package net.dobeen.jaravel.spring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.core.config.ConfigLoader;
import net.dobeen.jaravel.core.config.ConfigRepository;
import net.dobeen.jaravel.core.config.MapConfigRepository;
import net.dobeen.jaravel.core.container.Container;
import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.database.JdbcConnection;
import net.dobeen.jaravel.database.grammar.Grammar;
import net.dobeen.jaravel.database.grammar.MySqlGrammar;
import net.dobeen.jaravel.database.grammar.PostgresGrammar;
import net.dobeen.jaravel.database.grammar.SqliteGrammar;
import net.dobeen.jaravel.http.Middleware;
import net.dobeen.jaravel.routing.RouteCollection;
import net.dobeen.jaravel.routing.RouteDefinition;
import net.dobeen.jaravel.routing.Router;
import net.dobeen.jaravel.view.JbladeEngine;
import net.dobeen.jaravel.core.lang.LangLoader;
import net.dobeen.jaravel.view.ViewEngine;
import net.dobeen.jaravel.view.ViewRequestState;

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
            "filesystems.yml", "uploads.yml", "api.yml", "cors.yml",
            "broadcasting.yml");

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
        applyOverrides(tree, environment);
        var config = new MapConfigRepository(tree, environment::getProperty);

        Application.setEnvironment(config.string("app.env", "production"));
        Application.setDebug(config.bool("app.debug", false));
        return config;
    }

    /**
     * Spring 属性对 {@code config/*.yml} 的**显式覆盖**。
     *
     * <p>目前只有两个键,而且都是测试基础设施需要的:
     * <ul>
     *   <li>{@code jaravel.database.default} —— Testcontainers 把测试库换成
     *       MySQL/Postgres 时,Grammar 也必须跟着换。只改
     *       {@code spring.datasource.url} 会得到「MySQL 上跑 SQLite 语法」;</li>
     *   <li>{@code jaravel.migrate.on-boot} —— 新库是空的,必须在上下文启动时迁移。</li>
     * </ul>
     *
     * <p>只允许这两个键,不做通用覆盖:配置优先级越隐晦,排查越贵。
     */
    static void applyOverrides(Map<String, Object> tree, Environment environment) {
        override(tree, "database.default", environment.getProperty("jaravel.database.default"));
        override(tree, "jaravel.migrate.on-boot", environment.getProperty("jaravel.migrate.on-boot"));
    }

    private static void override(Map<String, Object> tree, String path, String value) {
        if (value == null || value.isBlank()) return;

        String[] parts = path.split("\\.");
        Map<String, Object> node = tree;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = node.get(parts[i]);
            if (!(child instanceof Map<?, ?>)) {
                var created = new LinkedHashMap<String, Object>();
                node.put(parts[i], created);
                node = created;
            } else {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) child;
                node = map;
            }
        }
        node.put(parts[parts.length - 1], value);
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
        net.dobeen.jaravel.eloquent.Eloquent.useConnection(connection);
        net.dobeen.jaravel.migration.schema.Schema.useConnection(connection);
        net.dobeen.jaravel.validation.DatabaseRule.useConnection(connection);

        // N+1 第二道防线。默认跟随 debug 模式:开发时任何未预加载的访问都
        // 立刻报错,上线后退化为静默懒加载(宁可慢也不要 500)。
        // 想在 CI 里强制开启就显式配置它 —— 那是证明「没有隐藏 N+1」的唯一方式。
        if (config.has("jaravel.eloquent.prevent-lazy-loading")) {
            net.dobeen.jaravel.eloquent.Eloquent.preventLazyLoading(
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

    // ================= i18n =================

    /**
     * 消息目录(docs/02 §2.3)。
     *
     * <p>locale 与 fallback 来自 {@code app.locale} / {@code app.fallback-locale};
     * 消息从两处装:**classpath 的 {@code lang/}** 与**磁盘的
     * {@code resources/lang/}**,磁盘后装所以同名键覆盖 jar 里的 ——
     * 开发时改一条消息不必重新打包。
     *
     * <p>jar 里无法枚举目录,所以 classpath 那一侧按约定读
     * {@code lang/<locale>/_groups.yml} 里列出的分组(见
     * {@link LangLoader#groupsOnClasspath})。没有那个文件就只用磁盘那份 ——
     * 开发期最常见的情形,不该因此报错。
     *
     * <p>一条消息都没有也**不报错**:绝大多数应用是单语言的,
     * 它们不该为了渲染页面先建一个 lang 目录。那时 {@code @lang('k')}
     * 输出的是键本身(见 {@code Lang} 的类注释)。
     */
    @Bean
    @ConditionalOnMissingBean
    public net.dobeen.jaravel.core.lang.Translator jaravelTranslator(ConfigRepository config) {
        String locale = config.string("app.locale", "zh_CN");
        String fallback = config.string("app.fallback-locale", "en");
        var translator = new net.dobeen.jaravel.core.lang.Translator(locale, fallback);

        var loader = getClass().getClassLoader();
        var locales = java.util.LinkedHashSet.<String>newLinkedHashSet(2);
        locales.add(locale);
        locales.add(fallback);

        for (String name : locales) {
            var groups = LangLoader.groupsOnClasspath(loader, name);
            if (groups.isEmpty()) continue;
            LangLoader.fromClasspath(loader, java.util.List.of(name), groups)
                    .forEach(translator::put);
        }

        Path langRoot = Path.of(config.string("lang.root", "src/main/resources/lang"));
        LangLoader.fromDirectory(langRoot).forEach(translator::put);

        net.dobeen.jaravel.core.lang.Lang.use(translator);
        return translator;
    }

    // ================= 视图 =================

    /**
     * 把 {@code @csrf} / {@code @error} / {@code @old} 接到会话上。
     *
     * <h2>★ 为什么是一个 SPI,而不是让 jaravel-view 直接读会话</h2>
     * <p>这三个指令要的数据都在会话里(令牌、上一次请求闪存的
     * errors 与 old),但 {@code jaravel-view} 不能依赖
     * {@code jaravel-session} —— 一个只渲染邮件模板的应用不该为此背上
     * 会话栈,而 ArchUnit 的 {@code viewIsIndependentOfWeb} 钉着这条边界。
     *
     * <p>所以视图那边留了 {@link ViewRequestState.Source},这里装上实现。
     * 装的是**长期有效的实现**而不是一份快照:每次调用都去读当时的
     * 请求作用域,否则第二个请求会读到第一个请求的令牌。
     *
     * <h2>★ 为什么没有 ShareErrors 中间件</h2>
     * <p>Laravel 有一个 {@code ShareErrorsFromSession} 中间件,把
     * {@code $errors} 塞进每个视图的数据里。这里**不需要**它:
     * 指令自己去读会话,于是模板不必声明 {@code @params(String error)},
     * 控制器也不必把 error / old 一个个 {@code .with(...)} 传进去
     * (那正是 {@code install:auth} 生成的代码此前在做的事)。
     *
     * <p>少一个中间件也就少一处「忘了注册 web 组时 @error 静默为空」
     * 的失败形态。
     */
    @Bean
    @ConditionalOnMissingBean(ViewRequestState.Source.class)
    public ViewRequestState.Source jaravelViewRequestState() {
        var source = new SessionViewRequestState();
        ViewRequestState.use(source);
        return source;
    }

    /**
     * 从当前请求的会话里读 CSRF 令牌、校验错误与旧输入。
     *
     * <p>错误的形状是校验失败时闪存进去的那个 map
     * (见 {@code JaravelExceptionHandler.renderValidationFailure}):
     * {@code Map<String, List<String>>}。这里**两种形状都认** ——
     * 应用自己 {@code .with("errors", ...)} 时传一个
     * {@code Map<String, String>} 是很自然的写法,而为此让 @error
     * 静默什么都不显示是最糟的结果。
     */
    static final class SessionViewRequestState implements ViewRequestState.Source {

        @Override
        public String csrfToken() {
            return net.dobeen.jaravel.session.Session.tryCurrent()
                    .map(net.dobeen.jaravel.session.SessionData::token)
                    .orElseThrow(() -> new IllegalStateException("""
                            @csrf 需要一个会话,而这个请求没有。
                              原因通常是路由不在 web 中间件组里 ——
                              会话由 StartSession 建立,而它在 web 组里。
                              API 路由不需要 CSRF(它用令牌认证),那里不该写 @csrf。"""));
        }

        @Override
        public java.util.List<String> errors(String field) {
            Object bag = net.dobeen.jaravel.session.Session.tryCurrent()
                    .flatMap(s -> s.get("errors", Object.class))
                    .orElse(null);
            if (!(bag instanceof java.util.Map<?, ?> map)) return java.util.List.of();

            Object messages = map.get(field);
            if (messages == null) return java.util.List.of();
            if (messages instanceof java.util.Collection<?> many) {
                return many.stream().map(String::valueOf).toList();
            }
            return java.util.List.of(String.valueOf(messages));
        }

        @Override
        public java.util.Optional<Object> old(String field) {
            return net.dobeen.jaravel.session.Session.tryCurrent()
                    .flatMap(session -> session.oldInput(field));
        }

        /**
         * {@code @auth} / {@code @guest}。
         *
         * <p>**取不到就是「没登录」**:没有请求作用域(邮件模板)、
         * 没装 auth 模块、guard 名写错 —— 三种情况下渲染的都是
         * {@code @guest} 那一支。
         *
         * <p>为什么不报错:一个布局模板里的 {@code @auth} 块会被所有页面
         * 共用,包括错误页。在渲染 500 错误页时因为「取不到 guard」
         * 再抛一个异常,得到的是一个无限递归的错误页 ——
         * 那比少显示一个「退出登录」链接糟糕得多。
         */
        @Override
        public boolean authenticated(String guard) {
            try {
                return guard == null
                        ? net.dobeen.jaravel.auth.Auth.check()
                        : net.dobeen.jaravel.auth.Auth.guard(guard).check();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * {@code @can} / {@code @cannot} —— 走 {@code Gate},与
         * {@code middleware("can:update,note")} 以及控制器里的
         * {@code Gate.authorize(...)} 是同一条路径(同一个 Policy)。
         *
         * <p>**取不到就是拒绝**:授权的默认值只能是拒绝。
         * 这里同样不抛异常,理由与 {@link #authenticated} 相同 ——
         * 但方向更重要:一个在异常路径上「放过」的 @can 会把
         * 管理员按钮渲染给所有人。
         */
        @Override
        public boolean allows(String ability, Object... arguments) {
            try {
                return net.dobeen.jaravel.auth.access.Gate.allows(ability, arguments);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ViewEngine jaravelViewEngine(ConfigRepository config) {
        Path viewRoot = Path.of(config.string("view.root", "src/main/resources/views"));
        Path classDir = Path.of(config.string("view.compiled", "target/jblade-classes"));

        // 开发期从目录读(改模板即生效);打包成 jar 后即使当前工作目录
        // 里还有 src/main/resources/views,也必须走预编译 —— 自包含 jar
        // 里没有 javac,jte 的运行时编译会直接报「必须预编译」。
        boolean fromJar = runningFromJar();
        boolean precompiled = getClass().getClassLoader().getResource(
                "META-INF/jaravel/jblade-precompiled") != null;

        if (!fromJar && java.nio.file.Files.isDirectory(viewRoot)) {
            // 共享而不是每个上下文新建一个:两个引擎共用同一个 class 目录时,
            // 后建的会覆盖文件,而先建的那个 ClassLoader 还没加载惰性的内部类
            // (@fragment / slot 的匿名 Content)—— 症状是一个
            // NoClassDefFoundError,消息里只有 XxxGenerated$1。
            // 理由全文见 JbladeEngine.sharedForDirectory
            return JbladeEngine.sharedForDirectory(viewRoot, classDir,
                    getClass().getClassLoader());
        }
        if (precompiled) {
            return JbladeEngine.forPrecompiled(getClass().getClassLoader());
        }
        return JbladeEngine.forClasspath("views", classDir, getClass().getClassLoader());
    }

    /**
     * 当前代码是从 jar 里加载的吗。
     *
     * <p>不能只看 {@code src/main/resources/views} 是否存在:
     * 在项目根目录直接 {@code java -jar target/app.jar} 时那个目录也在,
     * 于是会误选开发模式,然后在自包含 jar 里尝试运行时编译模板并失败。
     */
    private static boolean runningFromJar() {
        var self = JaravelAutoConfiguration.class.getResource(
                "JaravelAutoConfiguration.class");
        return self != null && "jar".equals(self.getProtocol());
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

        // 「一条都没有」的判断要**排掉框架自带的那些**
        // (存储的临时 URL 路由就是一个 RouteDefinition bean)。
        //
        // 不排的话这个警告永远不会再触发 —— 而它要提示的情况
        // (忘了给 WebRoutes 加 @Component)依然会发生,
        // 只是症状变成了「所有路由都 404」
        boolean onlyFrameworkRoutes = sorted.stream()
                .allMatch(FrameworkRoutes.class::isInstance);
        if (onlyFrameworkRoutes) {
            System.err.println("[jaravel] 警告:没有找到任何应用的 RouteDefinition 实现。"
                    + "新建一个 routes/WebRoutes.java 并加上 @Component。");
        }
        for (RouteDefinition d : sorted) d.define(router);

        return router.compile();
    }

    /**
     * CORS(docs/12 §6.3)—— 默认**关闭**,没有 cors.paths 就不装。
     *
     * <p>CORS 是放宽同源策略。一个默认带着 {@code Allow-Origin: *} 的框架
     * 会让每个不知道这件事的项目把 API 对全世界打开 ——
     * 那是安全默认值问题,不是便利性问题。
     *
     * <p>顺序必须在 Jaravel 的分发之前(预检要绕过路由匹配,
     * 否则会撞上 405)—— 用 HIGHEST_PRECEDENCE 之后一点点,
     * 让编码过滤器仍然在最外层。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<CorsFilter>
            jaravelCorsFilter(ConfigRepository config) {

        var filter = new CorsFilter(config);
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setEnabled(filter.enabled());
        return registration;
    }

    /**
     * HTML 表单的方法伪装(docs/04 §1.8)。
     *
     * <p>顺序在路由分发之前、请求 ID 之后:它改写的是**方法**,
     * 而路由匹配按方法选路由。写成中间件会太晚 ——
     * 理由见 {@link MethodOverrideFilter} 的类注释。
     *
     * <p>永远开。它只在「POST + 表单体里有 _method」时才动手,
     * 而那个组合除了伪装没有别的含义;加一个开关只会让
     * 「我写了 @method 但不生效」多一种可能的原因。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<MethodOverrideFilter>
            jaravelMethodOverrideFilter() {

        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new MethodOverrideFilter());
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 25);
        return registration;
    }

    /** 请求 ID(docs/12 §6.4)—— 永远开:每个响应都该能被定位到一次请求 */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<RequestIdFilter>
            jaravelRequestIdFilter() {

        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new RequestIdFilter());
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * 开发期调试工具栏(docs/15 §4.3)。
     *
     * <p>默认只在 local / app.debug=true 时启用;生产要显式打开
     * {@code debug.toolbar=true} —— 它会在页面上暴露路由与内存信息。
     * 顺序在请求 ID 之后:工具栏要包住整个分发过程。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<DebugToolbarFilter>
            jaravelDebugToolbarFilter(
                    ConfigRepository config,
                    org.springframework.beans.factory.ObjectProvider<
                            net.dobeen.jaravel.database.Connection> connections) {

        var filter = new DebugToolbarFilter(config, connections.getIfAvailable());
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setEnabled(filter.enabled());
        return registration;
    }

    @Bean
    public JaravelHandlerMapping jaravelHandlerMapping(RouteCollection routes) {
        return new JaravelHandlerMapping(routes);
    }

    /**
     * 统一异常处理(docs/12 §3)。
     *
     * <p>{@code api.error-format} 选 {@code laravel}(默认)还是
     * {@code problem-json}(RFC 7807)。格式在渲染末端转换,
     * 所以抛异常的代码一个字都不用改(理由写在 {@code problemBody} 上)。
     */
    @Bean
    @ConditionalOnMissingBean
    public JaravelExceptionHandler jaravelExceptionHandler(ConfigRepository config) {
        var handler = new JaravelExceptionHandler();
        handler.useErrorFormat(config.string("api.error-format", "laravel"));
        return handler;
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
    public net.dobeen.jaravel.http.Kernel jaravelKernel() {
        return new DefaultKernel();
    }

    /**
     * 默认安全响应头(SECURITY-AUDIT §6)。
     *
     * <p>三个「不会弄坏任何东西」的头默认开({@code nosniff} /
     * {@code SAMEORIGIN} / {@code strict-origin-when-cross-origin}),
     * CSP 与 HSTS 必须显式配 —— 它们配错会让站点以很隐蔽的方式坏掉,
     * 理由写在 {@link net.dobeen.jaravel.http.middleware.SecurityHeaders} 的类注释里。
     *
     * <pre>{@code
     * # config/jaravel.yml
     * security:
     *   frame-options: DENY                      # 覆盖默认的 SAMEORIGIN
     *   csp: "default-src 'self'"                # 默认不发
     *   hsts:
     *     max-age: 15552000                      # 秒;默认不发
     *     include-subdomains: false
     * }</pre>
     */
    @Bean
    @ConditionalOnMissingBean
    public net.dobeen.jaravel.http.SecurityHeaders jaravelSecurityHeaders(ConfigRepository config) {
        var headers = new net.dobeen.jaravel.http.SecurityHeaders()
                .withCsp(config.string("jaravel.security.csp"))
                .withCspReportOnly(config.string("jaravel.security.csp-report-only"))
                .withPermissionsPolicy(config.string("jaravel.security.permissions-policy"));

        String frameOptions = config.string("jaravel.security.frame-options");
        if (frameOptions != null) headers.withFrameOptions(frameOptions);

        String referrerPolicy = config.string("jaravel.security.referrer-policy");
        if (referrerPolicy != null) headers.withReferrerPolicy(referrerPolicy);

        long hstsSeconds = config.longValue("jaravel.security.hsts.max-age", 0);
        if (hstsSeconds > 0) {
            headers.withHsts(java.time.Duration.ofSeconds(hstsSeconds),
                    config.bool("jaravel.security.hsts.include-subdomains", false),
                    config.bool("jaravel.security.hsts.preload", false));
        }
        return headers;
    }

    /**
     * 贴安全响应头的 filter。
     *
     * <p>顺序紧跟请求 ID 之后、分发之前:错误响应、静态资源、没匹配上路由的
     * 请求都要覆盖到(那正是它不能是中间件的原因,见
     * {@link SecurityHeadersFilter})。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<SecurityHeadersFilter>
            jaravelSecurityHeadersFilter(net.dobeen.jaravel.http.SecurityHeaders policy) {

        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new SecurityHeadersFilter(policy));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 22);
        return registration;
    }

    /**
     * 按请求选语言的中间件 —— 别名 {@code locale},默认**不**挂在任何组里
     * (理由见 {@link net.dobeen.jaravel.http.middleware.SetLocale} 的类注释)。
     *
     * <p>这里把「用户选过的语言」接到会话上:{@code jaravel-http} 不依赖
     * {@code jaravel-session},所以那个来源是注入进去的函数。
     */
    @Bean
    @ConditionalOnMissingBean
    public net.dobeen.jaravel.http.middleware.SetLocale jaravelSetLocale(ConfigRepository config) {
        return new net.dobeen.jaravel.http.middleware.SetLocale()
                .supporting(config.stringList("app.locales"))
                .readingSelectionFrom(key -> net.dobeen.jaravel.session.Session.isActive()
                        ? net.dobeen.jaravel.session.Session.get(key, String.class)
                        : java.util.Optional.empty());
    }

    @Bean
    @ConditionalOnMissingBean
    public net.dobeen.jaravel.http.MiddlewareStack jaravelMiddlewareStack(
            net.dobeen.jaravel.http.Kernel kernel, Container container) {
        // 中间件经容器创建,因此可以构造注入(认证中间件需要 Guard、
        // 限流中间件需要 Cache)
        return new net.dobeen.jaravel.http.MiddlewareStack(kernel,
                net.dobeen.jaravel.http.MiddlewareStack.fromContainer(container::make));
    }

    @Bean
    public JaravelHandlerAdapter jaravelHandlerAdapter(Container container,
                                                       net.dobeen.jaravel.http.MiddlewareStack stack,
                                                       ResponseWriter writer,
                                                       JaravelExceptionHandler handler) {
        return new JaravelHandlerAdapter(container,
                JaravelHandlerAdapter.MiddlewareResolver.of(stack), writer, handler);
    }
}
