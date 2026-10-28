# V2 验证结果 · 路由 APT 表达式解析

| | |
|---|---|
| 日期 | 2026-10-28 |
| 环境 | JDK 21.0.11 (Temurin) · Windows 11 |
| 验证对象 | `docs/03` §1.2 与 §9 的第 1 条待决问题 |
| 结论 | **可行,采纳**(但有一条 DX 限制需降低预期) |
| 复现 | `bash run.sh` |

---

## 1. 结论摘要

| 问题 | 结论 |
|---|---|
| APT 能否读到 `define()` 方法体的 AST? | **✅ 能**,用 `com.sun.source.util.Trees`,**正式导出的受支持 API**,无需 `--add-exports` |
| 能否解析出 `(uri, 控制器, 方法, 路由名)` 四元组? | **✅ 能**,5/5 条路由全部正确提取 |
| 能否做 javac 做不到的校验? | **✅ 能**,4 类校验全部生效 |
| 能否取到精确行号? | **✅ 能**(`Trees.getSourcePositions` + `LineMap`) |
| 能否把诊断**定位**到精确行? | **❌ 不能**,`Messager` 无 `Tree` 重载 —— 只能锚定到 `define()` 方法 |
| 非字面量 URI 能否优雅降级? | **✅ 能**,降级为 NOTE 并跳过校验,不误报 |
| 控制器在已编译的 jar/classes 中时能否解析? | **✅ 能**,负例 n3/n4 的控制器来自 `build/sample` 的 class 文件 |

---

## 2. 一个前提澄清:javac 已经做了一半的工作

设计文档 `docs/03` §1.2 列的第一条 APT 职责是「校验 `UserController#show` 存在且可见」。

**实测发现:这件事 javac 自己就会做。** `UserController::nonExistent` 是标准的方法引用解析失败,javac 直接报错。APT 不需要、也不应该重复这件事。

因此 APT 的真实价值只在**javac 看不到的地方**,V2 据此重新界定了校验清单(见 §4)。

---

## 3. AST 解析能力验证(正例)

输入:

```java
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/users", UserController::index).name("users.index");
        r.get("/users/{id}", UserController::show).name("users.show");
        r.post("/users", UserController::store).name("users.store");
        r.put("/users/{id}", UserController::update);
        r.delete("/users/{id}", UserController::destroy);
        r.resource("/posts", PostController.class);
    }
}
```

APT 输出:

```
Note: [jaravel-routing] RouteProcessor 已启动,Trees = 可用
Note: [jaravel-routing] 发现路由定义类:routes.WebRoutes
Note: [jaravel-routing] resource 校验通过:PostController 具备全部所需方法 [index, create, store, show, edit, update, destroy]
Note: [jaravel-routing] ———— 路由表(共 5 条)————
Note: [jaravel-routing]   GET    /users                   → UserController#index  [users.index]
Note: [jaravel-routing]   GET    /users/{id}              → UserController#show  [users.show]
Note: [jaravel-routing]   POST   /users                   → UserController#store  [users.store]
Note: [jaravel-routing]   PUT    /users/{id}              → UserController#update
Note: [jaravel-routing]   DELETE /users/{id}              → UserController#destroy
```

**提取全部正确**,包括:
- 字符串字面量 URI(`LiteralTree`);
- 方法引用的限定符与方法名(`MemberReferenceTree`);
- **AST 父链上的链式调用** —— `.name("users.index")` 是在 `r.get(...)` 的**外层**调用,需要从 `TreePath` 往上走才能拿到。这是实现中最容易出错的一点,已验证可行。

关键代码:

```java
Trees trees = Trees.instance(processingEnv);     // 取得 AST 访问能力
TreePath path = trees.getPath(defineMethod);     // 拿到 define() 的 AST 路径
new TreePathScanner<>() {
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if (node.getArguments().get(1) instanceof MemberReferenceTree ref) {
            String controller = ref.getQualifierExpression().toString();
            String action = ref.getName().toString();
            // ...
        }
    }
}.scan(path, null);
```

---

## 4. 校验能力验证(5/5 负例符合预期)

| 用例 | 期望 | 实际 | 报错内容 |
|---|---|---|---|
| n1 `resource()` 缺失方法 | 失败 | ✓ | `resource("/half", HalfController.class) 需要以下方法,但控制器上不存在:[create, store, edit, update, destroy]`<br>`修复:实现这些方法,或用 .except("create", "store", ...) 排除` |
| n2 路径参数名与方法参数名不符 | 失败 | ✓ | `路径 "/users/{id}" 的参数 [id] 在 C2#show 上找不到同名参数(方法参数为 [userId])`<br>`修复:…若参数名有意不同,用 @Path("id") 显式绑定` |
| n3 路由重复定义 | 失败 | ✓ | `路由重复定义:GET /users`<br>`已定义于 DupRoute.java:6` |
| n4 路由名重复 | 失败 | ✓ | `路由名重复:"same"`<br>`已用于 DupName.java:6` |
| n5 动态 URI | **成功**(降级) | ✓ | `Note: 跳过非字面量 URI 的路由(DynamicUri.java:7)` |

`resource()` 的校验尤其有价值:Laravel 里控制器少一个方法只会在运行时 404,Jaravel 在编译期就拦住,并**列出缺哪些、给出 `.except()` 的现成写法**。

### 4.1 实现中纠正的一个设计错误

初版的参数校验按**个数**比对:路径参数数 ≠ 方法参数数 → 报错。结果在正例上产生 2 个误报:

```
error: 路径 "/users" 声明了 0 个参数 [],但 UserController#store 接收 1 个参数 [body]
error: 路径 "/users/{id}" 声明了 1 个参数 [id],但 UserController#update 接收 2 个参数 [id, body]
```

**原因**:方法参数不全来自路径,还可能来自请求体(`@Body`)、查询串(`@Query`)、容器注入(服务)。

**修正后的规则**:**每个路径参数都必须能在方法参数中找到同名项;方法上多出来的参数是合法的。** 修正后正例零误报。

> 这条要写进 `docs/03`:参数绑定的校验是**单向**的(路径 → 方法),不是双向。

---

## 5. DX 限制:诊断无法定位到精确行

`javax.annotation.processing.Messager` 的全部重载:

```java
printMessage(Kind, CharSequence)
printMessage(Kind, CharSequence, Element)
printMessage(Kind, CharSequence, Element, AnnotationMirror)
printMessage(Kind, CharSequence, Element, AnnotationMirror, AnnotationValue)
```

**没有接受 `Tree` 的重载。** 精确位置虽然能取到(`Trees.getSourcePositions()`),但无法交给 javac 作为诊断位置。

实际效果(锚定到 `define()` 方法 + 行号写进消息文本):

```
negative\n3\DupRoute.java:5: error: [jaravel-routing] DupRoute.java:7
    @Override public void define(Router r) {
                          ^
    路由重复定义:GET /users
    已定义于 DupRoute.java:6
```

- ✅ 有文件名,IDE 可点击跳转;
- ✅ 消息里有精确行号(`DupRoute.java:7`),人能看懂;
- ❌ 插入符 `^` 指向 `define` 方法名,不是出错的那一行;
- ❌ IDE 的红色波浪线会标在方法签名上,不是出错的那一行。

### 5.1 可能的升级路径(**未验证**)

`com.sun.source.util.Plugin`(javac 插件)能在编译的任意阶段介入,理论上可报告带精确位置的诊断。但**发出**定位诊断似乎仍需 `com.sun.tools.javac.util.Log`(内部 API)。

**这一条我没有实测,不作结论。** 若 v0.1 阶段认为这个 DX 缺口值得投入,应单独做一次验证。

### 5.2 建议的预期管理

把 `docs/03` 的承诺从「编译期精确定位」下调为:

> 路由错误在编译期暴露,诊断锚定到 `define()` 方法,**精确行号在错误消息中给出**。

这仍然远好于 Laravel 的运行时 404,但不如 javac 原生错误那样精确可点。

---

## 6. 一个重要的 DSL 设计发现:arity 重载

`Router.get(String, H0)` / `get(String, H1)` / `get(String, H2)` 的重载方案:

| 问题 | 结论 |
|---|---|
| javac 能否正确按 arity 解析方法引用重载? | **✅ 能**。`UserController::index`(0 参)走 H0,`::show`(1 参)走 H1,无歧义 |
| 重载不全会怎样? | **编译失败,报错清晰**。实测漏了 `put` 的 H2 重载:<br>`error: invalid method reference`<br>`unexpected instance method update(Long,Object) found in unbound lookup` |

**代价**:必须为每个 HTTP 动词 × 每个 arity 定义重载。

```
6 个动词 (get/post/put/patch/delete/any) × 5 个 arity (H0~H4) = 30 个重载方法
```

加上 `group`/`resource`/`match` 等,`Router` 接口约 **40 个方法**。这些方法体一行都没有(接口声明),可以由脚本生成,但会让 `Router` 的 javadoc 与 IDE 自动完成列表变长。

**替代方案(未验证)**:控制器方法统一签名为 `Response m(Request)`,只需一个 `H1` 重载。代价是回到「从 `Request` 里手动取参数」,失去 `show(Long id)` 这种直接签名——那是 `docs/03` §3.2 的核心卖点之一。**不建议**。

**结论**:接受 40 个重载。这是把参数绑定的类型安全做到编译期的必要成本,且成本一次性(写完即稳定)。

---

## 7. 对设计文档的修正项

| # | 文档 | 修正内容 |
|---|---|---|
| 1 | `docs/03` §1.2 | 删除「APT 校验控制器方法存在性」—— javac 已做。APT 职责改为 §4 的 4 类校验 + 生成无反射调用适配器 |
| 2 | `docs/03` §3.2 | 明确参数校验是**单向**的(每个路径参数需有同名方法参数;方法可有额外参数) |
| 3 | `docs/03` §1.2 | 「编译期精确定位」下调为「编译期暴露 + 消息内含精确行号」,并记录 `Messager` 无 `Tree` 重载这一硬限制 |
| 4 | `docs/03` §1.1 | 补充 `Router` 需要约 40 个 arity 重载的事实,说明这是类型安全参数绑定的必要成本 |
| 5 | `docs/03` §9 | 关闭第 1 条待决问题(APT 解析可行性);新增待决:是否投入 javac 插件以获得精确诊断位置 |

---

## 8. 采纳建议

**采纳 APT 方案**,不需要退到「运行时反射 + 启动期校验」。

理由:
1. 核心能力(AST 解析)完全可行,且用的是受支持 API,无 JDK 漂移风险;
2. 4 类校验的价值真实且是 Laravel 做不到的,尤其 `resource()` 的方法完备性检查;
3. 唯一缺陷(诊断定位到方法而非行)有明确的缓解(行号写进消息),且仍优于运行时发现;
4. 非字面量 URI 的降级路径已验证,不会把动态路由的用户挡在门外。
