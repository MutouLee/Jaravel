# V3 验证结果 · jblade 能否基于 jte 构建(ADR-011)

| | |
|---|---|
| 日期 | 2026-10-28 |
| 环境 | JDK 21.0.11 · jte 3.1.12(3 个 jar,无传递依赖) |
| 验证对象 | ADR-011 / `docs/04` §7 第 3 条待决问题 |
| 结论 | **可行,采纳基于 jte**——但必须是「jblade 做前端 + 自己编排布局」的形态,**不能**直接用 jte 的布局模型 |
| 复现 | `bash run.sh` |

---

## 1. 结论摘要

jte **能**承载 jblade 的全部核心需求,包括最初判断「无法表达」的 `@stack`/`@push`。

但有一个前提:**不能用 jte 的布局组合模型**。必须由 jblade 自己编排 section/stack 的收集与渲染时序。

| jblade 需求 | jte 原生 | 基于 jte 可实现 | 说明 |
|---|---|---|---|
| `{{ }}` 转义输出 | ✅ `${}` | ✅ | |
| `{!! !!}` 原样输出 | ✅ `$unsafe{}` | ✅ | |
| `@if/@elseif/@else` | ✅ | ✅ | |
| `@for` | ✅ | ✅ | |
| 类型安全参数 | ✅ `@param` | ✅ | 含默认值 |
| `@include` | ✅ `@template.x()` | ✅ | |
| 组件 + 属性默认值 | ✅ | ✅ | 实测通过 |
| 默认 slot + 命名 slot | ✅ `gg.jte.Content` | ✅ | 实测通过 |
| 自动 HTML 转义 | ✅ | ✅ | XSS 实测被转义 |
| **上下文感知转义** | ✅ **已有** | ✅ | 我原本列为 v0.6 愿望,jte 已内置 |
| 编译成 Java | ✅ | ✅ | |
| 错误行号映射回模板 | ✅ 质量好 | ✅ | `error at errtest/typo.jte:6` |
| 热重载 | ✅ 开箱 | ✅ | 实测 **39ms** 重编译 |
| `@extends/@section/@yield` | ⚠️ 模型反转 | ✅ | 需 `JbContext` 编排 |
| **`@stack/@push/@prepend`** | ❌ | ✅ | **需 section 立即渲染**,见 §3 |
| `loop.index/first/last` | ❌ | ✅ | 前端生成 `LoopState` 辅助对象 |
| `@forelse/@switch` | ❌ | ✅ | 前端转写为 if-else + for |
| 自定义指令 `@datetime` | ❌ | ✅ | 前端转写为方法调用 |
| 框架指令 `@csrf/@auth/@can/@error` | ❌ | ✅ | 前端转写为方法/模板调用 |
| 跨 partial 开闭 HTML 标签 | ❌ | **❌ 不可行** | jte 要求 HTML 良构,见 §4 |

---

## 2. jte 原生布局模型的问题

jte 没有 `@extends` 继承,布局是「接收 Content 参数的模板」:

```jte
@* page.jte —— 整个页面塞进 content 参数里 *@
@template.layout(
    title = "文章列表",
    content = @`
        <h1>文章列表</h1>
        ...整页内容,深度缩进...
    `)
```

两个问题:

1. **人机工效倒退**。页面主体被包在一个调用表达式的参数里,缩进加深,多个 section 在顶层无法平铺。Blade 的 `@extends` + 平铺的 `@section` 可读性明显更好。
2. **深层组件无法向上贡献内容**。Content 参数只能自上而下流动。实测:

```
组件 A、B 渲染出来了,但布局的 scripts 槽拿不到它们的贡献 —— 槽里只能由 page.jte 自己填。
```

这否决了 `@stack`/`@push` —— 而这是 Laravel 应用里每个带独立 JS/CSS 的组件都会用的能力。

---

## 3. 突破:用 JbContext 编排,`@stack` 可以实现

不用 jte 的 `@template.layout(content = ...)` 组合,改为每个模板接收一个 `JbContext`:

| Blade 写法 | 转写为 jte |
|---|---|
| `@section('x') ... @endsection` | `${ctx.section("x", @`...`)}` |
| `@yield('x')` | `${ctx.yield_("x")}` |
| `@push('x') ... @endpush` | `${ctx.push("x", @`...`)}` |
| `@stack('x')` | `${ctx.stack("x")}` |
| `@extends('layouts.app')` | 模板**末尾**追加 `@template.layouts.app(ctx = ctx)` |

### 3.1 第一版:只有「后置」的 stack 能工作

第一次实现把 section 存为惰性 `Content`。结果:

- ✅ 布局底部的 `@stack('scripts')` 拿到了深层组件 A、B 的 `<script>`;
- ❌ `<head>` 里的 `@stack('styles')` **是空的**。

原因:`<head>` 比 `${ctx.yield_("content")}` 先渲染,此刻深层组件还没执行,push 尚未发生。

### 3.2 修正:section 存入时**立即渲染**

```java
public String section(String name, Content c) {
    var buf = new StringOutput();
    var htmlBuf = new OwaspHtmlTemplateOutput(buf);   // HTML 模式需 HtmlTemplateOutput
    c.writeTo(htmlBuf);                                // ← 此刻触发全部嵌套 push
    String rendered = buf.toString();
    sections.put(name, out -> out.writeContent(rendered));   // 已转义,原样写出
    return "";
}
```

这正是 Blade 的两遍语义:**先把子模板主体渲染完(收集所有 section/push),再渲染布局**。body 只渲染一次,无额外开销。

修正后实测输出:

```html
<head>
  <title>栈测试页</title>
  <link rel="stylesheet" href="/css/A.css"><link rel="stylesheet" href="/css/B.css">   ← 深层组件贡献,进了 head
</head>
<body>
  <main>
    <h1>页面标题</h1>
    <div class="widget">组件 A</div>
    <div class="widget">组件 B</div>
  </main>
  <script src="/js/A.js"></script><script src="/js/B.js"></script>                      ← 深层组件贡献,进了尾部
</body>
```

**`@stack`/`@push`/`@prepend` 的完整 Blade 语义在 jte 之上成立。** 这是 V3 最重要的发现,它把 ADR-011 从「可能不可行」翻转为「可行」。

### 3.3 一个实现细节

HTML 模式下 `Content.writeTo` 需要 `HtmlTemplateOutput` 而非普通 `TemplateOutput`,否则:

```
ClassCastException: class gg.jte.output.StringOutput cannot be cast to class gg.jte.html.HtmlTemplateOutput
```

用 jte 自带的 `OwaspHtmlTemplateOutput` 包装即可。

---

## 4. 一个无法消除的 Blade 不兼容:HTML 必须良构

jte 在 `ContentType.Html` 下**结构化解析 HTML**。实测两处踩中:

```
error at line 3: Unclosed tag <script>. Maybe you want to use gg.jte.Content?
```

(文本里写了个字面量 `<script>`,被当成未闭合标签)

```
error at line 2: Unclosed tag <div>.
```

(Blade 常见的「`openDiv.blade.php` 开标签、`closeDiv.blade.php` 闭标签」写法)

**双面评价**:

- 代价:Blade 允许任意 HTML 片段,这类写法在 jblade 下会直接编译失败。从 Laravel 迁移的项目会遇到;
- 收益:正是这个结构化解析使 **上下文感知转义**成为可能(`<a href="{{ url }}">` 用 URL 转义、`<script>var x = {{ v }}</script>` 用 JS 转义)。我在 `docs/04` §2.2 里把它列为 v0.6 的愿望,**jte 已经内置了**。

**判定:接受这个约束**,并在文档中作为「jblade 与 Blade 的有意差异」显著说明,附迁移指引(用组件替代跨 partial 的标签配对)。

---

## 5. 性能与错误质量实测

| 指标 | 实测 | `docs/15` 的目标 |
|---|---|---|
| 首次渲染(含编译) | 426 ms | — |
| 缓存后渲染 | **0.21 ms** | — |
| 改模板后重编译 | **39 ms** | < 100 ms ✅ |
| 热重载是否需手工 invalidate | 不需要,开箱生效 | — |

错误报告质量:

```
gg.jte.TemplateException: Failed to compile template, error at errtest/typo.jte:6
.../JtetypoGenerated.java:15: 错误: 找不到符号
        jteOutput.writeUserContent(post.titel());
                                       ^
  符号:   方法 titel()
  位置: 类型为Post的变量 post
```

**模板文件名 + 精确行号都正确**。这解决了 `docs/04` §2.4 列为「头号质量要求」的大半——剩下的是 jblade→jte 这一跳的映射,由 jblade 自己的转写器维护(可控)。

---

## 6. 采纳形态:jblade = Blade 前端 + jte 后端 + JbContext 运行时

```
.jblade.html
   │
   ▼
[jblade 前端]  Lexer → Parser → 转写器         ← jblade 自己写(约 5~6 周)
   │  · Blade 语法 → jte 语法
   │  · @stack/@push/@section/@yield → ctx.* 方法调用
   │  · @forelse/@switch → if-else + for
   │  · loop 变量 → 生成 LoopState
   │  · @csrf/@auth/@can → 方法/模板调用
   │  · <x-alert> → @template.components.alert(...)
   │  · 维护 jblade行号 → jte行号 映射表
   ▼
.jte(中间产物,放 target/,用户通常不看)
   │
   ▼
[jte]  Parser → Java codegen → javac → ClassLoader   ← 复用(省约 5 周)
   │  · 转义 + 上下文感知转义
   │  · HTML 良构校验
   │  · 热重载(39ms)
   │  · Java 错误 → .jte 行号映射
   ▼
.class
   │
   ▼
[JbContext 运行时]  section/stack 收集 + 立即渲染编排   ← jblade 自己写(约 1 周)
```

### 6.1 省下与新增的工作量

| 项 | 原自研估算 | 基于 jte |
|---|---|---|
| Lexer + Parser | 2 周 | 2 周(Blade 语法仍需自己解析) |
| Emitter(生成 Java) | 2 周 | **0.5 周**(改为生成 jte 源码,简单得多) |
| 属性路径 → getter 转换 | 1 周 | **0**(jte 直接用 Java 表达式,`post.title()` 原样) |
| 布局继承 + stack | 1 周 | **1 周**(JbContext 编排,已验证) |
| 组件系统 + slot | 2 周 | **1 周**(映射到 jte 的 Content 参数) |
| **错误映射** | 1.5 周 | **0.5 周**(只需 jblade→jte 一跳) |
| 开发模式热重载 | 1.5 周 | **0**(jte 开箱) |
| Maven 插件预编译 | 1 周 | 0.5 周(调 jte 的预编译) |
| `Views` 门面生成 | 1 周 | 1 周 |
| 转义 + 性能优化 | 1 周 | **0**(jte 已做,且含上下文感知) |
| 新增:jblade→jte 转写器 | — | +1 周 |
| **合计** | **约 14 周** | **约 7.5 周** |

**净省约 6.5 周**,且立刻获得上下文感知转义与成熟的热重载。

### 6.2 代价

| 代价 | 评估 |
|---|---|
| 核心依赖 jte 的上游演进 | 中。jte 维护活跃、API 稳定、仅 3 个小 jar;但 jblade 的「转写目标」会被 jte 的语法变更影响 |
| 中间产物 `.jte` 增加一层调试复杂度 | 低。放在 `target/`,错误已映射回 `.jblade.html`;调试时可查 |
| HTML 必须良构 | 中。见 §4,有真实的 Blade 不兼容 |
| jte 的 `${}` 与 jblade 的 `{{ }}` 两套语法并存于管道中 | 低。用户只接触 `{{ }}` |
| `attribute = @`...`` 这类 jte 语法需要转写器精确生成 | 低。可控 |

---

## 7. 对设计文档的修正项

| # | 文档 | 修正内容 |
|---|---|---|
| 1 | `docs/18` ADR-011 | 状态改为「已采纳:基于 jte,形态为『Blade 前端 + jte 后端 + JbContext 编排』」,记录 jte 原生布局模型被否决的原因 |
| 2 | `docs/04` §2 | 编译器架构改为四阶段:jblade 前端 → jte 源码 → jte 编译 → JbContext 运行时。删除自研 Emitter / 转义 / 热重载章节 |
| 3 | `docs/04` §1.5 | `@extends/@section/@yield/@stack` 的实现改为 `JbContext` 编排,并说明 section 立即渲染的必要性 |
| 4 | `docs/04` §2.2 | 上下文感知转义从「v0.6 规划」改为「v0.1 即可用(jte 内置)」 |
| 5 | `docs/04` 新增小节 | 「与 Blade 的不兼容:HTML 必须良构」+ 迁移指引 |
| 6 | `docs/04` §6 | 工作量从 14 周改为 7.5 周 |
| 7 | `docs/17` v0.1 | jblade 的 3 周可压缩;释放的时间建议投入 Eloquent 主线 |

---

## 8. 待决问题(新增)

- [ ] jblade→jte 的行号映射策略:是「尽量 1:1 行对齐」(转写器约束更强,映射退化为恒等)还是「维护显式映射表」(更灵活,但要处理多行指令)?倾向后者。
- [ ] 中间 `.jte` 产物是否暴露给用户?倾向 `target/generated-jblade/` 可见但不纳入版本控制,`jaravel view:show posts.index` 命令可打印。
- [ ] jte 的 `HtmlPolicy` 是否全量启用?`PreventInlineEventHandlers` 等策略会拒绝 `onclick=`,对迁移项目可能过严。倾向默认启用 OWASP 标准组,可在 `config/view.yml` 放宽。
- [ ] jte 不支持自定义指令语法,`@datetime(x)` 必须在前端转写为方法调用。需确认 `Blade.directive()` 这个用户扩展点如何在**编译期**生效(用户注册的指令要被转写器看到 → 可能需要在 `config/view.yml` 声明,而非运行时 `Blade.directive()` 注册)。**这是一个真实的 API 变更**,`docs/04` §1.9 需相应修正。
