package io.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @aware}:从**外层组件**继承 prop(docs/04 §1.7)。
 *
 * <p>它解决的是「菜单的颜色要传给每一个菜单项」这类重复:
 *
 * <pre>{@code
 * <x-menu color="purple">
 *   <x-menu-item>一</x-menu-item>     ← 不用再写 color="purple"
 *   <x-menu-item>二</x-menu-item>
 * </x-menu>
 * }</pre>
 *
 * <p>解析顺序是**显式 > 外层 > 默认值**,这三条各有一个测试 ——
 * 它们是这个指令唯一会被问到的三个问题。
 */
class AwareTest {

    /** 外层:声明 color,body 原样输出 */
    private static final String MENU = """
            @props(String color = "gray")
            <ul class="menu-{{ color }}">{{ slot }}</ul>
            """;

    /** 内层:不要求调用方传 color */
    private static final String ITEM = """
            @aware(String color = "gray")
            <li class="item-{{ color }}">{{ slot }}</li>
            """;

    private String render(Map<String, String> templates, Path tmp) {
        return JbladeEngine.forMap(templates, tmp.resolve("c"),
                        AwareTest.class.getClassLoader())
                .render("page", Map.of());
    }

    @Test
    @DisplayName("★ 从外层组件继承 —— 调用方一个字都不用写")
    void inheritsFromTheEnclosingComponent(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.menu", MENU,
                "components.item", ITEM,
                "page", """
                        <x-menu color="purple">
                          <x-item>一</x-item>
                          <x-item>二</x-item>
                        </x-menu>
                        """), tmp);

        assertThat(out)
                .contains("menu-purple")
                .contains("item-purple")
                .doesNotContain("item-gray");
    }

    @Test
    @DisplayName("★ 显式传的赢过外层 —— 「我明明传了却不生效」是最难查的一类")
    void explicitValueWins(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.menu", MENU,
                "components.item", ITEM,
                "page", """
                        <x-menu color="purple">
                          <x-item color="red">一</x-item>
                          <x-item>二</x-item>
                        </x-menu>
                        """), tmp);

        assertThat(out).contains("item-red").contains("item-purple");
    }

    @Test
    @DisplayName("外层没有时用默认值")
    void fallsBackToTheDefault(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.item", ITEM,
                "page", "<x-item>孤零零</x-item>\n"), tmp);

        assertThat(out).contains("item-gray");
    }

    @Test
    @DisplayName("★ 继承跨过中间那层 —— 爷爷传的,孙子拿得到")
    void inheritsAcrossGenerations(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.menu", MENU,
                "components.item", """
                        @aware(String color = "gray")
                        <li class="item-{{ color }}"><x-icon /></li>
                        """,
                "components.icon", """
                        @aware(String color = "gray")
                        <i class="icon-{{ color }}"></i>
                        """,
                "page", """
                        <x-menu color="purple">
                          <x-item>一</x-item>
                        </x-menu>
                        """), tmp);

        assertThat(out)
                .as("压栈里放的是**解析后**的值,所以 item 继承到的 purple 能再往下传")
                .contains("icon-purple");
    }

    @Test
    @DisplayName("出了外层组件的范围就不再继承 —— 栈是成对进出的")
    void doesNotLeakOutOfScope(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.menu", MENU,
                "components.item", ITEM,
                "page", """
                        <x-menu color="purple">
                          <x-item>里面</x-item>
                        </x-menu>
                        <x-item>外面</x-item>
                        """), tmp);

        assertThat(out)
                .as("「外面」那一个在 menu 的范围之外,应当回到默认值")
                .contains("item-purple")
                .contains("item-gray");
    }

    @Test
    @DisplayName("@aware 的名字不漏进属性袋 —— color 不是合法的 HTML 属性")
    void awareNamesAreNotHtmlAttributes(@TempDir Path tmp) {
        String out = render(Map.of(
                "components.item", """
                        @aware(String color = "gray")
                        <li {{ attributes }}>{{ color }}</li>
                        """,
                "page", "<x-item color=\"red\" id=\"first\">x</x-item>\n"), tmp);

        assertThat(out)
                .as("id 是 HTML 属性,进属性袋;color 是 @aware 声明过的 prop,不进")
                .contains("id=\"first\"")
                .doesNotContain("color=\"red\"");
    }

    @Nested
    @DisplayName("报错")
    class Errors {

        @Test
        @DisplayName("没写括号")
        void missingParens() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile("@aware\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("@aware 需要括号");
        }

        @Test
        @DisplayName("没写类型")
        void missingType() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile("@aware(color)\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("少了类型");
        }

        @Test
        @DisplayName("★ 基本类型:「没传」要能表示成 null,所以要用包装类型")
        void primitiveTypeIsRejected() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile("@aware(int size = 3)\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("不能是基本类型")
                    .hasMessageContaining("Integer size");
        }

        @Test
        @DisplayName("★ 泛型类型:Class 字面量不存在(擦除),指路到 @props")
        void genericTypeIsRejected() {
            assertThatThrownBy(() -> JbladeTranspiler
                    .transpile("@aware(java.util.List<String> tags = null)\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("不能带泛型")
                    .hasMessageContaining("@props");
        }

        @Test
        @DisplayName("★ 外层传进来的类型不对:说清是谁传的、该怎么改")
        void typeMismatchExplainsItself(@TempDir Path tmp) {
            assertThatThrownBy(() -> render(Map.of(
                    "components.menu", """
                            @props(Integer color = 1)
                            <ul>{{ slot }}</ul>
                            """,
                    "components.item", ITEM,
                    "page", """
                            <x-menu :color="42">
                              <x-item>一</x-item>
                            </x-menu>
                            """), tmp))
                    .hasMessageContaining("@aware(\"color\")")
                    .hasMessageContaining("String")
                    .hasMessageContaining("Integer");
        }
    }
}
