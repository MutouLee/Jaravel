# v0.0 技术验证 · 总览

目的:在写框架代码之前,验证四个**会推翻架构**的技术点。每项都有预设退路,避免在错误的地基上盖 20 个月的房子。

| | |
|---|---|
| 执行日期 | 2026-10-28 |
| 环境 | JDK 21.0.11 (Temurin) · Maven 3.9.6 · Windows 11 Pro |
| 计划工期 | 3 周(`docs/17` v0.0) |
| 实际 | 1 天(得益于「只解析不编译」「扫 git 历史代替记录 2 周」等方法改进) |

---

## 结论速览

| # | 验证项 | 结论 | 对架构的影响 |
|---|---|---|---|
| **V1** | [模型访问器方案](v1-model-accessor/RESULT.md) | **原方案 B 证伪;新增方案 E' 胜出** | 重大。模型定义形态改变,且消除了一项 IDE 插件依赖 |
| **V2** | [路由 APT 表达式解析](v2-route-apt/RESULT.md) | **可行,采纳** | 确认。但需下调「精确诊断定位」的预期 |
| **V3** | [jblade 基于 jte](v3-jblade-jte/RESULT.md) | **可行,采纳** | 重大。工作量 14 周 → 7.5 周,且白得上下文感知转义 |
| **V4** | [热重载 L2 覆盖率](v4-hotreload-coverage/RESULT.md) | **L2 值得做,且应优先支持 JBR** | 确认 + 一项优先级调整 |

**四项全部通过,无一需要启用退路。可以进入 v0.1 垂直切片。**

---

## V1 · 模型访问器(ADR-010)· 最重要的一项

### 证伪:方案 B(ByteBuddy 编译后字节码织入)

```java
// Model.java 只有字段,期望织入 getter
public class Model { String name; }
// Caller.java
m.setName("Tom");  System.out.println(m.getName());
```

```
error: cannot find symbol  method setName(String)
error: cannot find symbol  method getName()
```

javac 在 `compile` 阶段编译**全部**源文件,织入发生在 `process-classes`(编译之后)。同模块内任何调用 getter 的源码都无法通过编译。**方案 B 技术上不可行**,只剩 Lombok 式的 javac 内部 API AST 篡改,风险不可接受。

### 新方案 E':public 字段 + 快照脏检查 + 关联走生成方法

```java
@Model(table = "users")
@HasMany(name = "posts", target = Post.class)
public class User extends UserBase {
    @Id public Long id;
    public String name;
    public String email;
}

user.name = "Jerry";      // 与 $user->name = 'Jerry' 等长
user.save();              // 与快照比对,只 UPDATE 变化的列
user.posts().get();       // 关联走 APT 生成的方法
```

实测 **18/18 通过**(13 项功能等价性 + 5 项关联形态),含边界情况:赋回原值不产生 UPDATE、null 变化可检测、save 后快照刷新。

### 过程中证伪的第二个坑

关联原设想用 `public Rel<Post> posts;`。实测发现父类构造器初始化子类字段**会被用户的显式初始化器静默覆盖**:

```
无显式初始化器 : INITIALIZED
有显式初始化器 : null          ← 写一个 = null 就让框架初始化失效
```

无声、依赖初始化顺序、用户无从察觉。因此关联改为**类级注解声明 + APT 生成访问器方法**,状态存在 `Model` 基类。

### 代码量实测

| 字段数 | 方案 C(手写访问器) | 方案 A(抽象声明) | **方案 E** | Laravel |
|---:|---:|---:|---:|---:|
| 5 | 19 | 14 | **8** | 8 |
| 15 | 49 | 34 | **18** | 8 |
| 30 | 94 | 64 | **33** | 8 |

增长规律:`C ≈ 3n+4` · `A ≈ 2n+4` · `E ≈ n+3` · `Laravel = 8`(常数)

### 类型安全(4/4 负例编译失败)

| 负例 | javac 报错 |
|---|---|
| 跨模型误用列 | `Col<UserC,String> cannot be converted to Col<UserE,?>` |
| 值类型不匹配 | `int cannot be converted to String` |
| 列名拼错 | `cannot find symbol: variable nmae` ⚠️ 无相似度建议 |
| 嵌套关联路径接错模型 | `required: RelPath<Post,N> / found: RelPath<UserE2,Post>` |

---

## V2 · 路由 APT(`docs/03` §9)

**核心能力成立**:`com.sun.source.util.Trees` 是 `jdk.compiler` **正式导出的受支持 API**(不是 `com.sun.tools.javac.*` 内部实现),无需 `--add-exports`,无 JDK 漂移风险。

正例:5 条路由 + 1 条 `resource()` 全部正确解析,含从 AST 父链提取 `.name("users.index")` 这类链式调用。

负例 **5/5 符合预期**:

| 用例 | 结果 |
|---|---|
| `resource()` 缺失方法 | ✅ 报错并列出缺哪些 + 给出 `.except()` 的现成写法 |
| 路径参数名与方法参数名不符 | ✅ 报错并提示 `@Path("id")` |
| 路由重复定义 | ✅ 报错并指出已定义位置 |
| 路由名重复 | ✅ 同上 |
| 动态构造的 URI | ✅ **优雅降级**为 NOTE,不误报 |

### 三个需要修正预期的发现

1. **javac 已做了一半工作**。`UserController::nonExistent` 是标准的方法引用解析失败,javac 自己就报错。APT 不应重复这件事,价值只在 javac 看不到的地方。
2. **诊断无法定位到精确行**。`Messager.printMessage` 没有接受 `Tree` 的重载。精确位置能取到(`Trees.getSourcePositions`),但只能写进消息文本,诊断锚点是 `define()` 方法。
3. **参数校验必须是单向的**。初版按个数比对,在正例上产生 2 个误报(`store(Object body)` 的 body 来自请求体,不是路径)。规则修正为:每个路径参数需有同名方法参数,方法可有额外参数。
4. **`Router` 需要约 40 个 arity 重载**。javac 能正确按 arity 解析方法引用(`::index`→H0、`::show`→H1 无歧义),但漏一个重载就编译失败。6 动词 × 5 arity + 分组/资源方法。接受这个成本。

---

## V3 · jblade 基于 jte(ADR-011)

jte 3.1.12(仅 3 个 jar,无传递依赖)覆盖 jblade 需求的约 70%,**两项原判「无法表达」中有一项被翻转**。

### 突破:`@stack`/`@push` 可以实现

jte 原生布局模型是反的(子模板**调用**布局,整页塞进 `content = @\`...\`` 参数),导致深层组件无法向外层布局贡献内容——这否决了 `@stack`。

解法:**不用 jte 的布局组合模型**,改为每个模板接收一个 `JbContext`,且 `section` 存入时**立即渲染**:

```java
public String section(String name, Content c) {
    var buf = new StringOutput();
    c.writeTo(new OwaspHtmlTemplateOutput(buf));   // ← 此刻触发全部嵌套 push
    String rendered = buf.toString();
    sections.put(name, out -> out.writeContent(rendered));
    return "";
}
```

实测:深层组件 A、B 的 CSS 进了 `<head>`,JS 进了页面底部 —— **完整的 Blade `@stack`/`@push` 语义成立**。

### 实测性能与质量

| 指标 | 实测 | 目标 |
|---|---|---|
| 缓存后渲染 | **0.21 ms** | — |
| 改模板后重编译 | **39 ms** | `docs/15`: < 100 ms ✅ |
| 热重载 | 开箱生效,无需手工 invalidate | — |
| 模板错误行号 | `error at errtest/typo.jte:6` ✅ 准确 | — |
| 上下文感知转义 | **已内置**(原列为 v0.6 愿望) | — |

### 一项无法消除的 Blade 不兼容

jte 在 HTML 模式下结构化解析 HTML,要求标签闭合。Blade 常见的「一个 partial 开 `<div>`、另一个闭」会直接编译失败:

```
error at line 2: Unclosed tag <div>.
```

双面评价:代价是真实的迁移摩擦;收益是正因为这个结构化解析,才能有上下文感知转义。**接受这个约束**,在文档中作为「有意的差异」说明。

### 工作量

| | 原自研 | 基于 jte |
|---|---:|---:|
| 合计 | 约 14 周 | **约 7.5 周** |

净省约 6.5 周。采纳形态:**Blade 前端(自研)+ jte 后端(复用)+ JbContext 运行时(自研)**。

---

## V4 · 热重载 L2 覆盖率(`docs/15` §9)

方法:不等 2 周,直接扫真实项目的 git 历史。关键是判断「是否只改方法体」**只需解析不需编译**,因此能分析任意 git 版本而无需解决依赖。

分类器自测 11/11 通过。

### 核心数据

| 覆盖范围 | spring-petclinic | halo | 判断 |
|---|---:|---:|---|
| 标准 HotSwap(任何 JDK) | 77.6% | 50.3% | 波动大 |
| **增强 HotSwap(JBR/DCEVM)** | **87.7%** | **87.0%** | **高度一致** |
| 真正必须重启 | 12.3% | 13.0% | **高度一致** |

两个性质、规模、活跃度差异很大的项目,后两项数字几乎相同。**纯新增(加方法/加字段)占结构性变更的约 89%** —— 而纯新增正是增强 HotSwap 能处理的。

### 结论:提升 JetBrains Runtime 的优先级

原设计把 JBR 列为「检测到就启用的增强项」。实测表明它应当是**推荐的默认开发运行时**:

- 把覆盖率从「波动的 50~78%」提升到「稳定的 87%」;
- 成本几乎为零 —— JBR 免费,随 IntelliJ 分发,多数 Java 开发者机器上已有。

### 过程中纠正的两个口径错误

1. 初版把 `pom.xml` 变更当成「0 重启的资源变更」,得出过于乐观的结论。依赖变更需要 L4 全量重启(~10s),已拆分为 BUILD / RELOADABLE / NON_RUNTIME 三档;
2. petclinic 200 个 commit 里 43 个是版本升级类机器人提交,让 L4 虚高到 32%。已加 `--exclude-chores` 过滤。

### 必须一并记录的局限

- **commit ≠ 一次保存**。真实重载单位是保存,commit 是多次保存的聚合(halo 平均 18 文件/commit)。因此所有数字都是**下界**;
- **未计入 Spring 容器状态**。改一个 `@Bean` 方法体虽然字节码可 HotSwap,但容器里已注入的实例不会变 → 实际仍需 L3。这会把真实的「0 重启」体验拉低到 87% 以下,需在 v0.3 实测。

---

## 汇总:对设计文档的修正项

四项验证共产出 **23 条**文档修正,已分别记录在各自的 RESULT.md §「对设计文档的修正项」。其中影响最大的五条:

| # | 文档 | 修正 |
|---|---|---|
| 1 | `docs/18` ADR-010 | 方案 B 证伪,采纳方案 E'(public 字段 + 快照 + 关联注解) |
| 2 | `docs/18` ADR-011 | 采纳基于 jte,形态为「Blade 前端 + jte 后端 + JbContext 编排」 |
| 3 | `DESIGN.md` §9 / `docs/16` §6.1 | parity 门禁必须明确为「按完整功能切片统计」—— 模型声明维度上 Laravel 是 O(1)、Java 是 O(n),比值永远达不到 1.3 |
| 4 | `docs/15` §3.4 | JBR 提升为推荐的默认开发运行时 |
| 5 | `docs/04` §2 | 编译器架构从「四阶段自研」改为「Blade 前端 → jte 源码 → jte 编译 → JbContext 运行时」,工作量 14 周 → 7.5 周 |

---

## 复现

```bash
bash v1-model-accessor/run.sh      # 或见该目录 RESULT.md §复现
bash v2-route-apt/run.sh
bash v3-jblade-jte/run.sh          # 需 Maven 联网
bash v4-hotreload-coverage/run.sh  # 需网络克隆样本仓库
```

---

## 下一步

进入 **v0.1 垂直切片**(`docs/17`)。两项调整:

1. jblade 的 3 周可压缩(V3 的工作量下降),释放的时间投入 Eloquent 主线;
2. 模型形态已定(方案 E'),v0.2 的「访问器生成」子项从 1~3 周降为约 0.5 周(只需生成 `*Base` + `*_` 元模型,无需处理访问器)。

v0.1 的退出判断不变:**`parity-check` 对 `01-crud-blog` 的比值 ≤ 1.3**,否则回头重新设计 API。
