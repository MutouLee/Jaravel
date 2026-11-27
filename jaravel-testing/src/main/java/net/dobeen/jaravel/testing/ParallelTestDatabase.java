package net.dobeen.jaravel.testing;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * 并行测试的每进程数据源开关(docs/14 §6.3、ADR-017)。
 *
 * <p>只在 {@code -Djaravel.test.parallel=true} 或
 * {@code JARAVEL_TEST_PARALLEL=1} 时工作。{@code jaravel test --parallel}
 * 两样都会设置 —— 前者给直接跑 Maven 的人,后者避免不同 Surefire 配置对
 * 系统属性传递的差异。
 *
 * <h2>为什么是 EnvironmentPostProcessor</h2>
 * <p>必须在 DataSource bean 创建**之前**改 {@code spring.datasource.url};
 * 到 {@code @Configuration} 或 BeanPostProcessor 都太晚。Spring Boot 的
 * {@code ConfigDataEnvironmentPostProcessor} 先加载 application.yml,
 * 本类用更高的 order 紧随其后,并在测试内联属性之后。
 *
 * <h2>多个 Spring 上下文共存</h2>
 * <p>一个 fork 里可能同时缓存 MOCK 与 RANDOM_PORT 两个上下文。派生库
 * **每个 JVM 每 URL 只准备一次**({@code PREPARED}),否则第二个上下文
 * 启动时 drop/create 会把第一个上下文的表删掉。
 */
public class ParallelTestDatabase implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "jaravelParallelTestDatabase";
    static final String PROPERTY = "jaravel.test.parallel";
    static final String ENV = "JARAVEL_TEST_PARALLEL";

    private static final long PID = ProcessHandle.current().pid();
    private static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!enabled()) return;
        if (environment.getPropertySources().contains(SOURCE_NAME)) return;

        String property = "spring.datasource.url";
        String url = environment.getProperty(property);
        String hikariUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
        String hikariFlatUrl = environment.getProperty("spring.datasource.jdbc-url");
        if (hikariUrl != null && !hikariUrl.isBlank()) {
            property = "spring.datasource.hikari.jdbc-url";
            url = hikariUrl;
        } else if (hikariFlatUrl != null && !hikariFlatUrl.isBlank()) {
            property = "spring.datasource.jdbc-url";
            url = hikariFlatUrl;
        }
        if (url == null || url.isBlank()) return;

        // 测试显式指定了数据源就尊重它 —— 那是测试作者自己管理的库
        PropertySource<?> inlined = environment.getPropertySources()
                .get(TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (inlined != null
                && (inlined.containsProperty("spring.datasource.url")
                    || inlined.containsProperty("spring.datasource.jdbc-url")
                    || inlined.containsProperty("spring.datasource.hikari.jdbc-url"))) {
            return;
        }

        // 派生出来的是一个空库;每个 fork 自己跑迁移,否则测试看到的是「没有表」。
        // 用户显式给了 jaravel.migrate.on-boot 就尊重它;-Djaravel.test.migrate=false 可关闭。
        forceMigrations();

        ForkedDatabase.Plan plan;
        try {
            plan = ForkedDatabase.plan(url, PID, Path.of("").toAbsolutePath());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ForkedDatabase.mask(e.getMessage()), e);
        }
        if (plan == null) return;

        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        synchronized (PREPARED) {
            if (!PREPARED.contains(plan.derivedUrl())) {
                ForkedDatabase.provision(plan, username, password);
                PREPARED.add(plan.derivedUrl());

                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> ForkedDatabase.cleanup(plan, username, password),
                        "jaravel-test-db-cleanup"));

                System.err.printf("[jaravel test] 进程 %d 的独立测试库:%s%n",
                        PID, label(plan));
            }
        }

        environment.getPropertySources()
                .addFirst(new MapPropertySource(SOURCE_NAME, Map.of(property, plan.derivedUrl())));
    }

    private static String label(ForkedDatabase.Plan plan) {
        return plan.kind() == ForkedDatabase.Kind.SQLITE
                ? plan.derivedFile().toString()
                : plan.derivedDatabase();
    }

    static boolean enabled() {
        String flag = System.getProperty(PROPERTY);
        if (flag == null || flag.isBlank()) flag = System.getenv(ENV);
        return flag != null && !flag.isBlank()
                && !flag.equals("0") && !flag.equalsIgnoreCase("false");
    }

    private static void forceMigrations() {
        if (System.getProperty("jaravel.migrate.on-boot") != null) return;
        String disabled = System.getProperty("jaravel.test.migrate");
        if (disabled != null
                && (disabled.equals("0") || disabled.equalsIgnoreCase("false"))) {
            return;
        }
        System.setProperty("jaravel.migrate.on-boot", "true");
    }

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor 是 HIGHEST_PRECEDENCE + 10
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
