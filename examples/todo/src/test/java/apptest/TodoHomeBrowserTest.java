package apptest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.testing.BrowserTest;

/**
 * 第二个 {@code BrowserTest} 子类。
 *
 * <p>它盯的不是某个功能,而是一条框架不变量:**同一个测试 JVM 里的多个
 * 浏览器测试类共用一个浏览器实例**。若基类在每个类的 {@code @AfterAll}
 * 里关浏览器,后运行的类会拿到一个已关闭的实例 —— 症状是「单跑绿、一起跑红」,
 * 而且只有真的写第二个类才会发现。
 *
 * <p>同时它也验证基类默认的 {@code @SpringBootTest} 配置:这个类没有写
 * 任何注解,靠 {@code JaravelTestApplication} + {@code RANDOM_PORT} 起服务。
 */
class TodoHomeBrowserTest extends BrowserTest {

    @Test
    @DisplayName("首页在真实浏览器里渲染 —— 且第二个浏览器测试类还能用同一个实例")
    void homeRenders() {
        browse(b -> b.visit("/")
                .assertSee("Todo")
                .assertSee("添加"));
    }
}
