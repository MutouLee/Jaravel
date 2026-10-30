package io.jaravel.spring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.Grammar;
import io.jaravel.database.grammar.MySqlGrammar;
import io.jaravel.database.grammar.PostgresGrammar;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.http.Middleware;
import io.jaravel.routing.RouteCollection;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;
import io.jaravel.view.JbladeEngine;
import io.jaravel.view.ViewEngine;

/**
 * Jaravel 的自动装配。**本模块是唯一允许 import Spring 的地方**(ADR-001)。
 *
 * <p>所有 {@code @ConditionalOnMissingBean} 都是刻意的:用户想换掉任何一块
 * (视图引擎、异常处理器、Grammar)只需提供自己的 bean。
 */
/*
 * @Import(JaravelComponentScan) 让 app / routes / database 三个约定包
 * 在**任何**启动方式下都被扫描,包括 @SpringBootTest ——
 * 它不走 Jaravel.run,所以不能只靠那里加。
 *
 * 不加的症状:集成测试里「没有找到任何 RouteDefinition 实现」,
 * 所有请求 404,而同一个应用 mvn spring-boot:run 起来完全正常。
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@org.springframework.context.annotation.Import(JaravelComponentScan.class)
public class JaravelAutoConfiguration {

    /**
     * 打包后从 classpath 读哪些配置文件。
     *
     * <p>必须显式列举:jar 里**无法可靠地列出一个目录**
     * (classpath 目录遍历在 fat jar / 嵌套 jar / 模块路径下行为各不相同),
     * 而「漏了一个文件就静默用默认值」比「列表要维护」糟得多 ——
     * 症状是「本地好的,打包后登录全挂」,因为开发期走的是文件系统分支。
     *
     * <p>**加新的 config/*.yml 时必须同步这里。** 不存在的文件会被跳过,
     * 所以多列无害。
     */
    static final List<String> CONFIG_FILES = List.of(
            "app.yml", "database.yml", "view.yml", "jaravel.yml",
            "auth.yml", "cache.yml", "session.yml", "hashing.yml", "queue.yml",
            "events.yml", "schedule.yml", "mail.yml", "notifications.yml",
            "filesystems.yml");

    // ================= 容器与应用 =================

    @Bean
    @ConditionalOnMissingBean
    public Container jaravelContainer(ConfigurableApplicationContext context) {
        var container = new SpringContainer(context);
        Application.bootstrap(container);
        return container;
    }

    // ================= 配置 =================

    @Bean
    @ConditionalOnMissingBean
    public ConfigRepository jaravelConfig(Environment environment) {
        // config/*.yml 优先从文件系统读(开发期可改),回落到 classpath(打包后)
        Path configDir = Path.of("src", "main", "resources", "config");
        Map<String, Object> tree = java.nio.file.Files.isDirectory(configDir)
                ? ConfigLoader.fromDirectory(configDir)
                : ConfigLoader.fromClasspath(getClass().getClassLoader(), CONFIG_FILES);

        // ${VAR} 的解析委托给 Spring 的 Environment —— 这样 .env、系统属性、
        // 环境变量、application.yml 全都能作为来源,无需各自实现一遍
        var config = new MapConfigRepository(tree, environment::getProperty);

        Application.setEnvironment(config.string("app.env", "production"));
        Application.setDebug(config.bool("app.debug", false));
        return config;
    }

    // ================= 数据库 =================

    @Bean
    @ConditionalOnMissingBean
    public Grammar jaravelGrammar(ConfigRepository config) {
        String driver = config.string("database.default", "sqlite");
        return switch (driver) {
            case "mysql", "mariadb" -> new MySqlGrammar();
            case "postgres", "postgresql", "pgsql" -> new PostgresGrammar();
            case "sqlite" -> new SqliteGrammar();
            default -> throw new IllegalStateException(
                    "未知的数据库驱动 \"" + driver + "\"(database.default)。"
                  + "\n  v0.1 支持:mysql / postgres / sqlite"
                  + "\n  其他方言可实现 Grammar 并注册为 @Bean");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public Connection jaravelConnection(ObjectProvider<DataSource> dataSource, Grammar grammar) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException(
                    "没有可用的 DataSource。"
                  + "\n  修复:在 .env 或 application.yml 中配置 spring.datasource.url,"
                  + "\n        或为纯无库应用排除 jaravel-database。");
        }
        return new JdbcConnection(ds, grammar);
    }

    /**
     * 把连接交给三个按静态门面工作的子系统。
     *
     * <p>Eloquent 的 {@code Todo.find(1)}、Schema 的 {@code Schema.create(...)}、
     * 以及 {@code unique}/{@code exists} 规则都是静态入口 —— 这是 Laravel 式
     * 开发体验的核心(没人想为了查一条记录先注入一个 Repository),代价是
     * 需要在启动时把连接放进一个全局位置。
     *
     * <p>这里是**唯一**做这件事的地方,所以「全局状态从哪来」是可追溯的;
     * 测试里换连接也只需调同样的三个方法。
     */
    @Bean
    public JaravelStaticWiring jaravelStaticWiring(Connection connection, ConfigRepository config) {
        io.jaravel.eloquent.Eloquent.useConnection(connection);
        io.jaravel.migration.schema.Schema.useConnection(connection);
        io.jaravel.validation.DatabaseRule.useConnection(connection);

        // N+1 第二道防线。默认跟随 debug 模式:开发时任何未预加载的访问都
        // 立刻报错,上线后退化为静默懒加载(宁可慢也不要 500)。
        // 想在 CI 里强制开启就显式配置它 —— 那是证明「没有隐藏 N+1」的唯一方式。
        if (config.has("jaravel.eloquent.prevent-lazy-loading")) {
            io.jaravel.eloquent.Eloquent.preventLazyLoading(
                    config.bool("jaravel.eloquent.prevent-lazy-loading"));
        }
        return new JaravelStaticWiring();
    }

    /**
     * 迁移执行器。
     *
     * <p>依赖 {@link JaravelStaticWiring} 是必要的:迁移里的 {@code Schema.create}
     * 走静态门面,必须等连接注入完成。
     */
    @Bean
    @ConditionalOnMissingBean
    public MigrationRunner jaravelMigrationRunner(
            Connection connection, ConfigRepository config, JaravelStaticWiring wiring) {
        var runner = new MigrationRunner(connection, config);
        runner.runIfEnabled();
        runner.seedIfEnabled();
        return runner;
    }

    /**
     * 一个空标记 bean。
     *
     * <p>存在的意义是让上面的注入成为**显式的依赖节点**:需要在连接就绪后
     * 才能工作的 bean 可以依赖它,而不是靠 bean 初始化顺序碰运气。
     */
    public static final class JaravelStaticWiring {
    }

    // ================= 视图 =================

    @Bean
    @ConditionalOnMissingBean
    public ViewEngine jaravelViewEngine(ConfigRepository config) {
        Path viewRoot = Path.of(config.string("view.root", "src/main/resources/views"));
        Path classDir = Path.of(config.string("view.compiled", "target/jblade-classes"));

        // 开发期从目录读(改模板即生效);目录不存在则从 classpath 读(打包后)
        return java.nio.file.Files.isDirectory(viewRoot)
                ? JbladeEngine.forDirectory(viewRoot, classDir, getClass().getClassLoader())
                : JbladeEngine.forClasspath("views", classDir, getClass().getClassLoader());
    }

    // ================= 路由 =================

    @Bean
    @ConditionalOnMissingBean
    public RouteCollection jaravelRoutes(ObjectProvider<RouteDefinition> definitions) {
        var router = new Router();

        // 按 order 再按类名排序,保证注册顺序可重现 ——
        // 路由顺序影响动态路由的匹配优先级,不能随扫描顺序浮动
        List<RouteDefinition> sorted = new ArrayList<>(definitions.stream().toList());
        sorted.sort(Comparator.comparingInt(RouteDefinition::order)
                              .thenComparing(d -> d.getClass().getName()));

        if (sorted.isEmpty()) {
            System.err.println("[jaravel] 警告:没有找到任何 RouteDefinition 实现。"
                    + "新建一个 routes/WebRoutes.java 并加上 @Component。");
        }
        for (RouteDefinition d : sorted) d.define(router);

        return router.compile();
    }

    @Bean
    public JaravelHandlerMapping jaravelHandlerMapping(RouteCollection routes) {
        return new JaravelHandlerMapping(routes);
    }

    @Bean
    @ConditionalOnMissingBean
    public JaravelExceptionHandler jaravelExceptionHandler() {
        return new JaravelExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseWriter jaravelResponseWriter(ViewEngine views, ObjectProvider<ObjectMapper> json,
                                                ConfigRepository config) {
        var mapper = json.getIfAvailable(ObjectMapper::new);
        // Secure cookie 默认开启,仅 local 环境关闭 —— 默认安全,显式才放松
        boolean secure = !"local".equals(config.string("app.env", "production"));
        return new ResponseWriter(views, mapper, secure);
    }

    /**
     * HTTP 内核。
     *
     * <p>用户不提供就用 {@link DefaultKernel} —— 它预置了 web/api 两组
     * 与 auth / can / throttle 等标准别名,所以「不写 Kernel」时
     * {@code middleware("auth")} 也能用。见 DefaultKernel 的类注释里
     * 「为什么 install:auth 不需要生成一份 Kernel」。
     */
    @Bean
    @ConditionalOnMissingBean
    public io.jaravel.http.Kernel jaravelKernel() {
        return new DefaultKernel();
    }

    @Bean
    @ConditionalOnMissingBean
    public io.jaravel.http.MiddlewareStack jaravelMiddlewareStack(
            io.jaravel.http.Kernel kernel, Container container) {
        // 中间件经容器创建,因此可以构造注入(认证中间件需要 Guard、
        // 限流中间件需要 Cache)
        return new io.jaravel.http.MiddlewareStack(kernel,
                io.jaravel.http.MiddlewareStack.fromContainer(container::make));
    }

    @Bean
    public JaravelHandlerAdapter jaravelHandlerAdapter(Container container,
                                                       io.jaravel.http.MiddlewareStack stack,
                                                       ResponseWriter writer,
                                                       JaravelExceptionHandler handler) {
        return new JaravelHandlerAdapter(container,
                JaravelHandlerAdapter.MiddlewareResolver.of(stack), writer, handler);
    }
}
