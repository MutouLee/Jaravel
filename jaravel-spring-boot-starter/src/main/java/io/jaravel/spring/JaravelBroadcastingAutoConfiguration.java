package io.jaravel.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.config.annotation.DelegatingWebSocketConfiguration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

import io.jaravel.auth.AuthManager;
import io.jaravel.broadcasting.Broadcast;
import io.jaravel.broadcasting.BroadcastChannels;
import io.jaravel.broadcasting.BroadcastManager;
import io.jaravel.broadcasting.ChannelRegistry;
import io.jaravel.broadcasting.EventBusBroadcaster;
import io.jaravel.broadcasting.channel.BroadcastChannel;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.events.EventBus;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.session.SessionConfig;
import io.jaravel.session.SessionStore;
import io.jaravel.spring.broadcast.JaravelWebSocketHandler;
import io.jaravel.spring.broadcast.LocalWsHub;
import io.jaravel.spring.broadcast.RedisWsHub;
import io.jaravel.spring.broadcast.UserResolvingInterceptor;
import io.jaravel.support.Arr;

/**
 * 广播的自动装配(docs/19 §6.1)。
 *
 * <pre>{@code
 * broadcast:
 *   driver: ws                 # null | log | ws | redis
 *   app-key: jaravel
 *   origins: []                # 空 = 仅同源(Spring 的 setAllowedOrigins 语义)
 *   activity-timeout: 120s
 *   redis:
 *     uri: redis://localhost:6379/0
 *     prefix: jaravel:broadcast:
 *   presence-ttl: 5m           # 必须大于 activity-timeout
 * }</pre>
 *
 * <h2>三条接线,全是「装钩子」</h2>
 * <p>事件广播:给 {@link EventBus} 装上 {@link EventBusBroadcaster};
 * 通知渠道:提供一个 {@link NotificationChannel} bean,由
 * {@code JaravelNotificationsAutoConfiguration} 的
 * {@code ObjectProvider} 拾取;ws 驱动:注册 WebSocket 端点。
 * 广播模块自己不创建任何东西 —— 它不知道 Spring 的存在。
 */
@AutoConfiguration(after = {JaravelAutoConfiguration.class,
        JaravelEventsAutoConfiguration.class, JaravelNotificationsAutoConfiguration.class})
// ★ Spring Boot 3.4 移除了对 DelegatingWebSocketConfiguration 的自动导入 ——
// WebSocketConfigurer bean 不再被自动收集(实测:端点 404)。
// Jaravel 的用户不该为此写 @EnableWebSocket,所以框架自己把它导回来。
@Import(DelegatingWebSocketConfiguration.class)
public class JaravelBroadcastingAutoConfiguration {

    /** 多实例广播需要 Lettuce(Lettuce 是 starter 的 optional 依赖) */
    private static final boolean LETTUCE_PRESENT = org.springframework.util.ClassUtils
            .isPresent("io.lettuce.core.RedisClient",
                       JaravelBroadcastingAutoConfiguration.class.getClassLoader());

    /**
     * 广播管理器。三条路径(事件 / 通知 / emit)都汇到它
     * 的 {@code publish} 上(docs/19 §6.6)。
     *
     * <p>driver 的校验在**这里**做而不是条件注解 —— Jaravel 的配置
     * 来自 {@code config/*.yml},不在 Spring 的 Environment 里,
     * 条件注解看不到它(与 auth 的 PasswordBroker 同一理由)。
     */
    @Bean
    @ConditionalOnMissingBean
    public BroadcastManager jaravelBroadcastManager(ConfigRepository config,
                                                    ObjectProvider<LocalWsHub> wsHub) {
        var manager = new BroadcastManager();
        String driver = config.string("broadcast.driver", "null");

        switch (driver) {
            case "null" -> manager.hub(new BroadcastManager.NullBroadcaster());
            case "log" -> manager.hub(new BroadcastManager.LogBroadcaster());
            case "ws" -> manager.hub(wsHub.getIfAvailable());
            case "redis" -> {
                LocalWsHub hub = wsHub.getIfAvailable();
                if (!(hub instanceof io.jaravel.spring.broadcast.DistributedHub)) {
                    throw new IllegalStateException("""
                            broadcast.driver = "redis" 需要分布式枢纽,但当前的 LocalWsHub
                            不是它(可能被自定义 bean 覆盖了)。提供一个 RedisWsHub,
                            或改回 driver: ws。""");
                }
                manager.hub(hub);
            }
            default -> throw new IllegalStateException("""
                    未知的 broadcast.driver "%s"。%s
                      可选:null(丢弃,debug 下警告)/ log / ws / redis(多实例)"""
                    .formatted(driver,
                               Arr.closest(driver, List.of("null", "log", "ws", "redis"))
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse("")));
        }

        Broadcast.use(manager);
        return manager;
    }

    /** 事件总线 → 广播的桥。装上即生效,事件模块零感知 */
    @Bean
    public EventBusBroadcaster jaravelEventBroadcaster(EventBus bus,
                                                       BroadcastManager manager) {
        var broadcaster = new EventBusBroadcaster(manager);
        bus.broadcaster(broadcaster);
        return broadcaster;
    }

    /**
     * 频道注册表。应用的 {@code Channels implements BroadcastChannels}
     * 注册成 Spring bean 后在这里被发现(与 RouteDefinition 同一机制)。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChannelRegistry jaravelChannelRegistry(ObjectProvider<BroadcastChannels> definitions) {
        var registry = new ChannelRegistry();
        definitions.orderedStream().forEach(d -> d.define(registry));
        return registry;
    }

    /** 通知 broadcast 渠道 —— 由通知装配的 ObjectProvider 拾取 */
    @Bean
    public BroadcastChannel jaravelBroadcastChannel(BroadcastManager manager) {
        return new BroadcastChannel(manager);
    }

    // ================= ws 驱动 =================

    /**
     * 连接/频道登记枢纽。driver 不是 ws / redis 时只是闲置。
     *
     * <p>redis 驱动返回 {@link RedisWsHub}(继承本地枢纽,只把 publish
     * 与 presence 换成 Redis);连接在启动时建立 —— 多实例广播没有
     * 「只发不收」的用法,订阅必须从启动起就生效。连不上就响亮失败。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public LocalWsHub jaravelLocalWsHub(ConfigRepository config) {
        if (!"redis".equals(config.string("broadcast.driver", "null"))) {
            return new LocalWsHub();
        }
        if (!LETTUCE_PRESENT) {
            throw new IllegalStateException("""
                    broadcast.driver = "redis" 需要 Lettuce,但它不在 classpath 上。
                      加这段到 pom:
                        <dependency>
                          <groupId>io.lettuce</groupId>
                          <artifactId>lettuce-core</artifactId>
                        </dependency>
                      或改回 broadcast.driver: ws(单实例)。""");
        }
        return RedisHubFactory.create(config);
    }

    /**
     * 单独一层,避免 driver: ws 的应用在类加载时解析 {@code RedisWsHub}
     * 的 Lettuce 类型(它只在这个方法被调用时加载)。
     */
    private static final class RedisHubFactory {
        static LocalWsHub create(ConfigRepository config) {
            return new RedisWsHub(
                    config.string("broadcast.redis.uri", "redis://localhost:6379/0"),
                    config.string("broadcast.redis.prefix", RedisWsHub.DEFAULT_PREFIX),
                    config.duration("broadcast.presence-ttl", java.time.Duration.ofMinutes(5)));
        }
    }

    /**
     * 注册 WebSocket 端点。driver 不是 ws / redis 时返回 null —— bean 被跳过,
     * 端点不暴露(与 PasswordBroker 的「没配就 null」同一模式)。
     */
    @Bean
    public WebSocketConfigurer jaravelWebSocketConfigurer(
            ConfigRepository config, LocalWsHub hub, ChannelRegistry registry,
            ObjectProvider<SessionStore> sessionStore,
            ObjectProvider<SessionConfig> sessionConfig,
            ObjectProvider<AuthManager> authManager) {

        String driver = config.string("broadcast.driver", "null");
        if (!"ws".equals(driver) && !"redis".equals(driver)) return null;

        String appKey = config.string("broadcast.app-key", "jaravel");
        var handler = new JaravelWebSocketHandler(hub, registry,
                config.duration("broadcast.activity-timeout",
                        java.time.Duration.ofSeconds(120)).toSeconds());

        // 会话/认证没装(被排除)时:端点仍可用,只是所有连接都是 guest
        var userInterceptor = new UserResolvingInterceptor(
                sessionStore.getIfAvailable(),
                sessionConfig.getIfAvailable(() -> SessionConfig.defaults()),
                authManager.getIfAvailable(),
                config.string("broadcast.guard",
                        authManager.getIfAvailable() == null
                                ? "web" : authManager.getIfAvailable().defaultGuardName()),
                appKey);

        List<String> origins = config.stringList("broadcast.origins");

        return ws -> {
            var registration = ws.addHandler(handler, "/app/{appKey}")
                    .addInterceptors(userInterceptor);
            // 空 = 不设置 → Spring 默认仅同源(比「全放行」安全)
            if (origins != null && !origins.isEmpty()) {
                registration.setAllowedOrigins(origins.toArray(new String[0]));
            }
        };
    }
}
