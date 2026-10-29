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
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public class JaravelAutoConfiguration {

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
                : ConfigLoader.fromClasspath(getClass().getClassLoader(),
                        List.of("app.yml", "database.yml", "view.yml"));

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

    @Bean
    public JaravelHandlerAdapter jaravelHandlerAdapter(Container container,
                                                       ObjectProvider<Middleware> middleware,
                                                       ResponseWriter writer,
                                                       JaravelExceptionHandler handler) {
        var global = middleware.stream().toList();
        return new JaravelHandlerAdapter(container,
                JaravelHandlerAdapter.MiddlewareResolver.of(global), writer, handler);
    }
}
