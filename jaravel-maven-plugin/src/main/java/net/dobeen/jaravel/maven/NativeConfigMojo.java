package net.dobeen.jaravel.maven;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * 生成 GraalVM {@code reflect-config.json}(v0.9)。
 *
 * <p>扫描编译产物与(可选的)依赖 jar,找出所有带
 * {@code @net.dobeen.jaravel.console.Command} 的类,为它们生成反射配置:
 * <ul>
 *   <li>{@code allDeclaredConstructors} —— {@code CommandRegistry.register(Class)}
 *       用无参构造建实例;</li>
 *   <li>{@code allDeclaredFields} —— {@code ArgumentBinder} / {@code Usage}
 *       靠反射读写 {@code @Argument} / {@code @Option} 字段;</li>
 *   <li>三个注解类型本身 —— 运行时要 {@code getAnnotation(...)}。</li>
 * </ul>
 *
 * <p>输出到 {@code target/classes/META-INF/native-image/...},native-image
 * 会自动从 classpath 收集。没有找到命令类时什么都不写。
 */
@Mojo(name = "native-config", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE)
public class NativeConfigMojo extends AbstractMojo {

    private static final String COMMAND_ANNOTATION = "net.dobeen.jaravel.console.Command";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    File outputDirectory;

    /** 扫描依赖 jar 里的命令类(jaravel-cli 打开;普通应用通常只需要自己的类) */
    @Parameter(property = "jaravel.native.scanDependencies", defaultValue = "false")
    boolean scanDependencies;

    @Parameter(property = "jaravel.native.groupId", defaultValue = "net.dobeen.jaravel")
    String groupId;

    @Parameter(property = "jaravel.native.artifactId", defaultValue = "${project.artifactId}")
    String artifactId;

    @Parameter(property = "jaravel.native.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("jaravel:native-config 已跳过");
            return;
        }

        List<Path> classpath = compileClasspath();
        Set<String> classes = new LinkedHashSet<>();
        try (var loader = new URLClassLoader(toUrls(classpath),
                Thread.currentThread().getContextClassLoader())) {
            // 项目自己的类永远扫;依赖按开关扫
            scanDirectory(Path.of(project.getBuild().getOutputDirectory()), classes);
            if (scanDependencies) {
                for (Path element : classpath) {
                    if (Files.isDirectory(element)) {
                        scanDirectory(element, classes);
                    } else if (element.toString().endsWith(".jar")) {
                        scanJar(element, classes);
                    }
                }
            }

            var commands = new ArrayList<String>();
            for (String name : classes) {
                if (name.startsWith("module-info") || name.startsWith("package-info")) continue;
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    if (hasCommandAnnotation(type)) commands.add(name);
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // 扫描到的是无法独立加载的类(外部依赖缺类),跳过
                }
            }
            if (commands.isEmpty()) {
                getLog().info("没有发现 @Command 类,跳过 native 配置");
                return;
            }

            Path out = outputDirectory.toPath()
                    .resolve("META-INF/native-image/%s/%s/reflect-config.json"
                            .formatted(groupId, artifactId));
            Files.createDirectories(out.getParent());
            Files.writeString(out, render(commands));
            getLog().info("native 反射配置:" + commands.size() + " 个命令 → " + out);
        } catch (IOException e) {
            throw new MojoExecutionException("生成 native 反射配置失败", e);
        }
    }

    /** 不加载注解类本身,避免插件 classloader 与项目 classloader 的身份问题 */
    private static boolean hasCommandAnnotation(Class<?> type) {
        for (var annotation : type.getAnnotations()) {
            if (annotation.annotationType().getName().equals(COMMAND_ANNOTATION)) return true;
        }
        return false;
    }

    private static void scanDirectory(Path root, Set<String> classes) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString())
                    .forEach(rel -> addClassName(classes, rel));
        }
    }

    private static void scanJar(Path jar, Set<String> classes) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class")) addClassName(classes, name);
            }
        }
    }

    private static void addClassName(Set<String> classes, String relative) {
        String name = relative.replace(File.separatorChar, '/')
                .replace('/', '.');
        if (name.endsWith(".class")) {
            classes.add(name.substring(0, name.length() - ".class".length()));
        }
    }

    private String render(List<String> commands) {
        var sb = new StringBuilder("[\n");
        var entries = new ArrayList<String>();
        for (String name : commands) {
            entries.add("""
                    {"name":"%s","allDeclaredConstructors":true,"allDeclaredFields":true}"""
                    .formatted(name));
        }
        // 基类上的 @Argument/@Option 字段
        entries.add("""
                {"name":"net.dobeen.jaravel.console.ConsoleCommand","allDeclaredFields":true,"allDeclaredConstructors":true}""");
        entries.add("""
                {"name":"net.dobeen.jaravel.console.generators.GeneratorCommand","allDeclaredFields":true,"allDeclaredConstructors":true}""");
        // 注解本身要被反射读取
        for (String annotation : List.of(COMMAND_ANNOTATION,
                "net.dobeen.jaravel.console.Argument", "net.dobeen.jaravel.console.Option")) {
            entries.add("""
                    {"name":"%s","allDeclaredMethods":true}""".formatted(annotation));
        }
        sb.append(String.join(",\n", entries));
        sb.append("\n]\n");
        return sb.toString();
    }

    private List<Path> compileClasspath() throws MojoExecutionException {
        try {
            var paths = new LinkedHashSet<Path>();
            for (String element : project.getCompileClasspathElements()) {
                if (element != null && !element.isBlank()) paths.add(Path.of(element));
            }
            for (var artifact : project.getArtifacts()) {
                String scope = artifact.getScope();
                if (scope != null && scope.equals("test")) continue;
                if (artifact.getFile() != null) paths.add(artifact.getFile().toPath());
            }
            return List.copyOf(paths);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("无法解析编译 classpath", e);
        }
    }

    private static URL[] toUrls(List<Path> classpath) throws MojoExecutionException {
        try {
            URL[] urls = new URL[classpath.size()];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = classpath.get(i).toUri().toURL();
            }
            return urls;
        } catch (Exception e) {
            throw new MojoExecutionException("无法把 classpath 转成 URL", e);
        }
    }
}
