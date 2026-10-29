package io.jaravel.view;

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

import gg.jte.CodeResolver;
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

    /** jte 报错里的位置,形如 {@code error at posts/index.jte:6} */
    private static final Pattern JTE_LOCATION =
            Pattern.compile("(?:error at|Failed to compile template, error at)\\s+(\\S+?):(\\d+)");

    private final TemplateRepository repository;
    private final TemplateEngine jte;
    /** 模板名 → 行号映射。转写时写入,报错时查询。 */
    private final Map<String, LineMap> lineMaps = new ConcurrentHashMap<>();
    /** 模板名 → @params 声明。报错时用于反射出候选成员名,生成「是否想写 X ?」建议。 */
    private final Map<String, List<String>> declaredParams = new ConcurrentHashMap<>();
    private final ClassLoader typeLoader;
    private final boolean devMode;

    private JbladeEngine(TemplateRepository repository, Path classDirectory,
                         ClassLoader parent, boolean devMode) {
        this.repository = repository;
        this.devMode = devMode;
        this.typeLoader = parent != null ? parent : JbladeEngine.class.getClassLoader();
        this.jte = TemplateEngine.create(new TranspilingCodeResolver(), classDirectory,
                                         ContentType.Html, parent);
    }

    /** 开发模式:从目录读取,文件变更自动重编译 */
    public static JbladeEngine forDirectory(Path viewRoot, Path classDirectory, ClassLoader parent) {
        return new JbladeEngine(new DirectoryRepository(viewRoot), classDirectory, parent, true);
    }

    /** 生产模式:从 classpath 读取 */
    public static JbladeEngine forClasspath(String resourcePrefix, Path classDirectory, ClassLoader parent) {
        return new JbladeEngine(new ClasspathRepository(resourcePrefix, parent), classDirectory, parent, false);
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

    @Override
    public boolean exists(String name) {
        return repository.read(name).isPresent();
    }

    @Override
    public void invalidate() {
        lineMaps.clear();
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
        LineMap map = lineMaps.get(failingView);
        int sourceLine = (map != null && jteLine > 0) ? map.toSourceLine(jteLine) : 0;

        String source = repository.read(failingView).orElse(null);
        String snippet = source != null ? JbladeException.renderSnippet(source, sourceLine, 2) : null;

        String cleaned = cleanJteMessage(message);
        String suggestion = JbladeException
                .suggestionFor(message, candidateNames(failingView, source, sourceLine))
                .orElse(null);

        return new JbladeException(failingView + SUFFIX, sourceLine, cleaned, snippet, suggestion, e);
    }

    /**
     * 去掉 jte 的内部细节:生成的 Java 文件路径、jte 行号。
     * 用户不该看到 {@code JtetypoGenerated.java:15}。
     */
    private String cleanJteMessage(String message) {
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

        for (String decl : declaredParams.getOrDefault(viewName, List.of())) {
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

    // ================= jte 接入 =================

    /**
     * jte 向我们索要模板源码时,按需把 {@code .jblade.html} 转写成 jte 源码。
     * 不落盘,因此没有中间文件的同步问题。
     */
    private final class TranspilingCodeResolver implements CodeResolver {

        @Override
        public String resolve(String jtePath) {
            String viewName = fromJtePath(jtePath);
            String blade = repository.read(viewName).orElse(null);
            if (blade == null) return null;

            var result = JbladeTranspiler.transpile(blade, viewName + SUFFIX);
            lineMaps.put(viewName, result.lineMap());
            declaredParams.put(viewName, result.declaredParams());
            return result.jteSource();
        }

        @Override
        public long getLastModified(String jtePath) {
            return repository.lastModified(fromJtePath(jtePath));
        }

        @Override
        public boolean exists(String jtePath) {
            return repository.read(fromJtePath(jtePath)).isPresent();
        }

        @Override
        public List<String> resolveAllTemplateNames() {
            return repository.allNames().stream().map(JbladeEngine::toJtePath).toList();
        }
    }

    // ================= 模板来源 =================

    /** 模板源码的来源抽象:目录(开发)/ classpath(生产)/ Map(测试) */
    public interface TemplateRepository {
        Optional<String> read(String viewName);
        long lastModified(String viewName);
        List<String> allNames();
    }

    static final class DirectoryRepository implements TemplateRepository {
        private final Path root;

        DirectoryRepository(Path root) { this.root = root; }

        private Path pathOf(String viewName) {
            return root.resolve(viewName.replace('.', '/') + SUFFIX);
        }

        @Override public Optional<String> read(String viewName) {
            Path p = pathOf(viewName);
            if (!Files.isRegularFile(p)) return Optional.empty();
            try {
                return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new JbladeException(viewName + SUFFIX, 0, "读取模板失败:" + e.getMessage());
            }
        }

        @Override public long lastModified(String viewName) {
            Path p = pathOf(viewName);
            try {
                return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            } catch (IOException e) {
                return 0L;
            }
        }

        @Override public List<String> allNames() {
            if (!Files.isDirectory(root)) return List.of();
            try (var s = Files.walk(root)) {
                return s.filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(p -> p.toString().replace('\\', '/'))
                        .filter(n -> n.endsWith(SUFFIX))
                        .map(n -> n.substring(0, n.length() - SUFFIX.length()).replace('/', '.'))
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
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

        @Override public List<String> allNames() { return List.of(); }
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
        String blade = repository.read(viewName)
                .orElseThrow(() -> new JbladeException(viewName + SUFFIX, 0, "模板不存在"));
        return JbladeTranspiler.transpile(blade, viewName + SUFFIX).jteSource();
    }
}
