package io.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v0.9 的构建期预编译:jblade → jte → class 文件,运行期零转写。
 */
@DisplayName("预编译(视图 → class)")
class PrecompileTest {

    @TempDir Path tmp;

    private Path views() { return tmp.resolve("views"); }

    private Path output() { return tmp.resolve("classes"); }

    private void write(String view, String source) throws IOException {
        Path file = views().resolve(view.replace('.', '/') + JbladeEngine.SUFFIX);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private List<Path> compilePath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .toList();
    }

    @Test
    @DisplayName("布局 + 匿名组件在预编译后渲染结果与开发模式一致")
    void renders_layout_and_component() throws Exception {
        write("layouts.app", """
                <!DOCTYPE html>
                <html><body>@yield('content')</body></html>
                """);
        write("components.alert", """
                @props(String type = "info")
                <div class="alert {{ type }}">{{ slot }}</div>
                """);
        write("hello", """
                @params(String name)
                @extends('layouts.app')
                @section('content')
                <h1>{{ name }}</h1>
                <x-alert type="danger">出错了</x-alert>
                @endsection
                """);

        var result = ViewPrecompiler.precompile(
                views(), output(), compilePath(), getClass().getClassLoader());

        // 3 个应用模板 + test classpath 上的 framework-provided 夹具
        assertThat(result.templates()).isEqualTo(4);
        assertThat(result.classFiles()).isGreaterThanOrEqualTo(4);
        assertThat(Files.exists(output().resolve(ViewPrecompiler.MARKER_RESOURCE))).isTrue();
        assertThat(Files.exists(output().resolve(ViewPrecompiler.METADATA_RESOURCE))).isTrue();
        try (var generated = Files.walk(output().resolve("gg/jte/generated/precompiled"))) {
            assertThat(generated.filter(p -> p.toString().endsWith(".java")).toList()).isEmpty();
        }

        try (var loader = new URLClassLoader(
                new URL[]{output().toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                var engine = JbladeEngine.forPrecompiled(loader);
                assertThat(engine.exists("hello")).isTrue();

                String html = engine.render("hello", Map.of("name", "世界"));
                assertThat(html)
                        .contains("<h1>世界</h1>")
                        .contains("class=\"alert danger\"")
                        .contains("出错了")
                        .contains("<!DOCTYPE html>");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    @DisplayName("编译错误映射回 .jblade.html 的文件名与行号")
    void maps_compile_error_to_blade_line() throws Exception {
        write("bad", """
                @params(String name)
                <p>{{ name.nmae }}</p>
                """);

        assertThatThrownBy(() -> ViewPrecompiler.precompile(
                views(), output(), compilePath(), getClass().getClassLoader()))
                .isInstanceOf(JbladeException.class)
                .hasMessageContaining("bad.jblade.html:2")
                .hasMessageContaining("nmae");
    }

    @Test
    @DisplayName("没有模板时是空操作,不写 marker")
    void empty_view_root_is_noop() throws Exception {
        Files.createDirectories(views());

        // 隔离的 loader:否则会扫到 test classpath 上的 views 夹具
        ClassLoader isolated = new ClassLoader(null) {};
        var result = ViewPrecompiler.precompile(
                views(), output(), compilePath(), isolated);

        assertThat(result.templates()).isZero();
        assertThat(Files.exists(output().resolve(ViewPrecompiler.MARKER_RESOURCE))).isFalse();
    }
}
