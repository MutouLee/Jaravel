package io.jaravel.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.jaravel.view.JbladeException;
import io.jaravel.view.ViewPrecompiler;

/**
 * 把 {@code src/main/resources/views} 下的 {@code .jblade.html}
 * 预编译成 class 文件,写进 {@code target/classes}。
 *
 * <p>绑定在 {@code process-classes}:这时候应用自己的类已经编译完,
 * 类组件构造函数里的 {@code -parameters} 名字才解析得到。
 *
 * <pre>{@code
 * <plugin>
 *   <groupId>io.jaravel</groupId>
 *   <artifactId>jaravel-maven-plugin</artifactId>
 *   <version>${jaravel.version}</version>
 *   <executions>
 *     <execution><goals><goal>compile-views</goal></goals></execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "compile-views", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE)
public class CompileViewsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(property = "jaravel.views.source",
            defaultValue = "${project.basedir}/src/main/resources/views")
    File viewRoot;

    @Parameter(property = "jaravel.views.output",
            defaultValue = "${project.build.outputDirectory}")
    File outputDirectory;

    @Parameter(property = "jaravel.views.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("jaravel:compile-views 已跳过(-Djaravel.views.skip=true)");
            return;
        }
        Path views = viewRoot.toPath();
        if (!Files.isDirectory(views)) {
            getLog().info("没有 views 目录,跳过模板预编译:" + views);
            return;
        }

        List<Path> classpath = compileClasspath();
        getLog().debug("jblade 编译 classpath:" + classpath);
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(toUrls(classpath), parent)) {
            var result = ViewPrecompiler.precompile(
                    views, outputDirectory.toPath(), classpath, loader);
            getLog().info("jblade 预编译完成:%d 个模板,%d 个 class"
                    .formatted(result.templates(), result.classFiles()));
        } catch (JbladeException e) {
            // 消息里已经带 .jblade.html 的文件名与行号,不要包一层把位置藏掉
            throw new MojoFailureException(e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("jblade 模板预编译失败", e);
        }
    }

    private List<Path> compileClasspath() throws MojoExecutionException {
        try {
            // getCompileClasspathElements() 在“只跑这个 goal”的调用下可能只有
            // target/classes(依赖还没解析),所以两条路都走一遍。
            var paths = new java.util.LinkedHashSet<Path>();
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
