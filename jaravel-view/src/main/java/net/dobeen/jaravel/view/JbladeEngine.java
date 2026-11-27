package net.dobeen.jaravel.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.output.StringOutput;

/**
 * jblade 引擎(ADR-011 的落地形态)。
 *
 * <pre>
 * .jblade.html
 *    │  JbladeTranspiler(本项目)
 *    ▼
 * .jte 源码(内存中,不落盘)
 *    │  jte:解析 → 生成 Java → javac → ClassLoader
 *    ▼
 * .class
 *    │  JbContext 编排 section/stack
 *    ▼
 * HTML
 * </pre>
 *
 * <p><b>关键设计:</b>不把 {@code .jte} 写到磁盘,而是实现一个 {@link CodeResolver}
 * 在 jte 索要模板源码时**按需转写**。这样:
 * <ul>
 *   <li>无中间文件管理问题,{@code @include} 的依赖解析由 jte 完成;
 *   <li>行号映射表与转写同时产生,一一对应;
 *   <li>jte 自带的热重载(V3 实测 39ms)直接生效 —— 只需让
 *       {@link CodeResolver#getLastModified} 反映 {@code .jblade.html} 的时间戳。
 * </ul>
 */
public final class JbladeEngine implements ViewEngine {

    public static final String SUFFIX = ".jblade.html";

    /**
     * 「生成类加载不到」的消息 —— 一个**匿名内部类**的名字,斜杠或点号形式。
     *
     * <p>必须以 {@code $数字} 结尾:jte 的**编译错误**消息里也带着生成文件的
     * 路径({@code gg/jte/generated/ondemand/JtetGenerated.java}),一个宽松的
     * 模式会把「模板里写错了字段名」的报错也换成那条内部类提示 ——
     * 那正是第一版干的事,而它只在 Linux 上暴露:Windows 上那个路径是
     * 反斜杠,压根没被匹配上,于是本地全绿、CI 全红。
     */
    private static final Pattern MISSING_GENERATED =
            Pattern.compile("gg[./]jte[./]generated[./][\\w./]*\\$\\d+");

    /** jte 报错里的位置,形如 {@code error at posts/index.jte:6} */
    private static final Pattern JTE_LOCATION =
            Pattern.compile("(?:error at|Failed to compile template, error at)\\s+(\\S+?):(\\d+)");

    private final TemplateRepository repository;
    private final TemplateEngine jte;
    private final TranspilingCodeResolver resolver;
    private final ClassLoader typeLoader;
    private final boolean devMode;

    private JbladeEngine(TemplateRepository repository, Path classDirectory,
                         ClassLoader parent, boolean devMode) {
        this.repository = repository;
        this.devMode = devMode;
        this.typeLoader = parent != null ? parent : JbladeEngine.class.getClassLoader();
        this.resolver = new TranspilingCodeResolver(repository, typeLoader);
        this.jte = TemplateEngine.create(resolver, classDirectory, ContentType.Html, parent);
    }

    private JbladeEngine(TemplateRepository repository, ClassLoader parent,
                         boolean devMode, TemplateEngine precompiled) {
        this.repository = repository;
        this.devMode = devMode;
        this.typeLoader = parent != null ? parent : JbladeEngine.class.getClassLoader();
        this.resolver = new TranspilingCodeResolver(repository, typeLoader);
        this.jte = precompiled;
    }

    /** 开发模式:从目录读取,文件变更自动重编译 */
    public static JbladeEngine forDirectory(Path viewRoot, Path classDirectory, ClassLoader parent) {
        return new JbladeEngine(new DirectoryRepository(viewRoot, parent), classDirectory, parent, true);
    }

    /**
     * 同一个 (视图目录, class 目录, 类加载器) 只建**一个**引擎,JVM 内共享。
     *
     * <h2>★ 为什么必须共享</h2>
     * <p>运行时模式下 jte 把模板编译成 {@code .class} 文件写进 class 目录,
     * 再用自己的 ClassLoader 从那里加载。一个 JVM 里出现**两个**引擎
     * (两个 Spring 上下文:测试里的默认上下文 + 热重启验收那种自己起
     * 容器的测试)时,它们共用同一个目录却各有一个 ClassLoader ——
     * 后建的那个重新编译并覆盖文件,而先建的那个 ClassLoader 已经加载了
     * 外层类、还没加载**惰性的内部类**。
     *
     * <p>于是第一次执行到 {@code @fragment} / 组件 slot 那种生成匿名
     * {@code Content} 子类的地方,它去加载 {@code XxxGenerated$1} ——
     * 文件刚被另一个引擎重写过,报 {@code NoClassDefFoundError},
     * 而消息里只有一个 {@code gg/jte/generated/ondemand/…$1} 的类名,
     * 与「模板哪里写错了」毫无关系。
     *
     * <p>这个故障是 CI(Linux)上出现、本地复现不了的那一类:它取决于
     * 文件覆盖与惰性加载的相对时序,Windows 上的文件锁恰好把它盖住了。
     * 所以修法不是重试或加锁,而是**让同一份模板在一个 JVM 里只有一个
     * 引擎与一个 ClassLoader**。
     *
     * <p>顺带的好处:多上下文的测试套件不再反复编译同一批模板。
     *
     * <p>键里带类加载器:热重载换掉 app 类加载器之后必须重新编译
     * (旧引擎编出来的类引用的是旧加载器里的类型)。
     */
    public static JbladeEngine sharedForDirectory(Path viewRoot, Path classDirectory,
                                                  ClassLoader parent) {
        var key = new SharedKey(viewRoot.toAbsolutePath().normalize(),
                classDirectory.toAbsolutePath().normalize(),
                parent == null ? JbladeEngine.class.getClassLoader() : parent);
        return SHARED.computeIfAbsent(key,
                k -> forDirectory(k.viewRoot(), k.classDirectory(), k.parent()));
    }

    private record SharedKey(Path viewRoot, Path classDirectory, ClassLoader parent) {}

    private static final Map<SharedKey, JbladeEngine> SHARED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 清掉共享缓存 —— 只给测试用。
     *
     * <p>生产里不需要:进程退出即消失,而热重载换加载器时键本来就变了。
     */
    public static void clearShared() {
        SHARED.clear();
    }

    /** 生产模式:从 classpath 读取 */
    public static JbladeEngine forClasspath(String resourcePrefix, Path classDirectory, ClassLoader parent) {
        return new JbladeEngine(new ClasspathRepository(resourcePrefix, parent), classDirectory, parent, false);
    }

    /**
     * 预编译模式:加载 {@code jaravel-maven-plugin} 在构建期生成的模板类。
     *
     * <p>生成的类在 {@code gg.jte.generated.precompiled} 包下,由 jte 的
     * {@link TemplateEngine#createPrecompiled(ContentType)} 从应用 classpath 加载。
     * 同时读取插件写出的 {@code META-INF/jaravel/jblade-metadata.properties},
     * 让运行期异常仍能映射回 {@code .jblade.html} 的行号。
     */
    public static JbladeEngine forPrecompiled(ClassLoader parent) {
        var engine = new JbladeEngine(new ClasspathRepository("views", parent), parent, false,
                TemplateEngine.createPrecompiled(ContentType.Html));
        engine.loadPrecompiledMetadata();
        return engine;
    }

    private void loadPrecompiledMetadata() {
        try (InputStream in = typeLoader.getResourceAsStream(
                "META-INF/jaravel/jblade-metadata.properties")) {
            if (in == null) return;
            var props = new java.util.Properties();
            props.load(in);

            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("lineMap.")) continue;
                String view = key.substring("lineMap.".length());
                int[] mapping = parseInts(props.getProperty(key));
                resolver.putMetadata(view, LineMap.fromArray(view, mapping), null);
            }
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("params.")) continue;
                String view = key.substring("params.".length());
                String raw = props.getProperty(key, "");
                List<String> names = raw.isBlank() ? List.of()
                        : java.util.Arrays.stream(raw.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList();
                resolver.putMetadata(view, null, names);
            }
        } catch (IOException | NumberFormatException e) {
            // metadata 损坏不该让应用起不来:模板本身仍可渲染,只是错误定位退化
        }
    }

    private static int[] parseInts(String raw) {
        if (raw == null || raw.isBlank()) return new int[0];
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
    }

    /** 测试:直接给定模板内容 */
    public static JbladeEngine forMap(Map<String, String> templates, Path classDirectory, ClassLoader parent) {
        return new JbladeEngine(new MapRepository(templates), classDirectory, parent, true);
    }

    // ================= 渲染 =================

    @Override
    public String render(String name, Map<String, Object> data) {
        String templatePath = toJtePath(name);
        var ctx = new JbContext();

        Map<String, Object> params = new LinkedHashMap<>(data == null ? Map.of() : data);
        params.put("ctx", ctx);

        var out = new StringOutput();
        try {
            jte.render(templatePath, params, out);
        } catch (TemplateException e) {
            throw isPrecompileRequired(e) ? precompileRequired(name, e) : translateError(e, name);
        } catch (JbladeException e) {
            throw e;   // 转写阶段的错误已有正确位置
        }
        return out.toString();
    }

    /**
     * 只返回某个 {@code @fragment} 的内容。
     *
     * <p>★ **整页照样渲染一遍**,然后把那一段取出来。
     *
     * <p>听起来浪费,但另一条路更糟:要「只渲染片段」就得让生成的模板
     * 类支持从中间进入,也就是把一个模板切成多个入口方法 ——
     * 而片段里引用的变量、所在的 {@code @foreach} 的循环变量、
     * 外层 {@code @if} 的条件,全都来自它上面的代码。切开之后那些
     * 上下文就没了,表现是「局部刷新时变量是 null」。
     *
     * <p>渲染一遍的代价是可度量且可接受的(模板渲染在毫秒级,
     * 而局部刷新本来就是为了省**网络**与**前端重排**,不是省 CPU)。
     */
    @Override
    public String renderFragment(String name, String fragment, Map<String, Object> data) {
        String templatePath = toJtePath(name);
        var ctx = new JbContext();

        Map<String, Object> params = new LinkedHashMap<>(data == null ? Map.of() : data);
        params.put("ctx", ctx);

        var out = new StringOutput();
        try {
            jte.render(templatePath, params, out);
        } catch (TemplateException e) {
            throw isPrecompileRequired(e) ? precompileRequired(name, e) : translateError(e, name);
        } catch (JbladeException e) {
            throw e;
        }

        return ctx.fragmentValue(fragment).orElseThrow(() -> new JbladeException(name, 1,
                "模板 %s 里没有名为 \"%s\" 的 @fragment。%s".formatted(name, fragment,
                        ctx.fragmentNames().isEmpty()
                                ? "\n  这个模板里一个 @fragment 都没有。"
                                : "\n  有的是:" + ctx.fragmentNames())
              + "\n  写法:@fragment('row') ... @endfragment"));
    }

    @Override
    public boolean exists(String name) {
        if (repository.read(name).isPresent()) return true;
        // 预编译模式:模板源码可能没有打进 jar,但生成的 class 还在
        return !devMode && jte.hasTemplate(toJtePath(name));
    }

    @Override
    public void invalidate() {
        resolver.invalidate();
        // jte 的 DirectoryCodeResolver 靠 getLastModified 判断,无需显式清理
    }

    /**
     * jte 在**自包含 jar** 里不支持运行时编译,只能用预编译的模板类。
     * 它的原始报错是一串 jte 文档链接,对 Jaravel 用户毫无上下文 ——
     * 换成说明「这是什么、为什么、怎么办」的消息。
     */
    private static boolean isPrecompileRequired(TemplateException e) {
        String m = e.getMessage();
        return m != null && m.contains("must be precompiled");
    }

    private JbladeException precompileRequired(String viewName, TemplateException cause) {
        return new JbladeException(viewName + SUFFIX, 0,
                "打包成单体 jar 后无法在运行时编译模板,必须预编译。",
                null,
                """
                开发期(mvn spring-boot:run / IDE 运行)不受影响,模板改完即生效。
                       打包部署需要模板预编译,该能力由 jaravel-maven-plugin 提供(排期 v0.9)。
                       v0.1 的部署方式:用展开的 classpath 运行,例如
                         mvn dependency:copy-dependencies
                         java -cp "target/classes;target/dependency/*" app.Application\
                """,
                cause);
    }

    // ================= 错误映射 =================

    /**
     * 把 jte 的错误翻译成指向 {@code .jblade.html} 的错误。
     *
     * <p>这是整个模块的头号质量要求。jte 已经把 Java 错误映射到 {@code .jte} 行号
     * (V3 实测:{@code error at errtest/typo.jte:6}),本方法完成最后一跳。
     */
    private JbladeException translateError(TemplateException e, String viewName) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = message + "\n" + e.getCause().getMessage();
        }

        Matcher m = JTE_LOCATION.matcher(message);
        String jtePath = null;
        int jteLine = 0;
        if (m.find()) {
            jtePath = m.group(1);
            jteLine = Integer.parseInt(m.group(2));
        }

        // 出错的可能是被 @include 的子模板,而非入口模板
        String failingView = jtePath != null ? fromJtePath(jtePath) : viewName;
        LineMap map = resolver.lineMap(failingView);
        int sourceLine = (map != null && jteLine > 0) ? map.toSourceLine(jteLine) : 0;

        String source = repository.read(failingView).orElse(null);
        String snippet = source != null ? JbladeException.renderSnippet(source, sourceLine, 2) : null;

        String cleaned = cleanJteMessage(message);
        String suggestion = JbladeException
                .suggestionFor(message, candidateNames(failingView, source, sourceLine))
                .orElse(null);

        String missingClass = missingGeneratedClass(e, message);
        if (missingClass != null) {
            // 这条消息原本只有一个类名(gg/jte/generated/ondemand/…$1),
            // 与「模板哪里写错了」毫无关系 —— 它是模板类文件被另一个引擎
            // 覆盖后,惰性加载内部类时炸的。见 sharedForDirectory 的注释
            cleaned = """
                    模板的生成类加载失败:%s

                      这不是模板的语法问题。生成类的**内部类**(@fragment 与组件
                      slot 会生成匿名 Content 子类)是惰性加载的,而它所在的
                      .class 文件被重新编译覆盖过。
                      常见原因:一个 JVM 里有两个视图引擎共用同一个
                      view.compiled 目录(两个 Spring 上下文各建了一个)。
                      修复:走 JbladeEngine.sharedForDirectory(框架默认已经这样做),
                      或给两个上下文配不同的 view.compiled;
                      手工恢复:删掉 %s 重跑。"""
                    .formatted(missingClass, "target/jblade-classes");
            suggestion = null;
        }

        return new JbladeException(failingView + SUFFIX, sourceLine, cleaned, snippet, suggestion, e);
    }

    /**
     * 这个异常是「生成类(或它的内部类)加载不到」吗 —— 返回那个类名。
     *
     * <p>{@code NoClassDefFoundError} 的 message 就是一个斜杠形式的类名,
     * 没有任何别的信息。不认出来的话,用户看到的错误页标题是 JbladeException、
     * 正文是一行类名,而真正该做的事(清掉编译目录 / 共享引擎)无从得知。
     */
    // 包可见:这条判据只在 Linux 上出过问题(路径分隔符不同),
    // 所以它必须能被直接测,而不是只能通过一次真实的渲染失败观察到
    static String missingGeneratedClass(Throwable error, String assembledMessage) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (!(t instanceof NoClassDefFoundError) && !(t instanceof ClassNotFoundException)) {
                continue;
            }
            String message = t.getMessage();
            if (message != null && message.contains("gg.jte.generated")
                    || message != null && message.contains("gg/jte/generated")) {
                return message;
            }
        }

        // 异常类型被包掉时退一步看消息本身 —— 但只认匿名内部类的名字,
        // 不认带 .java 的编译错误(见 MISSING_GENERATED 的注释)
        if (assembledMessage != null && !assembledMessage.contains("Generated.java")) {
            var matcher = MISSING_GENERATED.matcher(assembledMessage);
            if (matcher.find()) return matcher.group();
        }
        return null;
    }

    /**
     * 去掉 jte 的内部细节:生成的 Java 文件路径、jte 行号。
     * 用户不该看到 {@code JtetypoGenerated.java:15}。
     */
    static String cleanJteMessage(String message) {
        List<String> keep = new ArrayList<>();
        for (String line : message.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("Failed to compile") || t.startsWith("Failed to render")) continue;
            if (t.contains("jte-classes") || t.contains("Generated.java")) continue;
            if (t.startsWith("gg.jte.")) continue;
            // 生成的 Java 代码行不该出现在用户看到的消息里
            if (t.startsWith("jteOutput.") || t.startsWith("jteHtmlInterceptor.")) continue;
            if (t.equals("^") || t.matches("\\^+")) continue;
            if (t.startsWith("public static void render(")) continue;
            keep.add(t);
        }
        return keep.isEmpty() ? message.strip() : String.join("\n  ", keep);
    }

    /**
     * 「是否想写 X ?」的候选名来源。
     *
     * <p><b>关键:</b>候选**不能**只从模板源码里取 —— 模板里只有拼错的那个名字,
     * 正确名字根本不在里面。必须反射 {@code @params} 声明的类型,取其 public
     * 字段与方法名。这样 {@code post.titel} 才能被建议成 {@code title}。
     *
     * <p>泛型参数也要展开:{@code List<Post> posts} 里真正被访问的是 {@code Post} 的成员。
     */
    private List<String> candidateNames(String viewName, String source, int line) {
        var out = new java.util.LinkedHashSet<String>();

        for (String decl : resolver.declaredParams(viewName)) {
            for (String typeName : classLikeTokens(decl)) {
                Class<?> type = tryLoad(typeName);
                if (type == null) continue;
                for (var f : type.getFields()) out.add(f.getName());
                for (var m : type.getMethods()) {
                    if (m.getDeclaringClass() != Object.class) out.add(m.getName());
                }
            }
        }

        out.addAll(identifiersIn(source, line));
        return List.copyOf(out);
    }

    /** 从 {@code java.util.List<com.app.Post> posts} 中取出 List 与 Post 两个类名 */
    static List<String> classLikeTokens(String declaration) {
        var out = new java.util.ArrayList<String>();
        for (String token : declaration.split("[<>,\\s\\[\\]]+")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            // 类名以大写字母开头,或是全限定名
            if (Character.isUpperCase(t.charAt(0)) || t.contains(".")) out.add(t);
        }
        return out;
    }

    private Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, typeLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            // 简单名(未全限定)无法加载,正常情况,跳过
            return null;
        }
    }

    /** 从出错行附近提取标识符,作为补充候选 */
    private static List<String> identifiersIn(String source, int line) {
        if (source == null || line <= 0) return List.of();
        String[] lines = source.split("\\R", -1);
        var out = new ArrayList<String>();
        int from = Math.max(0, line - 3), to = Math.min(lines.length, line + 2);
        var p = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
        for (int i = from; i < to; i++) {
            var mm = p.matcher(lines[i]);
            while (mm.find()) out.add(mm.group());
        }
        return out.stream().distinct().toList();
    }

    // ================= 名称转换 =================

    /** {@code posts.index} → {@code posts/index.jte} */
    static String toJtePath(String viewName) {
        return viewName.replace('.', '/') + ".jte";
    }

    /** {@code posts/index.jte} → {@code posts.index} */
    static String fromJtePath(String jtePath) {
        String p = jtePath.endsWith(".jte") ? jtePath.substring(0, jtePath.length() - 4) : jtePath;
        return p.replace('/', '.').replace('\\', '.');
    }

    /**
     * 扫出一个组件模板声明的 prop 名字。
     *
     * <p>{@code Optional.empty()} = 这个模板**不是组件**(没有 {@code @props}),
     * 于是调用方的属性全当声明过的参数 —— 属性袋不介入。
     *
     * <p>只看 {@code @props} 开头的那一行,不做完整转写:这是个高频查询
     * (每个带组件的模板转写时,每个组件一次)。
     */
    static Optional<List<String>> declaredPropNames(String componentSource) {
        var names = new ArrayList<String>();
        boolean declaredAnything = false;

        // ★ @aware 的名字也算「声明过的 prop」。
        //
        // 这个列表决定调用方那边的分流:声明过的进参数,没声明的进属性袋。
        // 漏掉 @aware 的后果有两个,都很隐蔽:显式传的
        // <x-item color="red"> 会进属性袋(于是 @aware 读不到它 ——
        // 「我明明传了却不生效」),而且它会被 {{ attributes }} 原样打进
        // HTML,而 color 不是合法的 HTML 属性。
        for (String line : componentSource.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("@props") && !trimmed.startsWith("@aware")) continue;

            declaredAnything = true;
            int open = trimmed.indexOf('(');
            int close = trimmed.lastIndexOf(')');
            if (open < 0 || close < open) continue;

            for (String declaration : JbladeTranspiler.splitArgs(
                    trimmed.substring(open + 1, close))) {
                String name = propName(declaration);
                if (name != null) names.add(name);
            }
        }
        return declaredAnything ? Optional.of(List.copyOf(names)) : Optional.empty();
    }

    /** {@code String type = "info"} → {@code type} */
    private static String propName(String declaration) {
        String d = declaration.trim();
        int eq = d.indexOf('=');
        if (eq >= 0) d = d.substring(0, eq).trim();
        int space = d.lastIndexOf(' ');
        if (space < 0) return null;   // 没有类型 —— @props 的报错会讲这件事
        String name = d.substring(space + 1).trim();
        return name.isEmpty() ? null : name;
    }

    // ================= 模板来源 =================

    /** 模板源码的来源抽象:目录(开发)/ classpath(生产)/ Map(测试) */
    public interface TemplateRepository {
        Optional<String> read(String viewName);
        long lastModified(String viewName);
        List<String> allNames();
    }

    /**
     * 开发模式的仓库:先看应用的 views 目录,**再回落到 classpath**。
     *
     * <h2>★ 为什么必须有 classpath 回落</h2>
     * <p>框架自己会带视图 —— 邮件组件({@code components/mail/message})
     * 就是第一批,将来的分页视图、错误页也是。
     *
     * <p>没有回落的话那些视图在**打包后能用、开发模式下找不到** ——
     * 一个「本地跑不了但线上正常」的失败,而它的症状(模板找不到)
     * 与原因(仓库只看应用目录)之间没有线索。
     *
     * <p>这个项目修过很多次它的镜像版本(「本地好的,打包后全挂」),
     * 两个方向同样糟。
     *
     * <h2>顺序:应用目录优先</h2>
     * <p>于是**应用可以覆盖框架自带的视图** —— 在自己的
     * {@code views/components/mail/message.jblade.html} 放一份就生效,
     * 不需要任何配置。那正是 Laravel 的 {@code vendor:publish} 想达到
     * 的效果,而这里是零命令的。
     *
     * <p>⚠ 代价:应用里一个**打错名字**的模板不会报「找不到」,
     * 而是静默用了框架的那一份。这是优先级顺序的固有结果 ——
     * 反过来(classpath 优先)会让开发期的编辑失效,那更糟。
     */
    static final class DirectoryRepository implements TemplateRepository {
        private final Path root;
        private final ClasspathRepository fallback;

        DirectoryRepository(Path root) {
            this(root, null);
        }

        DirectoryRepository(Path root, ClassLoader loader) {
            this.root = root;
            this.fallback = new ClasspathRepository("views", loader);
        }

        private Path pathOf(String viewName) {
            return root.resolve(viewName.replace('.', '/') + SUFFIX);
        }

        @Override public Optional<String> read(String viewName) {
            Path p = pathOf(viewName);
            if (!Files.isRegularFile(p)) return fallback.read(viewName);
            try {
                return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new JbladeException(viewName + SUFFIX, 0, "读取模板失败:" + e.getMessage());
            }
        }

        /**
         * 不存在时**委托回落** —— 必须与 {@link #read} 走同一个来源。
         *
         * <p>若这里返回 0 而 read 却从 classpath 拿到了内容,jte 会看到
         * 一个「时间戳一直是 0」的模板。那恰好不会出错(0 是个常量,
         * 和 classpath 的 1L 一样稳定),但两个方法指向不同的来源是
         * 个等着被人踩的陷阱。
         */
        @Override public long lastModified(String viewName) {
            Path p = pathOf(viewName);
            try {
                if (!Files.isRegularFile(p)) return fallback.lastModified(viewName);
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }

        @Override public List<String> allNames() {
            var names = new java.util.LinkedHashSet<String>();
            if (Files.isDirectory(root)) {
                try (var s = Files.walk(root)) {
                    s.filter(Files::isRegularFile)
                            .map(root::relativize)
                            .forEach(p -> addTemplateName(names, p.toString()));
                } catch (IOException e) {
                    // 回落仍然可用
                }
            }
            // 依赖里的模板(邮件组件、dashboard)也要一起预编译:
            // 它们和应用的模板进同一个 jte 引擎,漏一个就会在运行期报找不到类。
            names.addAll(fallback.allNames());
            return List.copyOf(names);
        }
    }

    static final class ClasspathRepository implements TemplateRepository {
        private final String prefix;
        private final ClassLoader loader;

        ClasspathRepository(String prefix, ClassLoader loader) {
            this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
            this.loader = loader == null ? ClasspathRepository.class.getClassLoader() : loader;
        }

        @Override public Optional<String> read(String viewName) {
            String resource = prefix + viewName.replace('.', '/') + SUFFIX;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) return Optional.empty();
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new JbladeException(viewName + SUFFIX, 0, "读取模板失败:" + e.getMessage());
            }
        }

        /** classpath 资源不会变,返回常量让 jte 一直用缓存 */
        @Override public long lastModified(String viewName) { return 1L; }

        /**
         * 扫出 classpath 上所有 {@code views/} 下的模板。
         *
         * <p>预编译插件要连依赖里的模板一起编(邮件组件、dashboard);
         * 运行时不靠它 —— 预编译模式直接用生成的类。
         */
        @Override public List<String> allNames() {
            var names = new java.util.LinkedHashSet<String>();
            try {
                var urls = loader.getResources(prefix);
                while (urls.hasMoreElements()) {
                    java.net.URL url = urls.nextElement();
                    if ("file".equals(url.getProtocol())) {
                        Path dir = Path.of(url.toURI());
                        try (var s = Files.walk(dir)) {
                            s.filter(Files::isRegularFile).forEach(p ->
                                    addTemplateName(names, dir.relativize(p).toString()));
                        }
                    } else if ("jar".equals(url.getProtocol())) {
                        var conn = (java.net.JarURLConnection) url.openConnection();
                        try (var jar = conn.getJarFile()) {
                            String entry = conn.getEntryName();
                            if (entry == null) entry = prefix;
                            String p = entry.endsWith("/") ? entry : entry + "/";
                            jar.stream()
                                    .filter(e -> !e.isDirectory() && e.getName().startsWith(p))
                                    .forEach(e -> addTemplateName(
                                            names, e.getName().substring(p.length())));
                        }
                    }
                }
            } catch (IOException | java.net.URISyntaxException | ClassCastException e) {
                // 扫依赖失败不阻塞应用自己的模板
            }
            return List.copyOf(names);
        }
    }

    private static void addTemplateName(java.util.Set<String> names, String relative) {
        String n = relative.replace('\\', '/');
        if (!n.endsWith(SUFFIX)) return;
        names.add(n.substring(0, n.length() - SUFFIX.length()).replace('/', '.'));
    }

    static final class MapRepository implements TemplateRepository {
        private final Map<String, String> templates;
        private final Map<String, Long> versions = new HashMap<>();

        MapRepository(Map<String, String> templates) {
            this.templates = new ConcurrentHashMap<>(templates);
            templates.keySet().forEach(k -> versions.put(k, 1L));
        }

        /** 测试里模拟「改模板」 */
        public void put(String viewName, String content) {
            templates.put(viewName, content);
            versions.merge(viewName, 1L, Long::sum);
        }

        @Override public Optional<String> read(String viewName) {
            return Optional.ofNullable(templates.get(viewName));
        }

        @Override public long lastModified(String viewName) {
            return versions.getOrDefault(viewName, 0L);
        }

        @Override public List<String> allNames() { return List.copyOf(templates.keySet()); }
    }

    public TemplateRepository repository() { return repository; }

    public boolean isDevMode() { return devMode; }

    /** 调试用:看转写出的 jte 源码({@code jaravel view:show} 的数据源) */
    public String transpiledSource(String viewName) {
        return resolver.transpiledSource(viewName);
    }
}
