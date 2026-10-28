# 04 · 模板引擎 jblade

> ⚠️ **本文档的编译器架构(§2)已被 v0.0 验证取代。**
> ADR-011 已确定基于 **jte** 构建,形态为「Blade 前端(自研)→ jte 源码 → jte 编译 → JbContext 运行时」。
> 原 §2 的自研 Emitter / 转义实现 / 热重载章节作废;上下文感知转义(§2.2)从 v0.6 提前到 v0.1 即可用;
> 工作量(§6)从 14 周下调为 7.5 周。另新增一条与 Blade 的不兼容:**HTML 必须良构**。
> 结论与实测数据见 [verification/v3-jblade-jte/RESULT.md](../verification/v3-jblade-jte/RESULT.md)。
> 本文档的全面改写列入 v0.1 的文档任务。

> **命名**:Java 生态已有名为 **Blade** 的 Web 框架(lets-blade)。本引擎命名为 **jblade**,文件后缀 `.jblade.html`,避免搜索与品牌冲突。

---

## 设计目标

1. 语法与 Laravel Blade 高度一致,Laravel 开发者零学习成本;
2. **模板编译成 Java 源码**——无反射、可 IDE 跳转、可断点调试、GraalVM native 友好;
3. **类型安全**:模板顶部声明参数类型,`{{ user.nmae }}` 拼错在编译期报错;
4. 开发模式下改模板**不重启**,毫秒级生效;
5. 组件化(`<x-card>`)与布局继承(`@extends`)并存。

> 技术参照:`gg.jte`、Rocker 都已验证「模板编译成 Java」这条路可行且性能极佳。jblade 的增量价值是 **Blade 语法 + Laravel 式组件/布局体系**。实现时应研读 jte 的编译器架构(尤其是源码位置映射与热重载类加载器),不必从零摸索。

---

## 1. 语法

### 1.1 参数声明(jblade 对 Blade 的关键改进)

```html
@params(User user, Paginator<Post> posts, boolean canEdit)
```

- 必须是模板第一个非注释行;
- 支持默认值:`@params(String title = "首页", int page = 1)`;
- 支持 import:`@import(java.time.LocalDate)`、`@import(static com.app.Helpers.*)`;
- 省略 `@params` 则模板接收一个 `Map<String,Object> ctx`(弱类型模式,仅为快速原型,文档中不推荐)。

### 1.2 输出

```html
{{ user.name }}                  {{-- HTML 转义输出 --}}
{!! post.htmlBody !!}            {{-- 原样输出 --}}
{{ user.name ?? 'Guest' }}       {{-- null 合并 --}}
{{-- 这是注释,不出现在产物里 --}}
@{{ vue.expression }}            {{-- 转义掉,交给前端框架 --}}
@verbatim ... @endverbatim       {{-- 整块不解析 --}}
```

`{{ user.name }}` 编译结果:

```java
__out.escaped(user.getName());   // 属性路径自动转 getter;String 走转义写出
```

### 1.3 表达式支持范围

jblade **不发明表达式语言**,直接把表达式原样当作 Java 表达式编译,仅做两项语法糖转换:

| jblade 写法 | 编译为 |
|---|---|
| `user.name` | `user.getName()`(或 `user.name()` for record,或字段) |
| `user.profile.city` | `user.getProfile().getCity()` |
| `posts.isEmpty` | `posts.isEmpty()` |
| `a ?? b` | `Jb.coalesce(a, b)` |
| `user.name?.length` | `Jb.nullSafe(...)` |
| 其余 | 原样 Java:`user.age() > 18 && !user.banned()` |

因此 lambda、三元、方法调用、`new`、静态方法全部可用:

```html
{{ posts.stream().map(Post::getTitle).collect(joining(", ")) }}
```

**这是刻意的设计**:发明一套 EL 意味着要实现解析、求值、错误报告、IDE 支持——成本高且永远不如宿主语言。直接用 Java 表达式,把 javac 变成免费的类型检查器。

### 1.4 控制结构

```html
@if (user.isAdmin())
@elseif (user.isEditor())
@else
@endif

@unless (user.verified) ... @endunless

@isset(user.avatar) ... @endisset
@empty(posts) ... @endempty

@for (int i = 0; i < 10; i++) ... @endfor

@foreach (posts as post)
  {{ loop.index }} / {{ loop.count }}
  @if (loop.first) 第一个 @endif
  @if (loop.last)  最后一个 @endif
  {{ loop.iteration }}  {{ loop.remaining }}  {{ loop.depth }}
  {{ loop.parent.index }}
@endforeach

@forelse (posts as post)
  ...
@empty
  <p>暂无内容</p>
@endforelse

@while (it.hasNext()) ... @endwhile

@switch (user.role)
  @case("admin") ... @break
  @case("editor") ... @break
  @default ...
@endswitch

@continue  @break
@continue(loop.index == 0)
```

`loop` 变量是编译期生成的局部 `LoopState` 对象,**非反射**,嵌套循环自动维护 `parent` 链。

### 1.5 布局继承

```html
{{-- resources/views/layouts/app.jblade.html --}}
@params(String title)
<!DOCTYPE html>
<html>
<head>
  <title>@yield('title', 'Jaravel')</title>
  @stack('styles')
</head>
<body>
  @include('partials.nav')
  <main>@yield('content')</main>
  @stack('scripts')
</body>
</html>
```

```html
{{-- resources/views/posts/index.jblade.html --}}
@params(Paginator<Post> posts)
@extends('layouts.app', title = 'Posts')

@section('title', 'Posts')

@section('content')
  @each('posts.partials.card', posts, 'post', 'posts.partials.empty')
  {{ posts.links() }}
@endsection

@push('scripts')
  <script src="/js/posts.js"></script>
@endpush

@prepend('styles')
  <link rel="stylesheet" href="/css/posts.css">
@endprepend
```

### 1.6 包含

```html
@include('partials.nav')
@include('partials.alert', type = 'error', message = msg)   {{-- 命名参数 --}}
@includeIf('partials.optional')
@includeWhen(user.isAdmin(), 'partials.admin-bar')
@includeFirst(['custom.header', 'default.header'])
@each('posts.card', posts, 'post')
```

### 1.7 组件

**匿名组件**(纯模板):

```html
{{-- resources/views/components/alert.jblade.html --}}
@props(String type = "info", String title = null)
<div class="alert alert-{{ type }}">
  @if (title != null) <strong>{{ title }}</strong> @endif
  {{ slot }}
  @if (hasSlot("footer")) <footer>{{ slot("footer") }}</footer> @endif
</div>
```

使用:

```html
<x-alert type="error" title="出错了">
  操作失败,请重试。
  <x-slot:footer>
    <a href="/help">帮助</a>
  </x-slot:footer>
</x-alert>

<x-alert :type="errorLevel" :title="msg.title" />   {{-- : 前缀表示值是 Java 表达式 --}}
<x-alert type="error" dismissible />                 {{-- 布尔简写 --}}
```

**类组件**(需要逻辑):

```java
@ViewComponent("post-card")
public class PostCard extends Component {
    public final Post post;
    public final boolean highlighted;

    public PostCard(Post post, boolean highlighted) {
        this.post = post; this.highlighted = highlighted;
    }

    public String excerpt() { return Str.of(post.getBody()).limit(120).toString(); }
    @Override public String view() { return "components.post-card"; }
    @Override public boolean shouldRender() { return post.isPublished(); }
}
```

```html
{{-- resources/views/components/post-card.jblade.html --}}
@params(PostCard self)
<article class="{{ self.highlighted ? 'featured' : '' }}">
  <h3>{{ self.post.title }}</h3>
  <p>{{ self.excerpt }}</p>
</article>
```

组件标签 `<x-post-card :post="post" highlighted />` 在编译期解析为:

```java
__out.component(new PostCard(post, true));
```

**编译期校验**:属性名拼错、类型不匹配、必填属性缺失,全部 javac 报错。

### 1.8 框架指令

```html
@csrf                               {{-- 隐藏 token input --}}
@method('PUT')                      {{-- 方法伪装 --}}
@auth ... @endauth
@auth('api') ... @endauth
@guest ... @endguest
@can('update', post) ... @elsecan('view', post) ... @endcan
@cannot('delete', post) ... @endcannot
@env('local') ... @endenv
@production ... @endproduction
@error('title') <span>{{ message }}</span> @enderror
@old('title', post.title)
@lang('posts.created')
@choice('posts.count', n)
@json(data)                         {{-- 安全的 JSON 输出到 JS --}}
@vite(['resources/css/app.css', 'resources/js/app.js'])
@inject(Clock, clock)               {{-- 从容器注入(不鼓励,但有时必要) --}}
@once ... @endonce                  {{-- 同一次渲染只输出一次 --}}
@fragment('row') ... @endfragment   {{-- 可单独渲染的片段,配合 htmx/turbo --}}
```

### 1.9 自定义指令

```java
// AppServiceProvider#boot
Blade.directive("datetime", expr -> "__out.raw(DateX.of(" + expr + ").format(\"yyyy-MM-dd HH:mm\"))");

Blade.if_("subscribed", () -> Auth.user().isSubscribed());   // → @subscribed ... @endsubscribed
```

---

## 2. 编译器架构

三阶段,与传统编译器一致:

```
.jblade.html
   │
   ▼
[1 Lexer]  扫描出 Token 流
   │  TEXT / ECHO_ESC / ECHO_RAW / DIRECTIVE / COMMENT / COMPONENT_OPEN / COMPONENT_CLOSE / SLOT
   │  记录每个 Token 的 (line, col),用于后续源码映射
   ▼
[2 Parser]  → AST
   │  TemplateNode
   │   ├── ParamsNode(声明的参数签名)
   │   ├── ExtendsNode / SectionNode / YieldNode / StackNode
   │   ├── TextNode / EchoNode(expr, escaped)
   │   ├── IfNode / ForeachNode / SwitchNode ...
   │   ├── IncludeNode(view, args)
   │   └── ComponentNode(name, attrs, slots)
   ▼
[3 Emitter]  → Java 源码
   │  每个模板一个 final class,一个 render 方法
   │  插入 // jblade:<file>:<line> 注释 + SMAP(JSR-045)源码映射
   ▼
[4 javac]  → .class
   │  开发模式:javax.tools.JavaCompiler 内存编译 + 自定义 ClassLoader
   │  生产模式:jaravel-maven-plugin 在 compile 阶段批量生成到 target/generated-sources
   ▼
运行时直接 new + render,零反射
```

### 2.1 生成物形态

输入 `resources/views/posts/index.jblade.html`:

```html
@params(Paginator<Post> posts)
<h1>Posts</h1>
@foreach (posts.items() as post)
  <li>{{ post.title }}</li>
@endforeach
```

生成 `io.jaravel.generated.views.posts.Index`:

```java
package io.jaravel.generated.views.posts;

public final class Index implements JbladeTemplate {
    public static final String SOURCE = "posts/index.jblade.html";

    private final Paginator<Post> posts;
    public Index(Paginator<Post> posts) { this.posts = posts; }

    @Override public void render(JbOutput __out, JbContext __ctx) {
        __out.raw("<h1>Posts</h1>\n");                 // jblade:posts/index:2
        LoopState __l0 = LoopState.of(posts.items());
        for (Post post : posts.items()) { __l0.advance();
            __out.raw("  <li>");                        // jblade:posts/index:4
            __out.escaped(post.getTitle());             // jblade:posts/index:4
            __out.raw("</li>\n");
        }
    }
}
```

静态文本在编译期合并为**预编码的 byte[] 常量**,写出时直接 `OutputStream.write(bytes)`,跳过字符编码。这是性能的主要来源。

### 2.2 转义

```java
public interface JbOutput {
    void raw(byte[] precomputed);          // 静态文本,零成本
    void raw(String s);
    void escaped(String s);                // HTML 转义
    void escaped(Object o);                // null → "";否则 String.valueOf 后转义
    void escaped(int i);                   // 数值重载,跳过转义扫描
    void component(Component c);
}
```

转义实现:逐字符扫描 `& < > " '`,使用预分配的 `byte[]` 缓冲 + 快速路径(无需转义时直接整段写出)。参照 jte 的 `escapeHtml` 实现。

**上下文感知转义**(v0.6):`<a href="{{ url }}">` 自动用 URL 转义,`<script>var x = {{ v }}</script>` 自动用 JS 转义。需要 Lexer 跟踪 HTML 解析状态,属于安全加分项,非首版必需。

### 2.3 布局继承的实现

`@extends` 是两阶段渲染:

```java
public final class PostsIndex implements JbladeTemplate {
    @Override public void render(JbOutput __out, JbContext __ctx) {
        // 1. 先把自己的 section 注册到 context(不输出)
        __ctx.section("content", (o, c) -> { /* content 的渲染代码 */ });
        __ctx.section("title", "Posts");
        __ctx.push("scripts", (o, c) -> { ... });

        // 2. 再渲染父模板,父模板的 @yield 从 context 取
        new layouts.App("Posts").render(__out, __ctx);
    }
}
```

`@yield('content')` 编译为 `__ctx.yield("content", __out)`。`@stack` 同理但支持多次 push 累加。

### 2.4 源码映射与错误报告

两道防线:

**编译期错误**。生成的 Java 源码中带 `// jblade:posts/index.jblade.html:14`。javac 报错后,`jaravel-maven-plugin` / 运行时编译器拦截 `Diagnostic`,把 Java 行号翻译回模板行号:

```
[ERROR] posts/index.jblade.html:14  找不到符号: 方法 getNmae()
        {{ post.nmae }}
                  ^^^^
        提示: Post 上存在相似的 getName()。
```

**这是本模块的头号质量要求**。模板编译错误如果以生成后的 Java 源码位置呈现,DX 直接崩塌。

**运行时异常**。生成类附带 JSR-045 SMAP,使异常栈直接显示模板文件与行号,IDE 可点击跳转、可下断点。

### 2.5 双模式

| | 开发模式 | 生产模式 |
|---|---|---|
| 编译时机 | 首次渲染 / 文件变更时 | Maven `compile` 阶段 |
| 编译器 | `javax.tools.JavaCompiler`(内存) | javac |
| 类加载 | 每次重编译换一个 `JbladeClassLoader` | 应用类加载器 |
| 产物位置 | `target/jblade-dev/`(便于调试) | `target/classes` |
| 变更检测 | `WatchService` 监听 + mtime 校验 | 无 |
| 首次渲染耗时 | 约 80~200ms/模板 | 0 |
| 反射 | 无 | 无 |

开发模式的类加载器策略:

```java
class JbladeClassLoader extends ClassLoader {
    // 只加载 io.jaravel.generated.views.*,其余委托父加载器
    // 模板变更 → 丢弃整个 loader,新建一个(模板类不持有应用状态,安全)
}
```

模板间互相引用(`@include`)导致的级联重编译:维护依赖图,变更时重编译受影响的闭包。

---

## 3. 视图 API

```java
// 弱类型(Map)
View.of("posts.index").with("posts", posts).with("title", "Posts");
View.of("posts.index").with(Map.of("posts", posts));

// 强类型(推荐,APT 从 @params 生成 Views 门面)
Views.postsIndex(posts);                     // 编译期校验参数
Views.layoutsApp("Posts");
```

`Views` 门面由 `jaravel-view-processor` 从所有 `@params` 声明生成:

```java
public final class Views {
    public static View postsIndex(Paginator<Post> posts) {
        return new TypedView(new io.jaravel.generated.views.posts.Index(posts));
    }
}
```

**这是 jblade 相对 Blade 的核心升级**:`return Views.postsIndex(posts)` 在参数类型/数量错误时编译失败,而 Laravel 的 `view('posts.index', compact('posts'))` 只能运行时发现。

### 3.1 其他 API

```java
View.exists("posts.index");
View.render("posts.index", data);          // 渲染为 String
View.renderFragment("posts.index", "row", data);   // 渲染片段,配合 htmx
View.share("appName", config.name());      // 全局共享变量
View.composer("posts.*", view -> view.with("tags", Tag.all()));   // 视图组装器
View.first(List.of("custom.home", "home"), data);
```

### 3.2 视图命名空间(包/模块化)

```java
// 包的 ServiceProvider#boot
loadViewsFrom("classpath:/vendor/sms/views", "sms");
// 使用:@include('sms::templates.verify')
```

---

## 4. 逃生口

| 需求 | 途径 |
|---|---|
| 想用 Thymeleaf / Freemarker | 实现 `ViewEngine` 契约并注册;官方提供两者的适配器 |
| 需要运行时动态模板(用户上传的模板) | `View.fromString(src, data)` —— 走弱类型 + 解释执行路径(有性能与安全代价,文档明确警告) |
| 模板里需要复杂逻辑 | 用类组件;或 `@inject` 服务;或在控制器算好传进来(推荐) |
| 需要 SSR 框架(Inertia/Livewire 风格) | v1.1 路线图,`jaravel-inertia` 适配器 |
| 需要直接写 HTML 拼接 | `Response.text("<html>...")` |

---

## 5. 与 Laravel Blade 的差异

| 项 | Blade | jblade | 原因 |
|---|---|---|---|
| 变量类型 | 弱类型,运行时解析 | `@params` 强类型声明 | 编译期校验,是核心卖点 |
| 表达式 | PHP 表达式 | Java 表达式 | 复用宿主语言 |
| `@php ... @endphp` | 支持 | **不支持** | 模板里写 Java 代码块破坏关注点分离;需要逻辑请用类组件 |
| 编译产物 | PHP 文件 | Java 类 | |
| 组件自动发现 | 约定目录 | 约定目录 + APT 索引 | 零运行时扫描 |
| 调用视图 | `view('a.b', $data)` | `Views.aB(data)` 或 `View.of("a.b")` | 前者类型安全 |
| 文件后缀 | `.blade.php` | `.jblade.html` | 让编辑器按 HTML 高亮 |
| 上下文感知转义 | 无 | v0.6 规划 | 安全增强 |

---

## 6. 工作量与风险

| 子项 | 估算 | 风险 |
|---|---|---|
| Lexer + Parser | 2 周 | 低,语法规整 |
| Emitter(基础指令) | 2 周 | 低 |
| 属性路径 → getter 转换 | 1 周 | 中,需处理 record / boolean `isX` / 字段直取 / 泛型 |
| 布局继承 + stack | 1 周 | 中,两阶段渲染的作用域正确性 |
| 组件系统(含 slot) | 2 周 | 中高,属性类型推断 + slot 闭包捕获 |
| 错误映射(模板行号) | 1.5 周 | **高,但不可省** |
| 开发模式热重载 | 1.5 周 | 中,类加载器与依赖图 |
| Maven 插件预编译 | 1 周 | 低 |
| `Views` 门面生成 | 1 周 | 中 |
| 转义 + 性能优化 | 1 周 | 低 |
| **合计** | **约 14 周 / 1 人** | |

最大风险是**错误报告质量**。建议在 v0.1 阶段就把「模板里拼错属性名 → 报错指向模板第 N 行并给出相似方法建议」这条链路打通,作为该模块的第一个验收用例。

---

## 7. 待决问题

- [ ] `@params` 缺失时的弱类型模式是否保留?保留会分裂使用方式;不保留则「快速改个页面」略显笨重。倾向保留但在文档中明确标注为「原型模式」。
- [ ] 组件属性的类型推断:`<x-alert :type="expr" />` 中 `expr` 的类型如何与 `@props(String type)` 核对?需要在 APT 中做局部类型推断,或退化为「生成代码交给 javac 报错」(后者更简单,错误信息稍差)。倾向后者 + 行号映射。
- [ ] 是否直接基于 jte 实现 jblade 的后端(只写 Blade 语法前端)?**优点**:省掉 Emitter/热重载/SMAP 约 5 周工作量且立刻获得成熟度。**缺点**:受 jte 的 AST 能力约束,组件与布局体系可能难以完全表达。**建议 v0.1 阶段做一次 2 天的可行性验证再决定。**
- [ ] 模板的 i18n:`@lang` 的键在编译期校验存在性?可做,但会让模板编译依赖语言包,增加耦合。倾向提供 `jaravel lang:check` 命令离线校验。
