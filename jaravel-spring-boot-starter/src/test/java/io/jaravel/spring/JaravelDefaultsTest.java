package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

/**
 * Jaravel 强制的那组 Spring 默认属性。
 *
 * <p>这些值都有具体的理由(见 {@link JaravelDefaults} 的注释),
 * 而「某一条被谁顺手删了」的症状往往出现在很远的地方 ——
 * 比如中文在测试里乱码、404 不走 Jaravel 的异常处理。
 * 所以每一条都被钉住。
 */
class JaravelDefaultsTest {

    private static final java.util.Map<String, Object> DEFAULTS =
            JaravelDefaults.defaults();

    @Test
    @DisplayName("虚拟线程 —— 请求作用域与门面的前提(ADR-007)")
    void virtualThreads() {
        assertThat(DEFAULTS).containsEntry("spring.threads.virtual.enabled", "true");
    }

    @Test
    @DisplayName("UTF-8 强制解码 —— 否则 form 里的中文乱码")
    void utf8() {
        assertThat(DEFAULTS)
                .containsEntry("server.servlet.encoding.charset", "UTF-8")
                .containsEntry("server.servlet.encoding.force", "true");
    }

    @Test
    @DisplayName("未匹配的请求要抛异常 —— 否则走不到 Jaravel 的 404")
    void throwOnNoHandler() {
        assertThat(DEFAULTS)
                .containsEntry("spring.mvc.throw-exception-if-no-handler-found", "true")
                .containsEntry("spring.web.resources.add-mappings", "false");
    }

    @Test
    @DisplayName("★ devtools 的 trigger-file —— 重启时机归 Jaravel")
    void devtoolsTriggerFile() {
        assertThat(DEFAULTS)
                .as("""
                        没有它 devtools 会在**文件一变**时就重启,于是:
                          · docs/15 §3.3 的「按需重启」失效
                            (连续编辑 10 个文件会重启 10 次);
                          · L2 方法体热替换失效 —— 刚换完字节就被整体重启。

                        ⚠ 不能改成 spring.devtools.restart.enabled=false:
                          那个标记 devtools 在 ApplicationStartingEvent 上
                          从 **System property** 读,早于 EnvironmentPostProcessor;
                          而且关掉之后 Restarter 不初始化,L3 也一起没了。""")
                .containsEntry("spring.devtools.restart.trigger-file",
                        ".jaravel-restart-trigger");
    }

    @Test
    @DisplayName("★ 这些是默认值,不是强制值 —— 加在属性源末尾")
    void addedLastSoUsersWin() {
        var environment = new StandardEnvironment();
        int before = environment.getPropertySources().size();

        new JaravelDefaults().postProcessEnvironment(
                environment, new SpringApplication());

        assertThat(environment.getPropertySources().size()).isEqualTo(before + 1);

        var names = environment.getPropertySources().stream()
                .map(org.springframework.core.env.PropertySource::getName)
                .toList();
        assertThat(names.get(names.size() - 1))
                .as("用户的 application.yml、命令行 -D、测试的 properties "
                        + "都必须优先于它")
                .isEqualTo(JaravelDefaults.SOURCE_NAME);
    }

    @Test
    @DisplayName("重复调用不会加两遍")
    void idempotent() {
        var environment = new StandardEnvironment();
        var processor = new JaravelDefaults();
        var application = new SpringApplication();

        processor.postProcessEnvironment(environment, application);
        int after = environment.getPropertySources().size();
        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().size()).isEqualTo(after);
    }
}
