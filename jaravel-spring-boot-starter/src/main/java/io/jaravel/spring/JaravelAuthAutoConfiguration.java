package io.jaravel.spring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import io.jaravel.auth.Auth;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.EmailVerification;
import io.jaravel.auth.LoginThrottle;
import io.jaravel.auth.UserProvider;
import io.jaravel.auth.access.Gate;
import io.jaravel.auth.access.GateRegistry;
import io.jaravel.auth.guard.Jwt;
import io.jaravel.auth.guard.JwtGuard;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.auth.guard.TokenGuard;
import io.jaravel.auth.hash.Argon2Algorithm;
import io.jaravel.auth.hash.BcryptAlgorithm;
import io.jaravel.auth.hash.Hash;
import io.jaravel.auth.hash.HashManager;
import io.jaravel.auth.passwords.PasswordBroker;
import io.jaravel.auth.provider.EloquentUserProvider;
import io.jaravel.cache.CacheManager;
import io.jaravel.cache.RateLimiter;
import io.jaravel.cache.Repository;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.cache.store.CaffeineStore;
import io.jaravel.cache.store.DatabaseStore;
import io.jaravel.cache.store.FileStore;
import io.jaravel.cache.store.NullStore;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.crypt.Encrypter;
import io.jaravel.core.crypt.Keys;
import io.jaravel.core.crypt.UrlSigner;
import io.jaravel.database.Connection;
import io.jaravel.eloquent.Model;
import io.jaravel.session.SessionConfig;
import io.jaravel.session.SessionStore;
import io.jaravel.session.store.CacheSessionStore;
import io.jaravel.session.store.CookieStore;
import io.jaravel.session.store.MemoryStore;
import io.jaravel.support.Arr;

/**
 * 缓存、会话、哈希、认证、授权的自动装配。
 *
 * <p>与 {@link JaravelAutoConfiguration} 分开是为了让「不需要认证的应用」
 * 的启动路径上没有这些 bean —— 它们都是 {@code @ConditionalOnMissingBean},
 * 用户换掉任意一块只需提供自己的 bean。
 *
 * <h2>这是 install:auth 之后「3 行加个登录」能成立的那一层</h2>
 * <p>用户写的是 {@code config/auth.yml} 里的几行 YAML 和一个
 * {@code @AuthModel} 注解,剩下的 provider / guard / middleware 别名
 * 全部由这里装好。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
public class JaravelAuthAutoConfiguration {

    /**
     * caffeine 在不在 classpath 上。
     *
     * <p>它是 {@code jaravel-cache} 的 optional 依赖 —— 决定了默认
     * cache 驱动是什么,见 {@link #jaravelCacheManager}。
     */
    private static final boolean CAFFEINE_PRESENT = org.springframework.util.ClassUtils
            .isPresent("com.github.benmanes.caffeine.cache.Caffeine",
                       JaravelAuthAutoConfiguration.class.getClassLoader());

    // ================= 密码学原语 =================

    /**
     * {@code app.key} 解出的三个原语。
     *
     * <p>**启动即校验**:key 缺失或长度不足时直接拒绝启动,而不是等到
     * 第一个加密 cookie 或签名链接出现时才在请求里炸。后者的症状是
     * 「部署后一切正常,直到有人点了验证邮件」。
     */
    @Bean
    @ConditionalOnMissingBean
    public Encrypter jaravelEncrypter(ConfigRepository config) {
        return Encrypter.fromBase64(requireAppKey(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public UrlSigner jaravelUrlSigner(ConfigRepository config) {
        return UrlSigner.fromBase64(requireAppKey(config));
    }

    private static String requireAppKey(ConfigRepository config) {
        String key = config.string("app.key");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("""
                    app.key 未设置。会话加密、签名 URL、加密字段都依赖它。
                      修复:在 .env 里加一行
                        APP_KEY=base64:<32 字节的 base64>
                      生成:System.out.println(Encrypter.generateKey())
                      注意:换掉 app.key 会让所有已发出的签名链接与加密 cookie 立刻失效。""");
        }
        return key;
    }

    // ================= 哈希 =================

    /**
     * 密码哈希。
     *
     * <p>默认 PBKDF2-HMAC-SHA512(JDK 自带,零额外依赖)。
     * 引入 {@code spring-security-crypto} 后可在 {@code hashing.yml} 里
     * 切到 bcrypt 或 argon2id —— 注册哪些算法与**用**哪个是两件事:
     * 全部注册上,这样数据库里历史遗留的其它格式仍能校验通过
     * (迁移期必须如此,否则换算法等于让所有老用户登不进来)。
     */
    @Bean
    @ConditionalOnMissingBean
    public HashManager jaravelHashManager(ConfigRepository config) {
        var manager = new HashManager();

        int rounds = config.integer("hashing.bcrypt.rounds", 12);
        registerIfAvailable(manager, () -> new BcryptAlgorithm(rounds));
        registerIfAvailable(manager, Argon2Algorithm::new);

        String driver = config.string("hashing.driver", "pbkdf2");
        if (!manager.available().contains(driver)) {
            throw new IllegalStateException("""
                    hashing.driver = "%s" 不可用。%s
                      已注册:%s
                      bcrypt / argon2id 需要在 pom 里加 spring-security-crypto
                      (jaravel-auth 对它是 optional 依赖,默认不引入)。"""
                    .formatted(driver,
                               Arr.closest(driver, manager.available().stream().toList())
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                               manager.available()));
        }
        manager.defaultDriver(driver);

        Hash.use(manager);
        return manager;
    }

    /**
     * 注册一个可能缺少依赖的算法。
     *
     * <p>{@code spring-security-crypto} 是 optional,不在 classpath 上时
     * 构造 {@link BcryptAlgorithm} 会抛 {@link NoClassDefFoundError}。
     * 静默跳过是对的 —— 真正用到它时,上面的 {@code hashing.driver} 校验
     * 会给出带修复步骤的错误,比启动时一个 NoClassDefFoundError 清楚得多。
     */
    private static void registerIfAvailable(HashManager manager,
                                            java.util.function.Supplier<HashManager.Algorithm> factory) {
        try {
            manager.register(factory.get());
        } catch (NoClassDefFoundError | RuntimeException ignored) {
            // 依赖不在 classpath 上
        }
    }

    /** cache.default 写了 caffeine 但它不在 classpath 上时的修复提示 */
    private static final String CAFFEINE_HINT = """


                      caffeine 不在 classpath 上。加这段到 pom:
                        <dependency>
                          <groupId>com.github.ben-manes.caffeine</groupId>
                          <artifactId>caffeine</artifactId>
                        </dependency>
                      或改成 cache.default: array —— 进程内、**无上限**、
                      不会自动淘汰,只适合小数据量与测试。""";

    // ================= 缓存 =================

    @Bean
    @ConditionalOnMissingBean
    public CacheManager jaravelCacheManager(ConfigRepository config,
                                            ObjectProvider<Connection> connection) {
        var manager = new CacheManager()
                .prefix(config.string("cache.prefix", ""))
                .register("array", new ArrayStore())
                .register("null", new NullStore());

        // caffeine 是 jaravel-cache 的 optional 依赖 —— 只在它真的在
        // classpath 上时才注册。不在时「未注册」比「注册了但一取就
        // NoClassDefFoundError」清楚得多:下面的校验会给出 pom 片段
        if (CAFFEINE_PRESENT) {
            manager.register("caffeine", m ->
                    new CaffeineStore(config.longValue("cache.stores.caffeine.max-size", 10_000)));
        }

        manager.register("file", m -> new FileStore(
                Path.of(config.string("cache.stores.file.path", "target/cache"))));

        // 懒注册:只有真正取 database store 时才要求有连接 ——
        // 否则无库应用一启动就报错
        manager.register("database", m -> {
            Connection conn = connection.getIfAvailable();
            if (conn == null) {
                throw new IllegalStateException(
                        "cache 的 database 驱动需要一个 DataSource,但没有可用的连接。"
                      + "\n  改用 cache.default: caffeine,或配置 spring.datasource.url");
            }
            return new DatabaseStore(conn,
                    config.string("cache.stores.database.table", "cache"));
        });

        // 默认值随 classpath 走:有 caffeine 用它(有界、带过期),
        // 没有退回 array。**不能**硬写 caffeine —— 那会让「只想要个登录」
        // 的项目在启动时撞上一个 optional 依赖
        String defaultStore = config.string("cache.default",
                CAFFEINE_PRESENT ? "caffeine" : "array");

        if (!manager.registeredStores().contains(defaultStore)) {
            throw new IllegalStateException("""
                    cache.default = "%s" 没有注册。%s%s
                      已注册:%s
                      自定义驱动:提供一个 CacheManager @Bean,或在它上面 register(...)"""
                    .formatted(defaultStore,
                               "caffeine".equals(defaultStore) ? CAFFEINE_HINT : "",
                               Arr.closest(defaultStore,
                                           manager.registeredStores().stream().toList())
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                               manager.registeredStores()));
        }
        manager.defaultStore(defaultStore);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public Repository jaravelCache(CacheManager caches) {
        return caches.store();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter jaravelRateLimiter(Repository cache) {
        return new RateLimiter(cache);
    }

    // ================= 会话 =================

    @Bean
    @ConditionalOnMissingBean
    public SessionConfig jaravelSessionConfig(ConfigRepository config) {
        var defaults = SessionConfig.defaults();
        var result = new SessionConfig(
                config.string("session.cookie", defaults.cookieName()),
                config.duration("session.lifetime", defaults.lifetime()),
                config.bool("session.expire-on-close", defaults.expireOnClose()),
                config.string("session.path", defaults.path()),
                config.string("session.domain", defaults.domain()),
                // secure 默认跟随环境:local 之外一律 true。
                // 「默认安全,显式才放松」与 ResponseWriter 的 cookie 策略一致
                config.bool("session.secure", !"local".equals(config.string("app.env", "production"))),
                config.bool("session.http-only", defaults.httpOnly()),
                config.string("session.same-site", defaults.sameSite()),
                config.bool("session.partitioned", defaults.partitioned()));
        return result;
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore jaravelSessionStore(ConfigRepository config, CacheManager caches,
                                            Encrypter encrypter) {
        // 空白也当作「没配」—— config/session.yml 里写 ${SESSION_DRIVER:}
        // 是个常见写法(让环境变量可选),它解析出来是空串而不是 null
        String configured = config.string("session.driver", "");
        String driver = configured == null || configured.isBlank()
                ? defaultSessionDriver(config)
                : configured;
        return switch (driver) {
            case "memory", "array" -> new MemoryStore();
            case "file" -> new io.jaravel.session.store.FileSessionStore(
                    Path.of(config.string("session.files",
                                          "storage/framework/sessions")));
            case "cache", "redis", "database" -> new CacheSessionStore(
                    // session.store 指定用哪个 cache store;不写就用默认的。
                    // redis/database 都走同一条路径 —— 它们是 cache 的
                    // 驱动而不是 session 的,这样「换 session 存储」与
                    // 「换缓存存储」是同一个决定,不会出现两边配置打架
                    config.has("session.store")
                            ? caches.store(config.string("session.store"))
                            : caches.store());
            case "cookie" -> new CookieStore(encrypter);
            default -> throw new IllegalStateException("""
                    未知的 session.driver "%s"。%s
                      可选:cache / file / cookie / memory
                      redis / database 请配 cache.default,
                      session 侧写 driver: cache 即可。"""
                    .formatted(driver,
                               Arr.closest(driver,
                                           List.of("cache", "file", "cookie", "memory"))
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse("")));
        };
    }

    /**
     * 没写 {@code session.driver} 时用哪个。
     *
     * <p><b>开发模式默认 file,生产默认 cache。</b>
     *
     * <p>理由是 docs/15 §3.5:热重启会丢掉进程内状态。如果会话跟着丢,
     * **每改一行代码就要重新登录一次** —— 开发一个需要登录的页面时,
     * 这个摩擦大到足以让人放弃热重载直接用重启,那整个 L1/L3 就白做了。
     *
     * <p>生产不用 file:它不跨机器共享,多实例部署会出现
     * 「刷新页面随机掉登录」。
     */
    private static String defaultSessionDriver(ConfigRepository config) {
        return config.bool("app.debug", false) ? "file" : "cache";
    }

    // ================= 认证 =================

    /**
     * 按 {@code config/auth.yml} 装配 provider 与 guard。
     *
     * <pre>{@code
     * auth:
     *   default: web
     *   providers:
     *     users:
     *       driver: eloquent
     *       model: com.example.models.User
     *   guards:
     *     web:  { driver: session, provider: users }
     *     api:  { driver: token,   provider: users }
     * }</pre>
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthManager jaravelAuthManager(
            ConfigRepository config, HashManager hashes,
            ObjectProvider<Connection> connection,
            ObjectProvider<GuardDriver> extraDrivers,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var manager = new AuthManager();

        Map<String, Object> providers = config.section("auth.providers");
        if (providers.isEmpty()) {
            throw new IllegalStateException("""
                    auth.providers 是空的 —— 没有任何用户来源,认证无法工作。
                      在 config/auth.yml 里加:
                        auth:
                          providers:
                            users:
                              driver: eloquent
                              model: com.example.models.User
                      运行 install:auth 会生成一份带注释的完整模板。""");
        }
        providers.keySet().forEach(name ->
                manager.registerProvider(buildProvider(name, config, hashes)));

        Map<String, Object> guards = config.section("auth.guards");
        if (guards.isEmpty()) {
            throw new IllegalStateException(
                    "auth.guards 是空的。至少要有一个 guard,例如"
                  + "\n    guards:\n      web: { driver: session, provider: users }");
        }
        for (String name : guards.keySet()) {
            registerGuard(manager, name, config, connection, extraDrivers);
        }

        String defaultGuard = config.string("auth.default", guards.keySet().iterator().next());
        manager.defaultGuard(defaultGuard);

        Auth.use(manager);
        return manager;
    }

    private static UserProvider buildProvider(String name, ConfigRepository config,
                                              HashManager hashes) {
        String path = "auth.providers." + name;
        String driver = config.string(path + ".driver", "eloquent");

        if (!"eloquent".equals(driver)) {
            throw new IllegalStateException("""
                    provider "%s" 的 driver "%s" 不支持。
                      v0.3 内置:eloquent
                      其它来源(LDAP / 远程 API)请实现 UserProvider 并注册成
                      一个 AuthManager @Bean —— 见 docs/08 §3.2。"""
                    .formatted(name, driver));
        }

        String modelName = config.string(path + ".model");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException(
                    "provider \"" + name + "\" 没有指定 model。"
                  + "\n  写法:" + path + ".model: com.example.models.User");
        }

        Class<?> modelType;
        try {
            modelType = Class.forName(modelName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("""
                    找不到类 "%s"(%s.model)。
                      检查包名是否写全 —— 这里需要完全限定名,不是 User 而是 com.example.models.User。"""
                    .formatted(modelName, path), e);
        }
        if (!Model.class.isAssignableFrom(modelType)) {
            throw new IllegalStateException(
                    modelName + " 不是 Eloquent 模型(没有继承 Model<>),"
                  + "不能用于 driver: eloquent。");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        UserProvider provider = new EloquentUserProvider(name, (Class) modelType, hashes);
        return provider;
    }

    private static void registerGuard(AuthManager manager, String name,
                                      ConfigRepository config,
                                      ObjectProvider<Connection> connection,
                                      ObjectProvider<GuardDriver> extraDrivers) {
        String path = "auth.guards." + name;
        String driver = config.string(path + ".driver", "session");
        String providerName = config.string(path + ".provider", "users");

        switch (driver) {
            case "session" -> manager.registerGuard(name, providerName, provider ->
                    new SessionGuard(name, provider,
                            config.duration(path + ".remember", Duration.ofDays(30))));

            case "token" -> manager.registerGuard(name, providerName, provider ->
                    new TokenGuard(name, provider, requireConnection(connection, driver),
                            config.string(path + ".table", "personal_access_tokens")));

            case "jwt" -> manager.registerGuard(name, providerName, provider -> {
                var jwt = Jwt.hs256(requireAppKey(config)
                        .replaceFirst("^base64:", "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new JwtGuard(name, provider, jwt,
                        config.duration(path + ".ttl", Duration.ofHours(1)));
            });

            default -> {
                GuardDriver custom = extraDrivers.stream()
                        .filter(d -> d.driver().equals(driver))
                        .findFirst()
                        .orElseThrow(() -> unknownDriver(name, driver, extraDrivers));
                manager.registerGuard(name, providerName,
                        provider -> custom.create(name, provider, config));
            }
        }
    }

    private static Connection requireConnection(ObjectProvider<Connection> connection,
                                                String driver) {
        Connection conn = connection.getIfAvailable();
        if (conn == null) {
            throw new IllegalStateException(
                    "guard driver \"" + driver + "\" 需要数据库连接,但没有可用的 DataSource。"
                  + "\n  配置 spring.datasource.url,或改用 driver: jwt(无状态)。");
        }
        return conn;
    }

    private static IllegalStateException unknownDriver(String guardName, String driver,
                                                       ObjectProvider<GuardDriver> extras) {
        var known = new java.util.ArrayList<>(List.of("session", "token", "jwt"));
        extras.stream().map(GuardDriver::driver).forEach(known::add);

        return new IllegalStateException("""
                guard "%s" 的 driver "%s" 没有注册。%s
                  可用:%s
                  · spring-security 需要引入 jaravel-security-bridge;
                  · 自定义驱动:提供一个 GuardDriver @Bean。"""
                .formatted(guardName, driver,
                           Arr.closest(driver, known)
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           known));
    }

    // ================= 授权 =================

    /**
     * Gate,并自动注册所有 {@code @Policy} 标注的 bean。
     *
     * <p>用 {@code getBeansWithAnnotation} 而不是注入一个 {@code List<?>} ——
     * Policy 类没有共同的父类型或接口(它们的方法签名各不相同,
     * 那正是 Policy 比 {@code @PreAuthorize("...")} 字符串好用的原因),
     * 所以按类型注入拿不到它们。
     *
     * <p>注册顺序按 bean 名排序,保证启动日志与报错信息可重现。
     */
    @Bean
    @ConditionalOnMissingBean
    public GateRegistry jaravelGateRegistry(ApplicationContext context) {
        var registry = new GateRegistry();

        var policies = new java.util.TreeMap<>(
                context.getBeansWithAnnotation(io.jaravel.auth.access.Policy.class));
        policies.forEach((beanName, policy) -> {
            var annotation = org.springframework.core.annotation.AnnotationUtils
                    .findAnnotation(policy.getClass(), io.jaravel.auth.access.Policy.class);
            if (annotation == null) return;     // 不可能,但别让 NPE 掩盖真正的问题
            registry.policy(annotation.value(), policy);
        });

        Gate.use(registry);
        return registry;
    }

    // ================= 密码重置与邮箱验证 =================

    @Bean
    @ConditionalOnMissingBean
    public PasswordBroker jaravelPasswordBroker(AuthManager auth, ConfigRepository config,
                                                ObjectProvider<Connection> connection) {
        var broker = new PasswordBroker(
                auth.provider(config.string("auth.passwords.provider", "users")),
                requireConnection(connection, "password-reset"),
                config.string("auth.passwords.table", "password_reset_tokens"),
                config.duration("auth.passwords.expire", Duration.ofHours(1)),
                config.duration("auth.passwords.throttle", Duration.ofSeconds(60)));

        io.jaravel.auth.passwords.Password.use(broker);
        return broker;
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailVerification jaravelEmailVerification(UrlSigner signer,
                                                      ConfigRepository config) {
        // hash key 从 app.key 派生而不是复用它:这个 HMAC 的输出进 URL,
        // 与加密密钥分离是惯例(一个密钥只做一件事)
        byte[] hashKey = Keys.derive(requireAppKey(config), "jaravel:email-verification");
        var verification = new EmailVerification(signer, hashKey,
                config.duration("auth.verification.expire", Duration.ofHours(24)));

        io.jaravel.auth.Verification.use(verification);
        return verification;
    }

    @Bean
    @ConditionalOnMissingBean
    public LoginThrottle jaravelLoginThrottle(RateLimiter limiter, ConfigRepository config) {
        return new LoginThrottle(limiter,
                config.integer("auth.throttle.max-per-account", 5),
                config.integer("auth.throttle.max-per-ip", 20),
                config.duration("auth.throttle.window", Duration.ofMinutes(1)));
    }
}
