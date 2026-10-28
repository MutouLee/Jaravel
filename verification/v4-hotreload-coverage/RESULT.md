# V4 验证结果 · L2 热重载覆盖率实测

| | |
|---|---|
| 日期 | 2026-10-28 |
| 环境 | JDK 21.0.11 · Windows 11 |
| 验证对象 | `docs/15` §9 第 1 条待决问题(「方法体修改占日常改动 60%」这个估计值) |
| 结论 | **L2 值得做,且应优先支持 JetBrains Runtime** —— 增强 HotSwap 可覆盖 **87%** 的 Java 文件修改 |
| 复现 | `bash run.sh`(需要网络克隆样本仓库) |

---

## 1. 方法论

### 1.1 原计划 vs 实际做法

`docs/17` 的 v0.0 计划是「在一个真实 Spring Boot 项目上记录 2 周的变更分类」。实际采用了更快且样本量更大的做法:**扫描真实开源项目的 git 历史**。

关键技术点:判断「是否只改了方法体」**只需要解析源码,不需要编译**。因此可以用 javac 的 `com.sun.source` parser 直接分析任意 git 版本,无需解决项目依赖、无需构建成功。

```java
JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> {}, List.of("-proc:none"), null, List.of(file));
CompilationUnitTree unit = task.parse().iterator().next();   // 只 parse,不 analyze/generate
```

### 1.2 判定规则

JVM 的 `Instrumentation.redefineClasses`(标准 HotSwap)只允许改方法体,不允许增删重命名字段/方法、不允许改方法签名/修饰符/继承关系。

工具据此提取每个 Java 文件的**结构骨架**并比对:

| 骨架内容 | 纳入比对 | 理由 |
|---|---|---|
| 类声明(修饰符 / extends / implements / 类型参数) | ✅ | 改了即不可 HotSwap |
| 字段(修饰符 / 类型 / 名字) | ✅ | 同上 |
| 方法(修饰符 / 返回类型 / 名字 / 参数类型 / throws) | ✅ | 同上 |
| **方法体** | ❌ | 这正是要忽略的 |
| 参数**名** | ❌ | 不影响字节码兼容性 |
| 注解 | ❌ | class 文件元数据,实践中 redefine 可容忍 |
| import | ❌ | 只影响常量池,HotSwap 可处理 |

### 1.3 三档分类

| 分类 | 含义 | 需要什么 |
|---|---|---|
| `METHOD_BODY_ONLY` | 骨架完全相同 | **标准 HotSwap**,任何 JDK,0 重启 |
| `STRUCTURAL_ADD_ONLY` | 只有新增(加方法/加字段/加内部类) | **增强 HotSwap**(JBR / DCEVM),0 重启 |
| `STRUCTURAL_HARD` | 含删除 / 改签名 / 改修饰符 / 改继承 | **必须重启**(L3 类加载器重启) |

区分后两档是本次验证最有价值的设计决定——见 §3。

### 1.4 自测

`ChangeClassifier --selftest` 覆盖 11 个用例,全部通过:

```
✓ 只改方法体                        → METHOD_BODY_ONLY
✓ 方法体内加语句与局部变量          → METHOD_BODY_ONLY
✓ 改参数名(不影响字节码)          → METHOD_BODY_ONLY
✓ 新增方法(增强 HotSwap 可处理)   → STRUCTURAL_ADD_ONLY
✓ 新增字段(增强 HotSwap 可处理)   → STRUCTURAL_ADD_ONLY
✓ 方法重载(新增)                  → STRUCTURAL_ADD_ONLY
✓ 删除方法 / 删除字段               → STRUCTURAL_HARD
✓ 改方法返回类型 / 参数类型          → STRUCTURAL_HARD
✓ 改修饰符 / 改继承关系              → STRUCTURAL_HARD
```

---

## 2. 过程中纠正的两个口径错误

诚实记录,因为它们都曾把结论带偏。

### 2.1 把 `pom.xml` 当成「0 重启的资源变更」

初版把所有非 Java 文件变更归为 `RESOURCE_ONLY`,得到「50.5% 的 commit 无需重启」的乐观结论。

核实后发现这些 commit 里大量是 `pom.xml` / `build.gradle`:

```
   30 xml      26 properties    24 gradle    15 md    12 yml    7 jar
```

**依赖变更需要 L4 全量 JVM 重启(~10s),不是 L1 的 0 重启。** 修正为三档:

- `BUILD`(pom/gradle/jar/wrapper)→ L4
- `RELOADABLE`(properties/yml/html/css/js/图片)→ L1,0 重启
- `NON_RUNTIME`(md/Dockerfile/CI/sql/gitignore)→ 与运行时无关,不计入

### 2.2 机器人提交污染

petclinic 的 200 个 commit 里有 **43 个**是版本升级类(`Upgrade to Spring Boot 4.1`、`Upgrade to Apache Maven 3.9.12`…),让 L4 占比虚高到 32%。

开发者的内循环(改代码 → 刷新)里不包含这类操作,因此加了 `--exclude-chores` 过滤。

---

## 3. 实测数据

两个样本:

| 项目 | 性质 | commit 数 | 规模特征 |
|---|---|---|---|
| spring-projects/spring-petclinic | 官方示例应用 | 200 | 约 3 个文件/commit |
| halo-dev/halo | 真实活跃产品(CMS) | 300 | 约 18 个文件/commit |

### 3.1 核心结果

**在「被修改的已有 .java 文件」中:**

| 覆盖范围 | petclinic | halo | 判断 |
|---|---:|---:|---|
| 标准 HotSwap(任何 JDK) | 77.6% | 50.3% | **波动大** |
| **增强 HotSwap(JBR/DCEVM)** | **87.7%** | **87.0%** | **高度一致** |
| 真正必须重启 | 12.3% | 13.0% | **高度一致** |

两个性质、规模、活跃度都差异很大的项目,在后两项上的数字几乎相同(87% / 13%)。这个一致性让结论可信度大幅提升。

### 3.2 结构性变更的原因分布

| 原因 | petclinic | halo |
|---|---:|---:|
| 新增 method | 45 | 304 |
| 新增 field | 7 | 132 |
| 新增 class | 1 | 10 |
| 删除 method | 17 | 37 |
| 删除 field | 3 | 15 |
| 改变 method(签名/修饰符) | 8 | 0 |
| 改变 field | 3 | 0 |
| 改变 class(继承) | 1 | 1 |

**纯新增占结构性变更的 ~89%**(halo:446/499)。这就是为什么区分 `ADD_ONLY` 与 `HARD` 如此关键——**绝大多数「结构性」变更其实是增强 HotSwap 能处理的纯新增。**

### 3.3 按 commit 统计(参考,不作为结论)

| | petclinic | halo |
|---|---:|---:|
| L1 资源热替换(0 重启) | 13.9% | 28.0% |
| L2 方法体(0 重启) | 36.1% | 7.0% |
| L3 结构性(<2s) | 20.8% | 51.0% |
| L4 构建文件(~10s) | 29.2% | 14.0% |

**这组数字不应作为结论**,原因见 §4.1。

---

## 4. 口径的局限(必须一并记录)

### 4.1 commit ≠ 一次保存

真实开发的重载单位是**一次保存**,而 commit 是多次保存的聚合。一个最终新增了方法的 commit,过程中可能经历十几次纯方法体的保存-刷新循环。

halo 平均 18 个文件/commit,一个 commit 往往是一整个功能——按 commit 统计必然把大量本可 HotSwap 的中间状态归入「结构性」。

**因此本报告的所有数字都是 L2/增强 HotSwap 覆盖率的下界(保守估计)。** 真实的内循环覆盖率应当更高。

### 4.2 未计入的因素

| 因素 | 影响方向 |
|---|---|
| Spring 容器的 bean 定义变更(加 `@Bean`、改注入) | 即使字节码可 HotSwap,**容器状态也需要刷新** → 实际需要 L3。本工具未判定这一点 |
| 注解变更(`@Transactional` 加到方法上) | 字节码可 redefine,但 Spring 的 AOP 代理需重建 → 实际需要 L3 |
| 新增文件(`FILE_ADDED`) | 必然需要 L3(新类加载)。petclinic 2.8%,halo 8.9% |
| Jaravel 的 APT 产物(改模型字段触发重新生成元模型) | 必然需要 L3 + 重跑 APT。这是 Jaravel 特有的额外成本,见 `docs/15` §9 |

**第 1、2 条很重要**:Spring 应用里改一个 `@Bean` 方法体虽然字节码可 HotSwap,但容器里已注入的实例不会变。这意味着实际的「0 重启」体验比 87% 低。需要在 v0.3 实现 L2 时实测这部分的影响。

---

## 5. 结论与建议

### 5.1 对 `docs/15` §9 第 1 条待决问题的回答

> 原估计:「方法体修改占日常改动 60%」。若实测只有 30%,则 L2 的投入产出比需重新评估。

**实测:标准 HotSwap 覆盖 50%~78%(按文件,commit 粒度,保守下界)。** 原估计 60% 落在实测区间内,**估计值成立,L2 的投入是合理的**。

### 5.2 一个比原计划更重要的发现:优先支持 JetBrains Runtime

原设计(`docs/15` §3.4)把 JBR 列为「检测到就自动启用的增强模式」,优先级低于标准 HotSwap。

实测表明应当**提升 JBR 的优先级**:

| 方案 | 覆盖率 | 获取成本 |
|---|---|---|
| 标准 HotSwap | 50%~78%(波动大) | 零,任何 JDK |
| **增强 HotSwap(JBR)** | **87%(稳定)** | **零成本**——JBR 免费,且随 IntelliJ 分发,绝大多数 Java 开发者机器上已经有了 |

JBR 把覆盖率从「波动的 50~78%」提升到「稳定的 87%」,而成本几乎为零。

**建议**:
1. `jaravel serve` 启动时检测 JVM 厂商,非 JBR 时打印一次性提示,给出切换方法;
2. `jaravel doctor` 把「是否运行在 JBR 上」列为检查项;
3. `jaravel new` 生成的 README 与文档的「开发环境」章节推荐 JBR;
4. 文档中标注两档覆盖率,不隐瞒标准 JDK 下的波动。

### 5.3 L3 仍然必须做好

即使有 JBR,仍有 **13%** 的 Java 文件修改必须重启,加上:

- 新增文件(3%~9%);
- Spring bean 定义/注解变更(未量化,但不可忽略);
- Jaravel 的 APT 产物重新生成(改模型字段)。

**L3 的「< 2s」目标不可放松**,它是兜底保证。`docs/15` §3.3 的「请求挂起 + 自动重放」设计价值也因此确认——它让这 13% 的体验从「报错」变成「慢了 2 秒」。

---

## 6. 对设计文档的修正项

| # | 文档 | 修正内容 |
|---|---|---|
| 1 | `docs/15` §9 | 关闭第 1 条待决问题。记录实测区间 50%~78%(标准)/ 87%(增强) |
| 2 | `docs/15` §3.4 | JBR 从「检测到则启用的增强项」提升为**推荐的默认开发运行时**,附覆盖率数据 |
| 3 | `docs/15` §1 | 覆盖率表格中「方法体修改约 60%」替换为实测的两档数字 |
| 4 | `docs/13` §2.6 | `jaravel doctor` 新增检查项:是否运行在 JBR 上 |
| 5 | `docs/15` 新增待决 | Spring bean 定义 / 注解变更对「字节码可 HotSwap 但容器需刷新」的影响,需在 v0.3 实测 |
| 6 | `docs/17` v0.0 | 验证方式从「记录 2 周变更」改为「扫描真实仓库 git 历史」,工期从 1 周压缩到 2 天 |

---

## 7. 工具的长期价值

`ChangeClassifier` 与 `HistoryAnalyzer` 不是一次性脚本,应保留并纳入 Jaravel:

1. **`jaravel serve` 的变更分类器**:`docs/15` §3.2 的 `ChangeClassifier` 正是这段逻辑的生产版本——判定用 L1/L2/L3/L4 哪一层。本工具已验证其判定规则正确(11/11 自测);
2. **`jaravel doctor --reload-profile`**:扫描用户项目的 git 历史,告诉他们「你的项目里 X% 的改动可以 0 重启,建议用 JBR」;
3. **CI 的 DX 回归**:`docs/15` §7 的 DX 基准可以用它量化。

因此 `verification/v4-hotreload-coverage/` 的代码应在 v0.3 迁入 `jaravel-devtools`。

---

## 8. 复现方式

```bash
# 克隆样本仓库
git clone --depth 200 https://github.com/spring-projects/spring-petclinic.git /tmp/sp
git clone --depth 300 https://github.com/halo-dev/halo.git /tmp/halo

# 编译工具
javac -encoding UTF-8 -d out src/*.java

# 自测
java -cp out ChangeClassifier --selftest x

# 分析
java -cp out HistoryAnalyzer /tmp/sp   200 --exclude-chores
java -cp out HistoryAnalyzer /tmp/halo 300 --exclude-chores

# 只看应用主代码
java -cp out HistoryAnalyzer /tmp/sp 200 --exclude-chores src/main/java
```

或直接 `bash run.sh`。
