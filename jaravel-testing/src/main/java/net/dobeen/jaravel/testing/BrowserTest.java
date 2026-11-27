package net.dobeen.jaravel.testing;

import java.util.function.Consumer;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * 浏览器测试基类(docs/14 §6.1)。
 *
 * <pre>{@code
 * class TodoBrowserTest extends BrowserTest {
 *     @Test void adds_a_todo() {
 *         browse(b -> b.visit("/")
 *                      .type("title", "买牛奶")
 *                      .press("添加")
 *                      .assertSee("买牛奶"));
 *     }
 * }
 * }</pre>
 *
 * <h2>默认就起一个真实端口的 Spring 测试</h2>
 * <p>与 {@link FeatureTest} 一样由 {@link JaravelTestApplication} 引导,
 * 测试类放哪个包都可以,默认 {@code webEnvironment = RANDOM_PORT}。
 * 需要用自己的入口类或额外配置时,在子类上写自己的
 * {@code @SpringBootTest(...)} 覆盖继承来的那一个。
 *
 * <p>它需要一个**真的跑起来的 HTTP 服务**(浏览器不会走 MockServlet),
 * 端口通过 {@link LocalServerPort} 注入。若子类把环境改成 MOCK,
 * 这个端口会是 0,{@code BrowserTest} 会直接给出清晰的错误。
 *
 * <h2>为什么单独一个基类,不继承 FeatureTest</h2>
 * <p>FeatureTest 是 MOCK 环境的进程内测试,默认每个测试回滚、直接调
 * handler —— 与真实浏览器是两条路。混在一起的后果是「以为测了浏览器,
 * 实际走的是 MockServlet」,而那正是浏览器测试存在的理由的反面。
 *
 * <h2>默认不跑</h2>
 * <p>Playwright 要下载浏览器二进制(几百 MB),而且每个用例秒级。
 * 默认套件(不依赖外部服务)必须保持可跑,所以这里用
 * {@code -Djaravel.browser.enabled=true} gate。第一次使用:
 *
 * <pre>
 * mvn test -Djaravel.browser.enabled=true
 * </pre>
 *
 * <p>首次运行会由 Playwright 自动下载浏览器(一次性,约几百 MB)。
 * 若想跳过这一步、自己预装:
 *
 * <pre>
 * mvn exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.microsoft.playwright.CLI \
 *   -Dexec.args="install chromium"
 * </pre>
 *
 * <p>预装之后设置 {@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1},避免
 * Playwright 在驱动初始化时再装一遍 Firefox / WebKit。
 *
 * <p>调试时加 {@code -Djaravel.browser.headed=true} 看真实窗口;
 * 换引擎用 {@code -Djaravel.browser.name=firefox}(或 {@code webkit},
 * 默认 {@code chromium})。
 */
@SpringBootTest(
        classes = JaravelTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BrowserTest {

    private static Playwright playwright;
    private static com.microsoft.playwright.Browser playwrightBrowser;

    private BrowserContext context;
    private Browser browser;

    @LocalServerPort
    protected int port;

    @Autowired
    private ApplicationContext applicationContext;

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 与 {@link FeatureTest} 同一个理由:静态门面必须指向当前测试的上下文。 */
    @BeforeEach
    void rebindStaticFacades() {
        StaticBindings.bind(applicationContext);
    }

    // ================= 生命周期 =================

    @BeforeEach
    void openPage() {
        // 放在 @BeforeEach 而不是 @BeforeAll:关掉时报告里是逐条 skipped,
        // 而不是整个类 0 条 —— 后者看起来像「测试没被收集」。
        Assumptions.assumeTrue(enabled(),
                "浏览器测试默认关闭。加 -Djaravel.browser.enabled=true(首次会下载浏览器)");

        if (port <= 0) {
            throw new IllegalStateException("""
                    BrowserTest 需要一个真实端口,但 @LocalServerPort 没有注入。
                    子类请标注 @SpringBootTest(webEnvironment = RANDOM_PORT) 或 DEFINED_PORT ——
                    浏览器不会走 MockServlet,这是 BrowserTest 与 FeatureTest 的根本区别。""");
        }

        // 整个测试 JVM 共用一个浏览器实例:一个模块里的多个 BrowserTest 子类
        // 不会各起一份,也不会互相关掉对方;退出时由 shutdown hook 收尾。
        context = browserInstance().newContext(
                new com.microsoft.playwright.Browser.NewContextOptions()
                        .setBaseURL(baseUrl()));
        var page = context.newPage();
        page.setDefaultTimeout(10_000);
        browser = Browser.of(page, baseUrl());
    }

    private static synchronized com.microsoft.playwright.Browser browserInstance() {
        if (playwrightBrowser == null) {
            playwright = Playwright.create();
            playwrightBrowser = browserType(playwright).launch(
                    new BrowserType.LaunchOptions().setHeadless(!headed()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (playwrightBrowser != null) playwrightBrowser.close();
                } catch (Exception ignored) {
                    // JVM 正在退出,关不上也不该再制造噪音
                }
                try {
                    if (playwright != null) playwright.close();
                } catch (Exception ignored) {
                }
            }, "jaravel-browser-close"));
        }
        return playwrightBrowser;
    }

    private static BrowserType browserType(Playwright playwright) {
        String name = System.getProperty("jaravel.browser.name", "chromium");
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> throw new IllegalArgumentException(
                    "未知浏览器 '" + name + "';可用:chromium / firefox / webkit");
        };
    }

    /** 同 FeatureTest:测试结束后把时间放回去,免得漏进别的测试 */
    @AfterEach
    void restoreClock() {
        net.dobeen.jaravel.support.Clock.reset();
    }

    @AfterEach
    void closePage() {
        if (context != null) context.close();
    }

    // ================= 用法 =================

    protected void browse(Consumer<Browser> actions) {
        actions.accept(browser);
    }

    protected Browser browser() {
        return browser;
    }

    protected static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("jaravel.browser.enabled", "false"));
    }

    private static boolean headed() {
        return Boolean.parseBoolean(System.getProperty("jaravel.browser.headed", "false"));
    }
}
