package net.dobeen.jaravel.testing;

import java.nio.file.Path;
import java.time.Duration;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 真实浏览器上的断言(docs/14 §6.1)。
 *
 * <p>链式 API 与 Laravel Dusk 对齐,但底层是 Playwright:
 * 自动等待、无 Selenium、跨 Chromium/Firefox/WebKit。
 *
 * <p>高级交互用 {@link #page()} 直接拿 Playwright 的 Page ——
 * 这层只封装「每条浏览器测试都要写的那几件事」。
 */
public final class Browser {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Page page;
    private final String baseUrl;

    Browser(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public Page page() {
        return page;
    }

    // ================= 操作 =================

    public Browser visit(String path) {
        page.navigate(baseUrl + path);
        page.waitForLoadState();
        return this;
    }

    /** 按 {@code name} 填 input / textarea */
    public Browser type(String name, String value) {
        page.locator("input[name=\"" + name + "\"], textarea[name=\"" + name + "\"]")
                .fill(value);
        return this;
    }

    /** 按可访问名称点按钮;没有按钮时回落到链接 */
    public Browser press(String text) {
        var name = new Page.GetByRoleOptions().setName(text);
        // or() 而不是 try/catch:按钮不存在时不必先等满一次超时再找链接
        page.getByRole(AriaRole.BUTTON, name)
                .or(page.getByRole(AriaRole.LINK, name))
                .first()
                .click();
        return this;
    }

    public Browser click(String selector) {
        page.locator(selector).click();
        return this;
    }

    // ================= 断言 =================

    public Browser assertPathIs(String path) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String actual = null;
        while (System.nanoTime() < deadline) {
            actual = java.net.URI.create(page.url()).getPath();
            if (path.equals(actual)) return this;
            sleep();
        }
        fail("路径应为 %s,实际 %s(URL:%s)".formatted(path, actual, page.url()));
        return this;
    }

    public Browser assertSee(String text) {
        try {
            page.getByText(text)
                    .first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout((double) WAIT.toMillis()));
            return this;
        } catch (PlaywrightException e) {
            fail("""
                    页面上没看到「%s」。
                      URL:%s
                      正文片段:%s""".formatted(text, page.url(), excerpt()));
            return this;
        }
    }

    public Browser assertDontSee(String text) {
        page.waitForLoadState();
        assertThat(page.content())
                .as("页面上不该出现「%s」", text)
                .doesNotContain(text);
        return this;
    }

    public Browser assertInputValue(String name, String expected) {
        assertThat(page.locator("input[name=\"" + name + "\"]").inputValue())
                .isEqualTo(expected);
        return this;
    }

    // ================= 调试 =================

    public Browser screenshot(Path file) {
        page.screenshot(new Page.ScreenshotOptions().setPath(file));
        return this;
    }

    private String excerpt() {
        String content = page.content();
        return content.length() <= 500 ? content : content.substring(0, 500) + "…";
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Browser of(Page page, String baseUrl) {
        return new Browser(page, baseUrl);
    }
}
