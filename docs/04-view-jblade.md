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

> ### v0.4 实现状态:匿名组件 + 具名 slot + 属性袋可用(42 个测试),类组件未实现
>
> 这一节原先**一行都没实现**,而 `<x-post-card />` 会被当成普通 HTML
> **原样输出** —— 浏览器对未知标签不报错,只是什么都不渲染,
> 于是症状是「那块内容是空的」。v0.4 先把它改成报错,再实现。
>
> **已实现:**
>
> | 能力 | 写法 |
> |---|---|
> | 匿名组件 | `<x-alert type="danger">出错了</x-alert>` |
> | 自闭合 | `<x-badge label="热" />` |
> | 字面量属性 | `type="danger"` |
> | 表达式属性 | `:count="n"` |
> | 布尔简写 | `dismissible` → `true` |
> | 默认 slot | body 原地变成 `slot` 参数 |
> | **具名 slot** | `<x-slot:footer>…</x-slot>`,组件里 `hasSlot("footer")` / `slot("footer")` |
> | kebab 组件名 | `<x-post-card>` → `components/post-card.jblade.html` |
> | 命名空间 | `<x-mail::message>` → `components/mail/message.jblade.html` |
> | 嵌套组件 | 任意深度,slot 里也能嵌 |
> | **属性袋** | `{{ attributes }}`、`attributes.merge("class", "btn")`、`except` / `only` |
>
> **类组件在 v0.6 已实现(见下);`@aware` 仍未实现。**
>
> #### 具名 slot:读到就声明
>
> 组件**不需要**手写 `@props(gg.jte.Content footer = null)` ——
> 组件模板里出现 `slot("footer")` 或 `hasSlot("footer")`,转写器就
> 自动为它声明一个参数。组件模板自己就在转写器眼前,这是本地可见的,
> 不需要跨模板分析。
>
> 调用方的 `<x-slot:footer>` 变成那次调用的一个命名参数:
>
> ```
> <x-alert>正文<x-slot:footer>帮助</x-slot></x-alert>
>   → @template.components.alert(ctx = ctx, __slot_footer = @`帮助`, slot = @`正文`)
> ```
>
> 于是 `<x-slot:footr>` 打错名字是**响亮的**(jte:没有这个参数),
> 而不是静默少渲染一块。闭合写 `</x-slot>` 或 `</x-slot:footer>` 都收。
> slot 名原样作名字(`slot("sub-copy")` 对应 `<x-slot:sub-copy>`),
> 驼峰只活在生成码里。
>
> **⚠ slot 名必须是字面量** —— 它要在编译期变成一个参数名。
> `slot(which)` 直接报错,而不是等 javac 说「找不到方法 slot(String)」。
>
> **⚠ 具名 slot 内的错误行号是近似的。** 转写器自己的映射是准的
> (实测:生成码那一行确实映回模板那一行),失准在 **jte 自己的
> Java→jte 映射** —— 一次调用带两个 Content 块时,前一个块里的编译错误
> 被归到调用的收尾处。所以:
>
> | | 行号 |
> |---|---|
> | 默认 slot(调用里只有一个 Content 块) | **精确** |
> | 具名 slot | 落在 `<x-…>` … `</x-…>` 范围内,靠代码片段给上下文 |
>
> 换成 `slots = Map.of(...)` 或先提到局部变量都试过,同样不准 ——
> 这一跳不在我们手里。
>
> #### ★ 实现方式:复用 `@template`,不另造一套
>
> ```
> <x-alert type="danger" :count="n" dismissible />
>   → @template.components.alert(ctx = ctx, type = "danger", count = n, dismissible = true)
>
> <x-card>BODY</x-card>
>   → @template.components.card(ctx = ctx, slot = @`BODY`)
> ```
>
> 组件与 `@include` 在 jte 这一层是同一件事:带命名参数的模板调用。
> 另造一套意味着参数传递、行号映射、编译期检查都要各写一遍 ——
> 而它们注定分叉。
>
> 两个前提都是**先实测再设计**的:jte 支持把 `` @`...` `` 作为命名参数
> 传给模板调用,也支持 kebab-case 的模板文件名(于是组件文件名能与
> Blade 完全一致)。
>
> #### `@props` 要写 Java 类型 —— 与 Blade 的偏差
>
> ```html
> @props(String type = "info", String title = null)
> <div class="alert alert-{{ type }}">{{ slot }}</div>
> ```
>
> Blade 是 `@props(['type' => 'info'])`,无类型。这里必须写类型,
> 因为它落到 jte 的 `@param` 上。那是 Java 的固有成本,
> 但它换来一件 Blade 没有的事:**传错类型是编译错误**。
> Blade 里把一个 User 传给期望 string 的组件,症状是页面上出现
> `Object of class User could not be converted to string` ——
> 而且是在生产的那个页面上。
>
> `slot` 是**隐式**参数且默认 `null`:自闭合调用不传它,而 jte 的参数
> 没有默认值时调用方必须给 —— 否则每个 `<x-badge />` 都要写
> `slot = null`。
> ⚠ 后果:组件里 `{{ slot }}` 在没有 body 时输出空而不是报错。
> 要求 body 的组件得自己判断。
>
> #### 两个容易踩的点
>
> **属性名的连字符转驼峰**:`:user-id="x"` → `userId = x`。
> Java 的参数名带不了连字符,不转的话这个最自然的写法会变成语法错误,
> 而错误会指向生成的 jte 代码 —— 与模板里那一行对不上。
>
> **`{{ slot }}` 不会被二次转义**:slot 是**已经渲染好**的 `Content`,
> 不是用户输入。body 里的用户输入在 body 渲染时就已经转义过了 ——
> 两条测试分别钉住这两面。
>
> #### 属性袋为什么没有
>
> 属性袋的定义是「调用方传了、组件没在 `@props` 里声明的属性」,
> 而转写器**只看调用方那一行** —— 分不出 `type="error"`(是 prop)和
> `id="x"`(进袋)。这一条曾被记为「不是暂时没做」,因为它需要跨模板分析。
>
> **做法:给转写器一个 `ComponentProps` 查询。** 组件源码就在仓库里,
> 扫它 `@props` 那一行拿到名字,够了 —— 不需要完整转写,也不需要类型信息。
> 查不到(或那个模板用的是 `@params`)时退回旧行为:全部当成声明过的参数。
> `@include` 的目标、还没写出来的组件都走这条路,所以是退回而不是报错。
>
> ```html
> {{-- components/alert.jblade.html --}}
> @props(String type = "info")
> <div {{ attributes.merge("class", "alert alert-" + type) }}>{{ slot }}</div>
> ```
> ```html
> <x-alert type="error" id="login-error" class="mt-4" />
>   → <div id="login-error" class="alert alert-error mt-4">
> ```
>
> `class` 与 `style` 是**追加**(组件的默认值在前、调用方的在后),
> 其他属性调用方优先。覆盖语义的话,调用方加一个外边距就会悄悄删掉
> 组件自己的样式 —— 那正是属性袋存在的理由。
>
> `null` / `false` 的属性不输出,`true` 输出成裸属性(`disabled`)。
> 属性名原样保留(`data-test` 不转驼峰 —— 它要出现在 HTML 里)。
>
> #### ★ `{{ attributes }}` 在生成码里走 `$unsafe`
>
> 两件事逼出这个特例:jte 的 HTML 模式**禁止**在属性名位置写 `${}`
> (报 Illegal HTML attribute name),而那正是属性袋唯一的用处;
> 整体走转义又会变成 `id=&quot;x&quot;`,页面上一串乱码。
>
> **不转义 ≠ 不安全**:`Attributes` 自己转义每一个值(含单引号)。
> 不转义的只有属性之间的空格和等号。组件作者写的还是 `{{ attributes }}`。
>
> #### ⚠ 热重载:调用方的时间戳要算上它调用的组件
>
> 路由是在**转写调用方**的时候决定的。给组件的 `@props` 加一个属性时,
> 调用方的文件没动过,jte 不会重新转写它 —— 那个属性会继续走属性袋。
> 症状是「我声明了 prop,组件里却读不到」,而改完组件再随便动一下调用方的
> 文件就好了,极难复现。所以转写结果会报告它调用了哪些组件,
> 引擎在 `getLastModified` 里取两者的最大值。
>
> `<x-mail::message>` 那套邮件组件(docs/11 §1.4)**已经写出来了** ——
> 它是这套组件系统的第一个真实消费者,也是框架自带视图里第一批走
> classpath 回落的。那边记了两处被「不引入 Markdown 解析器」逼出来的
> 设计偏差。

> ### v0.6:类组件已实现(12 个测试)
>
> 类组件现在能用了,而**没有引入 APT 索引**:转写器从组件模板的
> `@params` 读出类名、加载它,把**公开构造函数**当属性表 ——
> 属性按参数名配对(kebab 与 camel 两种写法都认)。类名写在模板里,
> 只有一个出处;再生成一份索引会让两边漂。
>
> ```java
> public class PostCard extends Component {
>     public final Post post;
>     public PostCard(Post post) { this.post = post; }
>     @Override public String view() { return "components.post-card"; }
> }
> ```
>
> ```html
> {{-- components/post-card.jblade.html --}}
> @import(app.http.components.PostCard)
> @params(PostCard self)
> <article>{{ self.post.title }}</article>
> ```
>
> ```html
> <x-post-card :post="post" />
>   → @template.components.post-card(ctx = ctx, self = new PostCard(post))
> ```
>
> - 缺属性 / 多属性 / 重复属性 / 多个 public 构造函数 → **转写期报错**,
>   并指回模板行;类型不对由 javac 报;
> - 属性名按构造参数名配对,`:user-id` 与 `userId` 是同一个;
> - 字面量属性(`title="x"`)是 Java 字符串;数字等非字符串参数用表达式
>   写法(`:count="1"`),布尔简写直接写属性名(`disabled`);
> - 构造函数参数名需要 `-parameters`(框架 parent pom 与示例都加了)。
>   缺了会直接报错并说清怎么加,而不是把 `arg0` 当成属性名;
> - `examples/blog` 的首页卡片就是类组件 `<x-post-card :post="post" />`。
>
> **slot**:组件模板的 `@params` 里把 slot 声明成
> `gg.jte.Content` 参数(默认 slot 叫 `slot`,具名 slot 叫 `__slot_<name>`):
>
> ```html
> @params(PostCard self, gg.jte.Content slot = null, gg.jte.Content __slot_footer = null)
> ```
> ```html
> <x-post-card :post="post">
>   正文
>   <x-slot:footer>页脚</x-slot:footer>
> </x-post-card>
> ```
>
> 没声明的 slot 在转写期报错;自闭合调用时这些参数保持默认值(null)。
> 模板的 `@params` 要写在一行 —— 与普通模板一样,它要求是第一个非注释行。
>
> **这一版的限制**:
>
> - `shouldRender()` 不在标签调用里强制执行(先构造再判断会构造两次);
> - `view()` 不参与标签解析 —— 标签按 `components/<tag>` 约定;
> - 类组件没有属性袋(`{{ attributes }}`),多传的属性是错误;
> - 多个 public 构造函数需要先收敛成一个。

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
@import(app.http.components.PostCard)
@params(PostCard self)
<article class="{{ self.highlighted ? 'featured' : '' }}">
  <h3>{{ self.post.title }}</h3>
  <p>{{ self.excerpt }}</p>
</article>
```

组件标签 `<x-post-card :post="post" highlighted />` 在编译期解析为:

```java
@template.components.post-card(ctx = ctx, self = new PostCard(post, true))
```

**编译期校验**:属性名拼错、必填属性缺失是转写期错误;类型不匹配由 javac 报错。

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
| 组件自动发现 | 约定目录 | 约定目录 + 模板 `@params`(类组件) | 类名只有一个出处 |
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
