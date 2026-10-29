package io.jaravel.devtools;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 变更路径 → {@link ReloadStrategy}(docs/15 §3.2)。
 *
 * <p>纯函数,不碰文件系统 —— 这样它可以被详尽地测试,
 * 而「分类错了」的代价很高:把结构性变更误判成资源替换,
 * 用户会看到「代码改了但没生效」,然后怀疑整个热重载。
 */
public final class ChangeClassifier {

    /** 与运行时无关的路径片段 */
    private static final List<String> IGNORED_SEGMENTS = List.of(
            "/.git/", "/.idea/", "/.vscode/", "/node_modules/",
            "/storage/logs/", "/storage/framework/sessions/");

    /** 编辑器与系统的临时文件 */
    private static final List<String> IGNORED_PATTERNS = List.of(
            "~", ".swp", ".swx", ".tmp", ".DS_Store", ".orig", ".rej");

    /** 热替换即可生效的资源 */
    private static final Set<String> RESOURCE_SUFFIXES = Set.of(
            ".jblade.html", ".html", ".css", ".js", ".svg", ".png", ".jpg", ".ico",
            ".yml", ".yaml", ".properties", ".json", ".txt", ".md");

    /**
     * 改了这些注解的源文件一定要重启类加载器。
     *
     * <p>它们都会触发注解处理:{@code @Model} 生成父类与元模型,
     * {@code @Policy} 决定授权注册,{@code @AuthModel} 决定列名映射。
     * 这些生成物在类加载时才被读取,HotSwap 替换不了。
     */
    private static final List<String> APT_TRIGGERS = List.of(
            "@Model", "@AuthModel", "@Policy", "@Command",
            "@Fillable", "@Hidden", "@Cast", "@Scope",
            "@HasMany", "@HasOne", "@BelongsTo", "@BelongsToMany", "@MorphTo");

    private final boolean hotswapAvailable;

    public ChangeClassifier() {
        this(false);
    }

    /**
     * @param hotswapAvailable L2 是否可用。不可用时方法体修改也走
     *                         {@link ReloadStrategy#RESTART_LOADER} ——
     *                         这是**兜底保证**,见 docs/15 §3.4
     */
    public ChangeClassifier(boolean hotswapAvailable) {
        this.hotswapAvailable = hotswapAvailable;
    }

    /** 一批变更的整体策略 = 其中成本最高的那个 */
    public ReloadStrategy classify(Collection<Path> changed) {
        ReloadStrategy strategy = ReloadStrategy.IGNORE;
        for (Path path : changed) {
            strategy = strategy.escalate(classifyOne(path));
        }
        return strategy;
    }

    public ReloadStrategy classifyOne(Path path) {
        String normalized = normalize(path);

        if (isIgnored(normalized)) return ReloadStrategy.IGNORE;

        // pom.xml:classpath 本身变了,没有更便宜的办法
        if (normalized.endsWith("/pom.xml")) {
            return ReloadStrategy.RESTART_JVM;
        }

        // .env 在 Spring 启动**之前**被读进系统属性(见 Jaravel.loadDotEnv),
        // 所以它不是「资源」—— 改了必须重启 JVM 才能生效。
        // 把它当资源处理会让用户改完没反应,再去怀疑 .env 的语法
        if (normalized.endsWith("/.env") || normalized.contains("/.env.")) {
            return ReloadStrategy.RESTART_JVM;
        }

        if (normalized.endsWith(".java")) {
            return ReloadStrategy.RESTART_LOADER;
        }

        // 已编译的 class(IDE 增量编译的产物)
        if (normalized.endsWith(".class")) {
            return hotswapAvailable
                    ? ReloadStrategy.HOTSWAP
                    : ReloadStrategy.RESTART_LOADER;
        }

        for (String suffix : RESOURCE_SUFFIXES) {
            if (normalized.endsWith(suffix)) return ReloadStrategy.RESOURCE;
        }

        // 不认识的扩展名:按资源处理。
        // 保守选择是 RESTART_LOADER,但那会让「随手存了个 .excalidraw
        // 设计稿」触发一次重启 —— 比起偶尔漏掉一个真资源,这更烦人
        return ReloadStrategy.RESOURCE;
    }

    /**
     * 源文件改动是否一定需要重跑注解处理。
     *
     * <p>用在 {@link ReloadStrategy#HOTSWAP} 可用时:即使只改了方法体,
     * 只要文件里有这些注解,也要走类加载器重启 —— 因为 HotSwap
     * 不会重跑 APT,生成的父类/元模型会与源码脱节。
     *
     * @param sourceText 源文件内容
     */
    public static boolean triggersAnnotationProcessing(String sourceText) {
        if (sourceText == null) return false;
        for (String trigger : APT_TRIGGERS) {
            if (sourceText.contains(trigger)) return true;
        }
        return false;
    }

    private static boolean isIgnored(String normalized) {
        for (String segment : IGNORED_SEGMENTS) {
            if (normalized.contains(segment)) return true;
        }
        for (String pattern : IGNORED_PATTERNS) {
            if (normalized.endsWith(pattern)) return true;
        }
        // 编辑器的原子保存会先写一个点开头的临时文件
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.startsWith(".#") || fileName.startsWith("#")
            || (fileName.startsWith(".") && fileName.indexOf('.', 1) < 0
                && !fileName.equals(".env"));
    }

    /**
     * 统一成正斜杠,并保证以 {@code /} 开头。
     *
     * <p>两件事都是必需的:
     * <ul>
     *   <li>反斜杠 → 正斜杠,让规则在 Windows 与 POSIX 上写一遍就够;</li>
     *   <li>补前导斜杠 —— 忽略规则写的是 {@code /.git/} 这种**带边界**
     *       的片段(不带边界的 {@code .git/} 会误伤
     *       {@code src/my.git/x}),而相对路径 {@code .git/index}
     *       匹配不上它。不补的话「忽略 .git」只在绝对路径下生效,
     *       症状是 IDE 里跑着没事、命令行跑着每次 git 操作都触发重载。</li>
     * </ul>
     */
    private static String normalize(Path path) {
        String slashed = path.toString().replace('\\', '/');
        return slashed.startsWith("/") ? slashed : "/" + slashed;
    }
}
