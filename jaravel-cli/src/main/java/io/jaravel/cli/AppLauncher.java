package io.jaravel.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.jaravel.console.CommandLine;
import io.jaravel.console.ConsoleIo;

/**
 * 冷启动一个应用 JVM 执行在线命令(docs/13 §1 的第二条路径)。
 *
 * <pre>
 * jaravel migrate          应用没跑 → 这里起一个 JVM,跑完退出
 * </pre>
 *
 * <h2>★ 它是**兜底**,不是主路径</h2>
 * <p>快的那条是命令通道:应用跑着时命令在那个已经热起来的进程里执行,
 * 0 启动开销。这里每次都要付一次完整的应用启动(本机 blog 示例约 1.5 秒)。
 *
 * <p>但它不可省:CI 里、部署脚本里、刚 clone 下来的机器上,
 * 根本没有一个跑着的应用。没有这条路的话 {@code jaravel migrate}
 * 在那些地方只能打印一句「请先把应用跑起来」—— 而那正是
 * 自动化流程里最没用的建议。
 *
 * <h2>怎么找到要启动什么:三件事,都要缓存</h2>
 * <ol>
 *   <li><b>classpath</b> —— {@code mvn dependency:build-classpath} 要 2.7 秒
 *       (实测),比应用启动本身还慢。所以结果缓存在
 *       {@code .jaravel/classpath},{@code pom.xml} 变了才重算。</li>
 *   <li><b>主类</b> —— 扫 {@code target/classes} 找带 {@code main} 的类,
 *       缓存在 {@code .jaravel/main-class}。那个文件也是**覆盖入口**:
 *       猜错了就自己写一行进去。</li>
 *   <li><b>编译</b> —— 见下。</li>
 * </ol>
 *
 * <h2>★ 源码比 class 新的时候必须先编译</h2>
 * <p>这是 Java 这条路独有的陷阱,Laravel 没有:直接拿
 * {@code target/classes} 跑的话,用户改完迁移、重跑 {@code jaravel migrate},
 * **跑的还是旧代码** —— 而输出看起来完全正常。那种失败会让人去怀疑
 * 迁移器、怀疑数据库,查不到「你跑的是上一次编译的结果」。
 *
 * <p>所以比一次时间戳:源码更新就先 {@code mvn compile}(并且说出来),
 * 否则跳过 Maven 直接起 JVM。正确与快各占一半。
 */
final class AppLauncher {

    private AppLauncher() {
    }

    /** 缓存与通道共用 {@code .jaravel/} */
    private static final String CACHE_DIR = io.jaravel.console.channel.CommandChannel.DIRECTORY;

    /**
     * 启动一个应用 JVM 跑这条命令。
     *
     * @return 退出码;判断出「这条路走不了」时返回 empty,
     *         由调用方去打那条说明(它比这里更清楚上下文)
     */
    static Optional<Integer> tryRun(Path root, String command, List<String> args, ConsoleIo io) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            // 不是 Maven 项目:可能是 Gradle,也可能只是敲错了目录。
            // 两种情况都不该由这里猜 —— 调用方那条说明会把路指出来
            return Optional.empty();
        }
        Path classes = root.resolve("target").resolve("classes");
        try {
            if (isAggregator(pom)) {
                // ★ 聚合 pom(带 <modules>)不是一个应用。
                //
                // 不挡住的话这里会对**整个 reactor** 跑 mvn compile ——
                // 在本框架自己的仓库里那是四十个模块、好几分钟,
                // 而触发它的只是一个在根目录里敲的命令。
                // 而且就算编完也没用:哪个模块才是应用,这里无从得知。
                io.writeError(("[jaravel] %s 是一个聚合 pom(有 <modules>)——"
                        + "它不是一个应用。%n"
                        + "          到那个应用模块的目录里再敲。%n")
                        .formatted(root.getFileName()));
                return Optional.of(1);
            }

            if (!ensureCompiled(root, classes, io)) return Optional.of(1);

            String mainClass = mainClass(root, classes, io);
            if (mainClass == null) return Optional.of(1);

            String classpath = classpath(root, classes, io);
            if (classpath == null) return Optional.of(1);

            return Optional.of(spawn(root, mainClass, classpath, command, args, io));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(130);   // 128 + SIGINT,shell 的约定
        }
    }

    /**
     * 这个 pom 是聚合器吗。
     *
     * <p>按文本找 {@code <modules>} 而不是解析 XML:引一个 XML 库进
     * {@code jaravel-cli} 要付 classpath(而 CLI 的卖点就是启动快),
     * 而这个判断只要一个布尔值。
     *
     * <p>误判的方向是安全的:一个注释里提到 {@code <modules>} 的
     * 单模块 pom 会被当成聚合器 —— 那时用户看到的是一句明确的说明
     * (而不是一次几分钟的构建),而且 {@code .jaravel/main-class}
     * 那条路仍然可用。
     */
    private static boolean isAggregator(Path pom) throws IOException {
        return Files.readString(pom).contains("<modules>");
    }

    // ================= 编译 =================

    private static boolean ensureCompiled(Path root, Path classes, ConsoleIo io)
            throws IOException, InterruptedException {
        boolean missing = !Files.isDirectory(classes);
        if (!missing && !sourcesAreNewerThanClasses(root, classes)) return true;

        io.writeError(missing
                ? "[jaravel] target/classes 还不存在 —— 先编译。\n"
                : "[jaravel] 源码比 target/classes 新 —— 先编译,"
                        + "否则跑的会是上一次编译的结果。\n");

        int code = run(root, maven(root, "-q", "compile"), io);
        if (code != 0) {
            io.writeError("[jaravel] 编译失败 —— 命令没有执行。\n");
        }
        return code == 0;
    }

    /**
     * 比**最新的** .java 与**最新的** .class。
     *
     * <p>不逐个文件配对:配对要还原包路径、要处理内部类
     * ({@code Outer$Inner.class})、还要处理被删掉的源文件 ——
     * 而「最新 vs 最新」用十行代码给出同样的结论:
     * 只要有一个源文件比所有 class 都新,就得重编。
     *
     * <p>误判的方向是安全的:多编一次(几秒),而不是跑旧代码。
     */
    static boolean sourcesAreNewerThanClasses(Path root, Path classes) throws IOException {
        long newestSource = newestModified(root.resolve("src").resolve("main"), ".java");
        long newestClass = newestModified(classes, ".class");
        return newestSource > newestClass;
    }

    private static long newestModified(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .max().orElse(0L);
        }
    }

    // ================= 主类 =================

    private static String mainClass(Path root, Path classes, ConsoleIo io) throws IOException {
        Path cache = root.resolve(CACHE_DIR).resolve("main-class");
        if (Files.isRegularFile(cache)) {
            String cached = Files.readString(cache).trim();
            if (!cached.isBlank()) return cached;
        }

        String found = discoverMainClass(classes);
        if (found == null) {
            io.writeError("""
                    [jaravel] 在 target/classes 下找不到带 main 方法的类 ——
                              不知道该启动什么。
                              出路:把主类名写进 %s/main-class,例如
                                echo app.Application > %s/main-class
                    """.formatted(CACHE_DIR, CACHE_DIR));
            return null;
        }
        write(cache, found);
        return found;
    }

    /**
     * 扫 class 文件找 {@code main}。
     *
     * <h2>★ 不加载类来判断</h2>
     * <p>反射地 {@code getMethod("main", …)} 要先**加载**那个类,
     * 而加载会跑它的静态初始化 —— 一个 {@code static { connect(); }}
     * 就会在「只是想知道主类是谁」的阶段连数据库。
     * 所以改为在 class 文件的字节里找常量池里那两个字符串。
     *
     * <p>可能误判(一个**调用**别人 main 的类也含这两个常量),
     * 所以带 {@code Application} 后缀的优先、路径浅的优先 ——
     * Spring Boot 项目的入口几乎总是
     * {@code app/Application.class}。猜错了有缓存文件可以覆盖。
     */
    static String discoverMainClass(Path classes) throws IOException {
        if (!Files.isDirectory(classes)) return null;
        try (Stream<Path> files = Files.walk(classes)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .sorted(Comparator
                            .comparing((Path p) -> p.getFileName().toString().endsWith("Application.class") ? 0 : 1)
                            .thenComparing(Path::getNameCount)
                            .thenComparing(Path::toString))
                    .filter(AppLauncher::declaresMain)
                    .findFirst()
                    .map(p -> classes.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replace('/', '.')
                            .replaceAll("\\.class$", ""))
                    .orElse(null);
        }
    }

    private static boolean declaresMain(Path classFile) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            return contains(bytes, utf8Entry("main"))
                    && contains(bytes, utf8Entry("([Ljava/lang/String;)V"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 常量池里一条 {@code CONSTANT_Utf8}:{@code tag(1) + u2 长度 + 内容}。
     *
     * <h2>★ 为什么要带上 tag 和长度</h2>
     * <p>第一版只在字节里找 {@code "main"} 这四个字符 —— 结果
     * **任何**含 {@code mainClass} / {@code remaining} / {@code domain}
     * 的类都算「有 main 方法」(测试当场抓住:它把一个工具类当成了入口)。
     * 带上长度之后 {@code 01 00 04 main} 不会匹配 {@code 01 00 09 mainClass}。
     *
     * <p>残留的误判只剩一种:一个**调用**别人 {@code main} 的类 ——
     * 它的常量池里也有这两条(方法引用要用到)。所以
     * {@code *Application} 优先、并且有 {@code .jaravel/main-class} 可以覆盖。
     * 要彻底排除就得真的解析方法表,而那是六十行 class 文件解析 ——
     * 换来的是「极少数项目不用手写一行覆盖文件」。
     */
    private static byte[] utf8Entry(String text) {
        byte[] content = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] entry = new byte[content.length + 3];
        entry[0] = 1;                                      // CONSTANT_Utf8
        entry[1] = (byte) (content.length >>> 8);
        entry[2] = (byte) content.length;
        java.lang.System.arraycopy(content, 0, entry, 3, content.length);
        return entry;
    }

    private static boolean contains(byte[] haystack, byte[] bytes) {
        outer:
        for (int i = 0; i <= haystack.length - bytes.length; i++) {
            for (int j = 0; j < bytes.length; j++) {
                if (haystack[i + j] != bytes[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ================= classpath =================

    private static String classpath(Path root, Path classes, ConsoleIo io)
            throws IOException, InterruptedException {
        Path cache = root.resolve(CACHE_DIR).resolve("classpath");
        Path pom = root.resolve("pom.xml");

        boolean fresh = Files.isRegularFile(cache)
                && Files.getLastModifiedTime(cache).toMillis()
                        >= Files.getLastModifiedTime(pom).toMillis();

        if (!fresh) {
            // 说一句:这一步要两三秒,而沉默的两三秒看起来像卡住了
            io.writeError("[jaravel] 解析依赖(pom.xml 变了;结果会缓存在 "
                    + CACHE_DIR + "/classpath)…\n");
            Files.createDirectories(cache.getParent());
            int code = run(root, maven(root, "-q", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cache.toAbsolutePath()), io);
            if (code != 0 || !Files.isRegularFile(cache)) {
                io.writeError("""
                        [jaravel] 解析依赖失败 —— 没法知道用什么 classpath 启动应用。
                                  mvn 不在 PATH 上的话,可以改用:
                                    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djaravel.command=…"
                        """);
                return null;
            }
        }

        // target/classes 放在**最前面**:它才是应用自己的代码,
        // 而依赖里可能有同名类(比如上一次 install 进本地仓库的自己)
        return classes.toAbsolutePath() + java.io.File.pathSeparator
                + Files.readString(cache).trim();
    }

    // ================= 启动 =================

    private static int spawn(Path root, String mainClass, String classpath,
                             String command, List<String> args, ConsoleIo io)
            throws IOException, InterruptedException {
        var argv = new ArrayList<String>();
        argv.add(javaBinary());
        argv.add("-cp");
        argv.add(classpath);

        // ★ 把**我们自己**的输出编码透传给子进程。
        //
        // JDK 18 起 System.out 跟的是操作系统控制台的编码。Windows 上
        // 那常常是 cp936,而 Git Bash / Windows Terminal 实际是 UTF-8 ——
        // 于是子进程打出来的中文是一串问号(实测:「没有待执行的迁移」)。
        //
        // 但**不能无条件写 UTF-8**:真的 cmd.exe 里 cp936 才是对的,
        // 强塞 UTF-8 会把乱码换个方向。所以透传:子进程的输出就是
        // 这个进程的输出,两边用同一个编码,
        // 用户改了(-Dstdout.encoding=… 或将来的 jaravelw)两边一起改。
        String encoding = System.getProperty("stdout.encoding",
                System.getProperty("native.encoding", "UTF-8"));
        argv.add("-Dstdout.encoding=" + encoding);
        argv.add("-Dstderr.encoding=" + encoding);

        // 这个进程是为一条命令起的,不该开端口 —— 否则 dev server 跑着时
        // 它会撞端口,而那个报错与用户敲的命令毫无关系
        argv.add("-Dspring.main.web-application-type=none");
        argv.add("-Dspring.main.banner-mode=off");
        // 启动日志会夹在命令输出前面。WARN 仍然可见,
        // 而命令自己的输出走 stdout、不走日志框架
        argv.add("-Dlogging.level.root=WARN");

        argv.add("-D" + CommandLine.COMMAND_PROPERTY + "="
                + CommandLine.join(prepend(command, args)));

        // 用户可以追加/覆盖 JVM 参数 —— 后面的 -D 覆盖前面的,
        // 所以上面那几条都是「默认」而不是「强制」
        for (String extra : CommandLine.split(System.getenv("JARAVEL_JVM_ARGS"))) {
            argv.add(extra);
        }

        argv.add(mainClass);

        Process app = new ProcessBuilder(argv)
                .directory(root.toFile())
                // ★ 继承 IO,不是读管道:
                // ① 输出要**边跑边出**(migrate 一条一条打、queue:work 一直打);
                // ② tinker 这类要读键盘的命令靠它拿到真终端。
                .inheritIO()
                .start();

        // Ctrl-C 之后不能留一个孤儿 JVM 在后台跑着(queue:work 会一直跑)。
        // 同一个进程组里子进程通常也收到 SIGINT,但那依赖于
        // shell 与平台 —— 显式杀一次更可靠
        Thread killer = new Thread(app::destroy, "jaravel-launcher-cleanup");
        Runtime.getRuntime().addShutdownHook(killer);
        try {
            return app.waitFor();
        } finally {
            Runtime.getRuntime().removeShutdownHook(killer);
        }
    }

    private static List<String> prepend(String command, List<String> args) {
        var all = new ArrayList<String>(args.size() + 1);
        all.add(command);
        all.addAll(args);
        return all;
    }

    /**
     * 用**当前这个** JVM 的 java,而不是 PATH 上的。
     *
     * <p>PATH 上那个可能是另一个版本(机器上装着 8 和 21 很常见),
     * 而症状会是「UnsupportedClassVersionError」—— 那条报错不会
     * 让任何人想到「CLI 和应用用的不是同一个 java」。
     */
    private static String javaBinary() {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    /** 项目自带的 wrapper 优先 —— 它钉住了版本,而 PATH 上那个没有 */
    private static List<String> maven(Path root, String... args) {
        String wrapper = isWindows() ? "mvnw.cmd" : "mvnw";
        var argv = new ArrayList<String>();
        argv.add(Files.isRegularFile(root.resolve(wrapper))
                ? root.resolve(wrapper).toAbsolutePath().toString()
                : (isWindows() ? "mvn.cmd" : "mvn"));
        argv.add("-o");   // 离线:启动器不该因为仓库不通而卡住
        argv.addAll(List.of(args));
        return argv;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static int run(Path root, List<String> argv, ConsoleIo io)
            throws IOException, InterruptedException {
        try {
            return new ProcessBuilder(argv).directory(root.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            // 「找不到 mvn」要说清是找不到**什么**:
            // ProcessBuilder 的原话是 "CreateProcess error=2",它什么也没说
            io.writeError("[jaravel] 跑不起来 " + argv.get(0) + ":" + e.getMessage() + "\n");
            return 127;   // shell 的「命令找不到」
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content + System.lineSeparator());
    }
}
