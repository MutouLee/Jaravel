package io.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 同一份模板在一个 JVM 里只有一个引擎({@link JbladeEngine#sharedForDirectory}）。
 *
 * <h2>它挡的是什么</h2>
 * <p>两个引擎共用一个 class 目录时,后建的会重新编译并覆盖文件,而先建的那个
 * ClassLoader 已经加载了外层类、还没加载惰性的内部类
 * ({@code @fragment} 与组件 slot 会生成匿名 {@code Content} 子类)。
 * 于是第一次渲染片段时报 {@code NoClassDefFoundError: …Generated$1} ——
 * 消息里只有一个类名,与模板写错了毫无关系。
 *
 * <p>这里测的是**身份**:同样的输入拿到同一个实例。那是上面那条竞态
 * 不可能发生的唯一充分条件 —— 一份 class 目录,一个 ClassLoader。
 */
class SharedEngineTest {

    @AfterEach
    void clearCache() {
        JbladeEngine.clearShared();
    }

    private Path views(Path tmp) throws Exception {
        Path views = tmp.resolve("views");
        Files.createDirectories(views.resolve("notes"));
        Files.writeString(views.resolve("notes/list.jblade.html"), """
                @params(java.util.List<String> items)
                <ul>
                @foreach(items as item)
                  @fragment('row')
                  <li>{{ item }}</li>
                  @endfragment
                @endforeach
                </ul>
                """);
        return views;
    }

    @Test
    @DisplayName("同样的 (视图目录, class 目录, 类加载器) 拿到同一个引擎")
    void sameInputsShareOneEngine(@TempDir Path tmp) throws Exception {
        Path views = views(tmp);
        Path classes = tmp.resolve("classes");

        var first = JbladeEngine.sharedForDirectory(views, classes, getClass().getClassLoader());
        var second = JbladeEngine.sharedForDirectory(views, classes, getClass().getClassLoader());

        assertThat(second)
                .as("两个 Spring 上下文各建一个引擎,就会有两个 ClassLoader 盯着同一份文件")
                .isSameAs(first);
    }

    @Test
    @DisplayName("相对路径与绝对路径算同一个 —— 键按规范化后的绝对路径")
    void pathsAreNormalised(@TempDir Path tmp) throws Exception {
        Path views = views(tmp);
        Path classes = tmp.resolve("classes");

        var first = JbladeEngine.sharedForDirectory(views, classes, getClass().getClassLoader());
        var second = JbladeEngine.sharedForDirectory(
                views.resolve("..").resolve("views"), classes, getClass().getClassLoader());

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("视图目录不同就是不同的引擎")
    void differentRootsGetDifferentEngines(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");
        var first = JbladeEngine.sharedForDirectory(views(tmp), classes, getClass().getClassLoader());

        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        var second = JbladeEngine.sharedForDirectory(other, classes, getClass().getClassLoader());

        assertThat(second).isNotSameAs(first);
    }

    @Test
    @DisplayName("★ 判据只认匿名内部类,不碰编译错误的消息")
    void onlyAnonymousInnerClassesAreTreatedAsMissingClasses() {
        // 真正要认的:NoClassDefFoundError 的消息就是一个斜杠形式的内部类名
        assertThat(JbladeEngine.missingGeneratedClass(
                new NoClassDefFoundError("gg/jte/generated/ondemand/notes/JtepageGenerated$1"), null))
                .isEqualTo("gg/jte/generated/ondemand/notes/JtepageGenerated$1");

        // 异常类型被包掉时也能从消息里认出来
        assertThat(JbladeEngine.missingGeneratedClass(new RuntimeException("boom"),
                "gg.jte.generated.ondemand.JtepageGenerated$2"))
                .isEqualTo("gg.jte.generated.ondemand.JtepageGenerated$2");

        // ★ 编译错误的消息里也有生成文件的路径,但那是「模板写错了」,不能动它。
        // 第一版的判据把这条也吞了 —— 而且只在 Linux 上吞(Windows 是反斜杠),
        // 于是本地 154 条全绿、CI 上三条红
        assertThat(JbladeEngine.missingGeneratedClass(new RuntimeException("compile failed"),
                "模板编译失败:gg/jte/generated/ondemand/JtetGenerated.java\n  是否想写 title"))
                .as("带 .java 的是编译错误,不是类加载失败")
                .isNull();
        assertThat(JbladeEngine.missingGeneratedClass(new RuntimeException("compile failed"),
                "模板编译失败:gg\\jte\\generated\\ondemand\\JtetGenerated.java"))
                .as("Windows 的反斜杠路径同样不该被认成类加载失败")
                .isNull();
    }

    @Test
    @DisplayName("共享的引擎照常渲染,片段也照常")
    void theSharedEngineStillWorks(@TempDir Path tmp) throws Exception {
        var engine = JbladeEngine.sharedForDirectory(views(tmp), tmp.resolve("classes"),
                getClass().getClassLoader());

        assertThat(engine.render("notes.list", Map.of("items", java.util.List.of("甲", "乙"))))
                .contains("<li>甲</li>")
                .contains("<li>乙</li>");
        assertThat(engine.renderFragment("notes.list", "row",
                        Map.of("items", java.util.List.of("甲"))).trim())
                .as("片段渲染走的是生成出来的匿名 Content 子类 —— 那正是出事的地方")
                .isEqualTo("<li>甲</li>");
    }
}
