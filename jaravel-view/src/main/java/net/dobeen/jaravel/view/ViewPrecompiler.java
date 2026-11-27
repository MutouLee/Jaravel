package net.dobeen.jaravel.view;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;

/**
 * 构建期把 {@code .jblade.html} 预编译成 class 文件(v0.9)。
 *
 * <p>运行时编译适合开发,不适合打包:sleek 的 classpath 里没有 javac、
 * 启动要等第一次渲染、自包含 jar 根本编不了。这里是
 * {@code jaravel-maven-plugin} 的实现核心,做成普通类是为了能在
 * 单元测试里直接跑,不必起 Maven。
 *
 * <p>两步:
 * <ol>
 *   <li>与开发模式**同一套** {@link TranspilingCodeResolver} 转写,保证两边不分叉;</li>
 *   <li>用 jte 的 {@code precompileAll(compilePath)} 生成并编译
 *       {@code gg.jte.generated.precompiled} 包下的类,直接写进
 *       {@code target/classes}。</li>
 * </ol>
 *
 * <p>同时写出 {@code META-INF/jaravel/jblade-metadata.properties}
 * (行号映射与 {@code @params}),让预编译后的运行期异常仍指向
 * {@code .jblade.html} 的正确行;并写一个 marker 供 starter 自动选择
 * 预编译引擎。
 */
public final class ViewPrecompiler {

    public static final String METADATA_RESOURCE =
            "META-INF/jaravel/jblade-metadata.properties";
    public static final String MARKER_RESOURCE =
            "META-INF/jaravel/jblade-precompiled";

    /** jte 报错里的位置,形如 {@code error at posts/index.jte:6} */
    private static final Pattern LOCATION = Pattern.compile(
            "(?:error at|Failed to compile template, error at)\\s+(\\S+?):(\\d+)");

    private ViewPrecompiler() {
    }

    public record Result(int templates, int classFiles, List<String> generated) {
    }

    /**
     * @param viewRoot        {@code src/main/resources/views}
     * @param outputDirectory 编译输出目录(通常是 {@code target/classes})
     * @param compilePath     编译期 classpath(项目类 + 依赖),供 jte 调 javac
     * @param parent          应用类加载器;类组件的构造函数靠它解析
     */
    public static Result precompile(Path viewRoot, Path outputDirectory,
                                    List<Path> compilePath, ClassLoader parent) {
        if (!Files.isDirectory(viewRoot)) {
            return new Result(0, 0, List.of());
        }

        try {
            Files.createDirectories(outputDirectory);
            deleteRecursively(outputDirectory.resolve("gg/jte/generated/precompiled"));
            Files.deleteIfExists(outputDirectory.resolve(METADATA_RESOURCE));
            Files.deleteIfExists(outputDirectory.resolve(MARKER_RESOURCE));

            var repository = new JbladeEngine.DirectoryRepository(viewRoot, parent);
            var resolver = new TranspilingCodeResolver(repository, parent);

            TemplateEngine engine = TemplateEngine.create(
                    resolver, outputDirectory, ContentType.Html, parent,
                    "gg.jte.generated.precompiled");

            List<String> generated = compilePath == null || compilePath.isEmpty()
                    ? engine.precompileAll()
                    : engine.precompileAll(compilePath.stream().map(Path::toString).toList());

            deleteGeneratedSources(outputDirectory, generated);

            int templates = resolver.lineMaps().size();
            if (templates > 0) {
                writeMetadata(outputDirectory, resolver);
                writeMarker(outputDirectory, resolver.resolveAllTemplateNames().size());
            }

            long classes = 0;
            Path generatedRoot = outputDirectory.resolve("gg/jte/generated/precompiled");
            if (Files.isDirectory(generatedRoot)) {
                try (var stream = Files.walk(generatedRoot)) {
                    classes = stream.filter(p -> p.toString().endsWith(".class")).count();
                }
            }
            return new Result(templates, (int) classes, generated);
        } catch (TemplateException e) {
            throw translate(e, viewRoot, parent);
        } catch (IOException e) {
            throw new JbladeException("views", 0,
                    "预编译模板失败:" + e.getMessage(), null, null, e);
        }
    }

    private static void deleteGeneratedSources(Path output, List<String> generated)
            throws IOException {
        for (String name : generated) {
            if (!name.endsWith(".java")) continue;
            Path file = output.resolve(name);
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            // 只删空目录,别碰到同级的 class 文件
            while (parent != null && parent.startsWith(output)
                    && !parent.equals(output) && isEmptyDir(parent)) {
                Files.delete(parent);
                parent = parent.getParent();
            }
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMetadata(Path output, TranspilingCodeResolver resolver)
            throws IOException {
        var props = new Properties();
        resolver.lineMaps().forEach((view, map) ->
                props.setProperty("lineMap." + view, joinInts(map.toArray())));
        resolver.declaredParamsMap().forEach((view, params) ->
                props.setProperty("params." + view, String.join(",", params)));

        Path file = output.resolve(METADATA_RESOURCE);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "jblade precompiled metadata; lineMap = jte line -> jblade line");
        }
    }

    private static String joinInts(int[] values) {
        var sb = new StringBuilder(values.length * 2);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static void writeMarker(Path output, int templates) throws IOException {
        Path marker = output.resolve(MARKER_RESOURCE);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker,
                "jaravel-jblade-precompiled=1\ntemplates=" + templates + "\n");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static JbladeException translate(TemplateException e, Path viewRoot,
                                             ClassLoader parent) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = message + "\n" + e.getCause().getMessage();
        }

        Matcher m = LOCATION.matcher(message);
        String failingView = null;
        int jteLine = 0;
        if (m.find()) {
            failingView = JbladeEngine.fromJtePath(m.group(1));
            jteLine = Integer.parseInt(m.group(2));
        }

        int sourceLine = 0;
        String source = null;
        if (failingView != null) {
            var resolver = new TranspilingCodeResolver(
                    new JbladeEngine.DirectoryRepository(viewRoot, parent), parent);
            // 出错模板可能还没被 resolve 过;重跑一次转写以拿到行映射
            resolver.resolve(JbladeEngine.toJtePath(failingView));
            LineMap map = resolver.lineMap(failingView);
            if (map != null) sourceLine = map.toSourceLine(jteLine);
            source = resolver.repository().read(failingView).orElse(null);
        }

        String snippet = source != null
                ? JbladeException.renderSnippet(source, sourceLine, 2) : null;
        String cleaned = JbladeEngine.cleanJteMessage(message);
        String file = failingView != null ? failingView + JbladeEngine.SUFFIX : "views";
        return new JbladeException(file, sourceLine, cleaned, snippet, null, e);
    }
}
