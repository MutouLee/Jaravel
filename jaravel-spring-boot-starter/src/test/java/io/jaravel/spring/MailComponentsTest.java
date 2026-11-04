package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.view.JbladeEngine;

/**
 * 内置邮件组件(docs/11 §1.4 的 {@code <x-mail::*>})。
 *
 * <h2>为什么这套测试在 starter 里</h2>
 * <p>模板是 {@code jaravel-mail} 的资源,渲染要 {@code jaravel-view} ——
 * 而那两个模块**刻意不互相依赖**(SPI 由 starter 接上,理由写在
 * jaravel-mail 的 pom 里:邮件的单元测试不该拖进一个 jte 编译器)。
 * starter 两个都有,所以这里是唯一能真跑这套模板的地方。
 *
 * <p>而且走的正是真实应用的那条路:{@code forClasspath("views")} ——
 * 组件来自 jar 里的 {@code views/components/mail/},不是测试里拼出来的字符串。
 */
class MailComponentsTest {

    /** 生产模式:组件从 classpath 的 views/ 下解析 */
    private String renderFromClasspath(String view, Map<String, Object> data, Path tmp) {
        return JbladeEngine.forClasspath("views", tmp.resolve("jte"), getClass().getClassLoader())
                .render(view, data);
    }

    /** 开发模式:应用自己的 views 目录 + classpath 回落(框架带的组件走回落) */
    private String renderFromDirectory(Path views, String view, Map<String, Object> data, Path tmp) {
        return JbladeEngine.forDirectory(views, tmp.resolve("jte"), getClass().getClassLoader())
                .render(view, data);
    }

    private Path writeApplicationView(Path tmp, String name, String content) throws Exception {
        Path views = tmp.resolve("views");
        Path file = views.resolve(name + JbladeEngine.SUFFIX);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return views;
    }

    @Test
    @DisplayName("★ 框架自带的邮件组件在 classpath 上能被解析到")
    void componentsResolveFromClasspath(@TempDir Path tmp) throws Exception {
        // 应用的模板在 classpath 的 views/ 下也能放,这里直接放到临时目录走开发模式
        Path views = writeApplicationView(tmp, "mail/shipped", """
                @params(String tracking)
                <x-mail::message title="订单已发货">
                  <p>你的订单已经发出。</p>
                  <x-mail::button :url="tracking">查看物流</x-mail::button>
                </x-mail::message>
                """);

        String html = renderFromDirectory(views, "mail.shipped",
                Map.of("tracking", "https://example.com/t/42"), tmp);

        assertThat(html)
                .as("""
                        这一条同时是 classpath 回落的验收:
                        应用的 views 目录里没有 components/mail/message,
                        它来自 jaravel-mail 的 jar。""")
                .contains("<!DOCTYPE html>")
                .contains("你的订单已经发出")
                .contains("https://example.com/t/42")
                .contains("查看物流");
    }

    @Test
    @DisplayName("★ 布局是 table 套 table、样式全部内联 —— 邮件客户端的唯一可靠做法")
    void layoutIsTableBasedWithInlineStyles(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                <x-mail::message>正文</x-mail::message>
                """);

        String html = renderFromDirectory(views, "m", Map.of(), tmp);

        assertThat(html)
                .as("Outlook 的渲染引擎是 Word,对 div + CSS 的支持极不可靠")
                .contains("<table role=\"presentation\"")
                .contains("style=\"")
                .as("外部样式表与 <style> 块在邮件里不可靠,所以不该出现")
                .doesNotContain("<style")
                .doesNotContain("<link");
    }

    @Test
    @DisplayName("具名 slot:header / footer / subcopy 不传就不渲染")
    void optionalSlots(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                <x-mail::message>
                  正文
                  <x-slot:header>小店</x-slot>
                  <x-slot:subcopy>按钮点不开?把地址粘到浏览器里。</x-slot>
                </x-mail::message>
                """);

        String html = renderFromDirectory(views, "m", Map.of(), tmp);

        assertThat(html).contains("小店").contains("粘到浏览器");
        assertThat(html).as("footer 没传,那一块不该出现").doesNotContain("padding:16px 0 0;text-align:center");
    }

    @Test
    @DisplayName("按钮:颜色名映射到色值,两层都带背景色")
    void buttonColors(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                <x-mail::message>
                  <x-mail::button url="/a">默认</x-mail::button>
                  <x-mail::button url="/b" color="danger">撤销</x-mail::button>
                </x-mail::message>
                """);

        String html = renderFromDirectory(views, "m", Map.of(), tmp);

        assertThat(html).contains(io.jaravel.mail.MailTheme.PRIMARY)
                        .contains(io.jaravel.mail.MailTheme.DANGER);
        assertThat(html.split(io.jaravel.mail.MailTheme.DANGER, -1).length - 1)
                .as("""
                        背景色同时写在外层 <td> 和里面的 <a> 上 ——
                        <a> 的 padding 在 Outlook 里不可靠,而两层都写的话
                        谁被剥掉另一个还在。""")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★ 表格:单元格由组件渲染,所以每个 td 都带内联样式")
    void tableCellsCarryTheirOwnStyles(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                @params(java.util.List<String> items)
                <x-mail::message>
                  <x-mail::table>
                    <tr><x-mail::th>商品</x-mail::th><x-mail::th align="right">数量</x-mail::th></tr>
                    @foreach (items as item)
                    <tr><x-mail::td>{{ item }}</x-mail::td><x-mail::td align="right">1</x-mail::td></tr>
                    @endforeach
                  </x-mail::table>
                </x-mail::message>
                """);

        String html = renderFromDirectory(views, "m", Map.of("items", List.of("茶", "杯")), tmp);

        assertThat(html).contains("茶").contains("杯").contains("商品");
        // 按 td 组件特有的内边距数,不按 "<td style=" 数 —— message 布局自己也有 td
        assertThat(html.split("padding:10px 12px", -1).length - 1)
                .as("""
                        外壳够不到调用方写的 <td>,所以单元格也得是组件 ——
                        否则渲染出来是个没边框没内边距的裸表格,
                        比没有这个组件更坏(它看起来像是提供了样式)。""")
                .isEqualTo(4);
        assertThat(html).contains("text-align:right");
    }

    @Test
    @DisplayName("面板:强调块有灰底与左边界")
    void panel(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                <x-mail::message><x-mail::panel>预计 3 天送达。</x-mail::panel></x-mail::message>
                """);

        assertThat(renderFromDirectory(views, "m", Map.of(), tmp))
                .contains("预计 3 天送达")
                .contains("border-left:4px solid");
    }

    @Test
    @DisplayName("★ 正文里的用户输入照样转义 —— 邮件也是 HTML 注入的目标")
    void userInputIsEscaped(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                @params(String name)
                <x-mail::message>
                  <p>你好,{{ name }}</p>
                  <x-mail::button url="/a">确认</x-mail::button>
                </x-mail::message>
                """);

        String html = renderFromDirectory(views, "m",
                Map.of("name", "<script>alert(1)</script>"), tmp);

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("应用可以用同名模板覆盖框架自带的组件(零命令的 vendor:publish)")
    void applicationCanOverrideAComponent(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "m", """
                <x-mail::panel>正文</x-mail::panel>
                """);
        Files.createDirectories(views.resolve("components/mail"));
        Files.writeString(views.resolve("components/mail/panel" + JbladeEngine.SUFFIX),
                "@props()\n<div class=\"my-panel\">{{ slot }}</div>\n");

        assertThat(renderFromDirectory(views, "m", Map.of(), tmp))
                .as("应用目录优先于 classpath —— Laravel 要 vendor:publish 才能做到")
                .contains("my-panel")
                .doesNotContain("border-left:4px solid");
    }

    @Test
    @DisplayName("★ 端到端:组件邮件的纯文本版本里,按钮的 URL 还在")
    void plainTextVersionKeepsTheButtonUrl(@TempDir Path tmp) throws Exception {
        Path views = writeApplicationView(tmp, "mail/shipped", """
                @params(String tracking)
                <x-mail::message title="订单已发货">
                  <p>你的订单已经发出。</p>
                  <x-mail::button :url="tracking">查看物流</x-mail::button>
                  <x-mail::table>
                    <tr><x-mail::th>商品</x-mail::th><x-mail::th>数量</x-mail::th></tr>
                    <tr><x-mail::td>铁观音</x-mail::td><x-mail::td>2</x-mail::td></tr>
                  </x-mail::table>
                </x-mail::message>
                """);
        var engine = JbladeEngine.forDirectory(views, tmp.resolve("jte"),
                getClass().getClassLoader());

        var renderer = new io.jaravel.mail.MailRenderer(
                (view, data, locale) -> engine.render(view, data),
                io.jaravel.mail.Address.of("noreply@shop.com", "小店"));

        var message = renderer.render(new io.jaravel.mail.Mailable() {
            @Override public io.jaravel.mail.Envelope envelope() {
                return io.jaravel.mail.Envelope.of().subject("订单已发货");
            }
            @Override public io.jaravel.mail.Content content() {
                return io.jaravel.mail.Content.view("mail.shipped")
                        .with("tracking", "https://example.com/t/1024");
            }
        }, new io.jaravel.mail.MailRenderer.Recipients(
                List.of(io.jaravel.mail.Address.of("a@b.com", null)), List.of(), List.of()), null);

        assertThat(message.text())
                .as("""
                        这是「只写一份模板」的真实代价检查:纯文本版本是从
                        组件渲染出的 HTML 退化来的。退化丢了 URL 的话,
                        那封邮件的行动号召就没了 —— 而 <x-mail::button>
                        把整封邮件的行动号召都放在那一个 <a> 里。""")
                .contains("查看物流:https://example.com/t/1024")
                .as("head 整段去掉,标题不该在正文里重复一遍")
                .doesNotStartWith("订单已发货")
                .contains("商品\t数量");
    }

    @Test
    @DisplayName("生产模式(forClasspath)也能解析到这套组件")
    void worksInProductionMode(@TempDir Path tmp) {
        // 入口模板也必须在 classpath 上 —— 用 jaravel-mail 自带的组件直接当入口
        assertThat(renderFromClasspath("components.mail.panel", Map.of(), tmp))
                .as("生产模式没有目录回落这一跳,组件必须本来就在 classpath 的 views/ 下")
                .contains("border-left:4px solid");
    }
}
