package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import net.dobeen.jaravel.console.Artisan;
import net.dobeen.jaravel.console.CommandRegistry;
import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.testing.FeatureTest;

/**
 * 静态门面必须指向**当前测试的**上下文 —— 即使上一个测试把它们踩乱了。
 *
 * <h2>★ 为什么补这条测试</h2>
 * <p>一个测试 JVM 里同时缓存着多个 Spring 上下文(默认测试上下文、
 * {@link BroadcastE2eTest} 自己的上下文……)。Spring 复用缓存上下文时
 * **不会重跑自动配置**,而 {@code Artisan} / {@code Application} 这些
 * 门面是 JVM 全局的静态字段 —— 它们会停在**最后启动的那个**上下文上。
 *
 * <p>症状是「测试刚插入的数据,命令却查不到」,而且只在特定的测试类
 * 执行顺序下出现:单独跑全绿,全量跑偶发(CI 上就是这么挂的)。
 * 修复在 {@code FeatureTest} 的 {@code @BeforeEach}:每个测试前把门面
 * 重绑到本测试的上下文。
 *
 * <p>这条测试用两个**有序**的方法把它钉死:先故意踩乱,再要求下一个
 * 测试看到的是自己的上下文。顺序由注解固定,不依赖文件系统枚举顺序。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaticFacadeIsolationTest extends FeatureTest {

    @Autowired
    CommandRegistry registry;

    @Test
    @Order(1)
    void aTestCanPolluteTheGlobalFacades() {
        Artisan.use(new CommandRegistry());
        Application.setEnvironment("production");
        Application.setDebug(true);

        assertThat(Application.environment()).isEqualTo("production");
    }

    @Test
    @Order(2)
    void theNextTestSeesTheFacadesOfItsOwnContext() {
        assertThat(Artisan.registry())
                .as("每个测试前都应重绑到本测试的上下文")
                .isSameAs(registry);

        assertThat(Application.environment())
                .as("环境也是上下文的一部分,不能停在上一个测试写进去的值")
                .isEqualTo("local");
        assertThat(Application.isDebug())
                .as("app.debug 取自本示例的 config/app.yml")
                .isTrue();
    }
}
