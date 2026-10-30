package io.jaravel.devtools;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * L2:方法体热替换(docs/15 §2)。
 *
 * <p>docs/17 的 v0.3 复盘里把这一项**挪进了 v0.4**。在那之前
 * {@code ReloadCoordinator} 的 {@code HOTSWAP} 分支有一个
 * {@code onHotswap} 钩子,但**没有任何地方给它赋值** ——
 * 于是它永远走 else 降级到 L3。分类是对的,执行是空的。
 *
 * <h2>做得到什么,做不到什么</h2>
 * <p>标准 JVM 的 {@code redefineClasses} 只允许**方法体**变化。
 * 以下一律被拒(抛 {@code UnsupportedOperationException}):
 * 增删方法、增删字段、改签名、改继承关系、改注解。
 *
 * <p>所以 L2 是一个**优化,不是保证**。任何失败都降级到 L3,
 * 而 docs/15 §3.4 的原则没有变:核心体验不押在非标准 JVM 上。
 *
 * <p>⚠ 这也意味着**覆盖率低于 docs/15 §1 的估计**。在一个有 APT 的
 * 项目里,「改方法体」经常伴随注解或字段的变更(加一个
 * {@code @Fillable} 列、给模型加一个字段),那些都必须走 L3。
 * {@link ChangeClassifier#triggersAnnotationProcessing} 就是为此存在。
 *
 * <h2>Instrumentation 从哪来</h2>
 * <p>两条路,按顺序试:
 * <ol>
 *   <li>用户加了 {@code -javaagent:...jaravel-devtools.jar} ——
 *       {@link HotSwapAgent#premain} 已经把它发布好了;</li>
 *   <li>**运行时自挂载** —— 用 {@code jdk.attach} 挂到自己身上。</li>
 * </ol>
 *
 * <p>第二条有两个前置条件,都会被明确报出来而不是静默失败:
 * <ul>
 *   <li>JDK 9 之后自挂载默认**禁止**,要
 *       {@code -Djdk.attach.allowAttachSelf=true};</li>
 *   <li>{@code jdk.attach} 模块要在(JRE 上没有,JDK 上有)。</li>
 * </ul>
 *
 * <p>「明确报出来」是刻意的:一个静默降级的 L2 会让用户以为
 * 「改方法体 0 重启」生效了,然后困惑于为什么每次都等 2 秒。
 */
public final class HotSwapper {

    /**
     * 生成的 agent jar 里要带上 agent 的**类字节**。
     *
     * <p>只写一个 manifest 不够:{@code Agent-Class} 由系统类加载器
     * 加载,而开发期 Jaravel 的 jar 很可能在
     * {@code RestartClassLoader} 里(spring-boot-devtools 这么干),
     * 系统类加载器看不见它。把字节打进 jar 之后它自成一体。
     */
    private static final String AGENT_RESOURCE =
            "io/jaravel/devtools/HotSwapAgent.class";

    private volatile Instrumentation instrumentation;
    private volatile String unavailableReason;
    private volatile boolean attempted;

    // ================= 可用性 =================

    /**
     * L2 能用吗。
     *
     * <p>第一次调用会尝试自挂载 —— 那有几十毫秒的成本,
     * 所以结果被缓存(包括失败的结果:一个缺了 JVM 参数的进程
     * 再试一百次也还是缺)。
     */
    public synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            resolve();
        }
        return instrumentation != null;
    }

    /** 不可用的原因 —— 带着可以照着做的修复步骤 */
    public String unavailableReason() {
        available();
        return unavailableReason == null ? "" : unavailableReason;
    }

    private void resolve() {
        Object published = System.getProperties().get(HotSwapAgent.INSTRUMENTATION_KEY);
        if (published instanceof Instrumentation found) {
            instrumentation = found;
            return;
        }

        try {
            selfAttach();
        } catch (RuntimeException | Error e) {
            unavailableReason = explain(e);
            return;
        }

        Object afterAttach = System.getProperties().get(HotSwapAgent.INSTRUMENTATION_KEY);
        if (afterAttach instanceof Instrumentation found) {
            instrumentation = found;

            if (!found.isRedefineClassesSupported()) {
                instrumentation = null;
                unavailableReason = """
                        这个 JVM 不支持类重定义(isRedefineClassesSupported=false)。
                          方法体变更会降级到 L3(类加载器重启)。""";
            }
        } else {
            unavailableReason = "agent 挂载成功但没有发布 Instrumentation —— "
                    + "这通常意味着 agent jar 里的 HotSwapAgent 与应用里的"
                    + "不是同一份字节,请检查 jaravel-devtools 的版本。";
        }
    }

    /**
     * 挂到自己身上。
     *
     * <p>全程用反射:{@code com.sun.tools.attach} 在
     * {@code jdk.attach} 模块里,而那个模块**在 JRE 上不存在**。
     * 直接 import 会让 jaravel-devtools 在 JRE 上根本加载不了 ——
     * 一个开发期模块不该有这种硬要求。
     */
    private void selfAttach() {
        if (!Boolean.getBoolean("jdk.attach.allowAttachSelf")) {
            unavailableReason = """
                    L2(方法体热替换)不可用:JDK 9 之后**禁止进程挂载到自己**。

                      打开它(开发期才需要):
                        -Djdk.attach.allowAttachSelf=true

                      IDE 里加在 VM options;spring-boot:run 用
                        <configuration><jvmArguments>-Djdk.attach.allowAttachSelf=true</jvmArguments></configuration>

                      或者走 agent 那条路(不需要上面这个参数):
                        -javaagent:/path/to/jaravel-devtools.jar

                      不打开也能工作 —— 方法体变更会降级到 L3
                      (类加载器重启,~2s)。L2 是优化,不是保证。""";
            throw new IllegalStateException("allowAttachSelf 未开启");
        }

        Path agentJar = writeAgentJar();
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class)
                    .invoke(null, String.valueOf(ProcessHandle.current().pid()));
            try {
                vmClass.getMethod("loadAgent", String.class)
                        .invoke(vm, agentJar.toAbsolutePath().toString());
            } finally {
                vmClass.getMethod("detach").invoke(vm);
            }
        } catch (ClassNotFoundException e) {
            unavailableReason = """
                    L2 不可用:找不到 com.sun.tools.attach ——
                      当前运行在 JRE 上而不是 JDK 上(jdk.attach 模块只在 JDK 里)。
                      开发期请用 JDK。方法体变更会降级到 L3。""";
            throw new IllegalStateException("没有 jdk.attach 模块", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("自挂载失败:" + rootCauseMessage(e), e);
        } finally {
            try {
                Files.deleteIfExists(agentJar);
            } catch (IOException ignored) {
                // 临时文件,删不掉就留着
            }
        }
    }

    /** 现场生成一个自带 agent 字节的 jar */
    private Path writeAgentJar() {
        var manifest = new Manifest();
        var main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Agent-Class", HotSwapAgent.class.getName());
        main.putValue("Premain-Class", HotSwapAgent.class.getName());
        main.putValue("Can-Redefine-Classes", "true");
        main.putValue("Can-Retransform-Classes", "true");

        try {
            Path jar = Files.createTempFile("jaravel-hotswap-agent", ".jar");
            try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
                 InputStream agentBytes = HotSwapper.class.getClassLoader()
                         .getResourceAsStream(AGENT_RESOURCE)) {

                if (agentBytes == null) {
                    throw new IllegalStateException(
                            "classpath 上找不到 " + AGENT_RESOURCE
                          + " —— jaravel-devtools 的 jar 不完整?");
                }
                out.putNextEntry(new JarEntry(AGENT_RESOURCE));
                agentBytes.transferTo(out);
                out.closeEntry();
            }
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException("写不出临时的 agent jar:" + e.getMessage(), e);
        }
    }

    // ================= 热替换 =================

    /**
     * 结果。
     *
     * @param swapped 真的被替换掉的类
     * @param skipped 还没被加载过的类 —— 不需要替换(下次加载就是新的)
     * @param failures 类名 → 失败原因。**非空就必须降级到 L3**
     */
    public record Result(List<String> swapped, List<String> skipped,
                         Map<String, String> failures) {

        public boolean ok() {
            return failures.isEmpty();
        }

        public String describe() {
            var parts = new ArrayList<String>();
            if (!swapped.isEmpty()) parts.add("替换 " + swapped.size() + " 个");
            if (!skipped.isEmpty()) parts.add("跳过 " + skipped.size() + " 个(未加载)");
            if (!failures.isEmpty()) parts.add("失败 " + failures.size() + " 个");
            return parts.isEmpty() ? "没有可替换的类" : String.join(",", parts);
        }
    }

    /**
     * 把这些 {@code .class} 文件的新字节换进正在运行的 JVM。
     *
     * <p>⚠ **调用方必须检查 {@link Result#ok()}**,失败时降级到 L3。
     * 一次失败的替换会让「文件已经改了、运行时还是旧的」——
     * 而那是最难排查的一类状态。
     *
     * @param changed 变更的文件集合。非 {@code .class} 的会被忽略
     */
    public Result redefine(Collection<Path> changed) {
        var swapped = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        var failures = new LinkedHashMap<String, String>();

        if (!available()) {
            failures.put("(全部)", unavailableReason());
            return new Result(swapped, skipped, failures);
        }

        var loaded = loadedClassesByName();

        for (Path path : changed) {
            if (!path.toString().endsWith(".class")) continue;

            Optional<String> name = classNameOf(path);
            if (name.isEmpty()) {
                // 推不出类名就不要猜。按文件名去匹配已加载的类会在
                // 同名类(不同包)上换错对象,而那个后果比降级严重得多
                failures.put(path.toString(),
                        "推不出类名 —— 路径里没有 classes/ 这一段");
                continue;
            }

            List<Class<?>> targets = loaded.getOrDefault(name.get(), List.of());
            if (targets.isEmpty()) {
                skipped.add(name.get());
                continue;
            }

            // ★ 参与注解处理的类一律不走 L2
            Optional<String> aptAnnotation = aptAnnotationOn(targets);
            if (aptAnnotation.isPresent()) {
                failures.put(name.get(), """
                        带着 %s —— 它参与注解处理。
                          HotSwap 不会重跑 APT,于是生成的父类/元模型
                          (XxxBase、Xxx_)会与新代码脱节,而那种不一致
                          比多等两秒糟得多:报错会指向生成的代码。"""
                        .formatted(aptAnnotation.get()));
                continue;
            }

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                // 增量编译正在写这个文件时读会失败 —— 降级而不是重试:
                // 重试要猜等多久,而 L3 本来就要重新编译
                failures.put(name.get(), "读不到新字节:" + e.getMessage());
                continue;
            }

            try {
                var definitions = new ClassDefinition[targets.size()];
                for (int i = 0; i < targets.size(); i++) {
                    definitions[i] = new ClassDefinition(targets.get(i), bytes);
                }
                // 同名类可能被多个加载器各加载一份(devtools 重启之后
                // 会有残留的旧加载器)。全部换成同一份新字节是对的
                instrumentation.redefineClasses(definitions);
                swapped.add(name.get());
            } catch (UnsupportedOperationException e) {
                // 标准 HotSwap 的硬限制:增删方法/字段、改签名、改注解。
                // 这是**最常见**的失败,所以消息要说清它不是 bug
                failures.put(name.get(),
                        "不只是方法体变了(增删方法/字段、改签名或注解)—— "
                      + "标准 JVM 的 HotSwap 不支持这类变更");
            } catch (Throwable e) {
                failures.put(name.get(), rootCauseMessage(e));
            }
        }
        return new Result(swapped, skipped, failures);
    }

    /**
     * 驱动 APT 的注解**简单名**。
     *
     * <p>与 {@link ChangeClassifier} 里那份列表是同一件事的两个入口:
     * 那边看源码文本(改 {@code .java} 时),这边看已加载的
     * {@code Class}(改 {@code .class} 时)。
     *
     * <p>⚠ 两份列表必须一起维护。合成一份做不到:那边拿到的是
     * 文本,这边拿到的是 {@code Class} 对象,而
     * {@code jaravel-devtools} 只依赖 {@code jaravel-core} ——
     * 它**引用不到** {@code @Model} 这些注解类(它们在 eloquent 里)。
     * 所以这里按**简单名**比对。
     *
     * <p>按简单名比对会误伤一个自己写的、恰好也叫 {@code Scope} 的
     * 注解 —— 代价是那个类走 L3 而不是 L2,而那只是慢两秒。
     * 反过来(漏掉一个真的 APT 注解)的代价是脱节的生成代码。
     */
    private static final List<String> APT_ANNOTATIONS = List.of(
            "Model", "AuthModel", "Policy", "Command",
            "Fillable", "Hidden", "Cast", "Scope",
            "HasMany", "HasOne", "BelongsTo", "BelongsToMany", "MorphTo",
            "Listener");

    /**
     * 这些 Class 上有没有驱动 APT 的注解。
     *
     * <p>为什么必须查:{@link ChangeClassifier#triggersAnnotationProcessing}
     * 看的是**源码文本**,而走到 L2 这条路的是 {@code .class} 变更 ——
     * 那时手上没有源码。在 L2 接上之前那个方法**没有任何生产调用方**,
     * 也就是说这个风险此前是无人看守的。
     *
     * <p>⚠ 只看类级注解,不看字段/方法级的。理由:
     * {@code @Fillable} / {@code @Model} 这类改动一定伴随类级注解,
     * 而逐个字段去查会把一次热替换的成本从微秒拉到毫秒级
     * (反射读注解要解析常量池)。
     */
    private static Optional<String> aptAnnotationOn(List<Class<?>> targets) {
        for (Class<?> type : targets) {
            for (var annotation : type.getAnnotations()) {
                String simple = annotation.annotationType().getSimpleName();
                if (APT_ANNOTATIONS.contains(simple)) return Optional.of("@" + simple);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code .../classes/a/b/C.class} → {@code a.b.C}。
     *
     * <p>从**路径**推而不是解析字节码的常量池:后者要实现一个
     * class 文件解析器,而这里只需要一个名字。
     *
     * <p>找的锚点是 {@code /classes/} 或 {@code /test-classes/}
     * (Maven 的输出目录)。Gradle 的 {@code build/classes/java/main}
     * 也命中 {@code /classes/},但后面多了 {@code java/main} 两段 ——
     * 所以额外剥掉它们。
     *
     * <p>推不出来时返回 empty,调用方降级。**不猜**:按文件名匹配
     * 已加载的类会在同名类(不同包)上换错对象。
     */
    static Optional<String> classNameOf(Path path) {
        String normalized = path.toString().replace('\\', '/');
        if (!normalized.endsWith(".class")) return Optional.empty();

        int marker = lastIndexOfAny(normalized, "/test-classes/", "/classes/");
        if (marker < 0) return Optional.empty();

        String relative = normalized.substring(
                normalized.indexOf('/', marker + 1) + 1);

        // Gradle:classes/java/main/a/b/C.class
        for (String prefix : new String[] {"java/main/", "java/test/",
                                           "kotlin/main/", "kotlin/test/"}) {
            if (relative.startsWith(prefix)) {
                relative = relative.substring(prefix.length());
                break;
            }
        }

        String name = relative.substring(0, relative.length() - ".class".length())
                .replace('/', '.');
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    private static int lastIndexOfAny(String text, String... needles) {
        for (String needle : needles) {
            int at = text.lastIndexOf(needle);
            if (at >= 0) return at;
        }
        return -1;
    }

    /**
     * 已加载的类,按名字分组。
     *
     * <p>用 {@code getAllLoadedClasses} 而不是
     * {@code ClassLoader.loadClass}:后者会**触发加载**,于是一个
     * 「还没被用到的类」会因为我们去查它而被加载 ——
     * 那改变了应用的行为(静态初始化块会跑)。
     *
     * <p>一个名字可能对应多个 Class 对象(不同加载器),所以值是列表。
     */
    private Map<String, List<Class<?>>> loadedClassesByName() {
        var out = new LinkedHashMap<String, List<Class<?>>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            // 数组类与基本类型不可能是编译输出
            if (type.isArray() || type.isPrimitive()) continue;
            out.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add(type);
        }
        return out;
    }

    private static String explain(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
