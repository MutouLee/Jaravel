package io.jaravel.spring;

import io.jaravel.auth.Guard;
import io.jaravel.auth.UserProvider;
import io.jaravel.core.config.ConfigRepository;

/**
 * guard 驱动的扩展点。
 *
 * <p>{@code config/auth.yml} 里的 {@code driver:} 值对应一个驱动。
 * 内置四个({@code session} / {@code token} / {@code jwt} / {@code basic}),
 * 其余由实现本接口的 {@code @Bean} 提供。
 *
 * <pre>{@code
 * @Bean
 * GuardDriver ldapGuardDriver() {
 *     return GuardDriver.named("ldap",
 *             (name, provider, config) -> new LdapGuard(name, provider));
 * }
 * }</pre>
 *
 * <h2>为什么要有这个接口</h2>
 * <p>{@code jaravel-security-bridge} 需要注册 {@code driver: spring-security},
 * 但 starter 不能依赖它(那会让每个项目都拉进 Spring Security)。
 * 反过来让 starter 用反射去探测也不好 —— 拼错类名只会得到
 * 「未知的 driver」而不是真正的原因。
 *
 * <p>于是方向反过来:桥接模块依赖 starter,贡献一个 {@code GuardDriver} bean。
 * starter 只认这个接口,对实现方一无所知。
 */
public interface GuardDriver {

    /** 与 {@code auth.guards.<name>.driver} 比对的值 */
    String driver();

    /**
     * 造一个 guard。
     *
     * @param guardName guard 的名字(不是驱动名)
     * @param provider  配置里指定的 user provider,已解析好
     * @param config    全局配置 —— 驱动自己的选项通常在
     *                  {@code auth.guards.<guardName>.*} 下
     */
    Guard create(String guardName, UserProvider provider, ConfigRepository config);

    interface Factory {
        Guard create(String guardName, UserProvider provider, ConfigRepository config);
    }

    static GuardDriver named(String driver, Factory factory) {
        return new GuardDriver() {
            @Override public String driver() { return driver; }

            @Override
            public Guard create(String guardName, UserProvider provider,
                                ConfigRepository config) {
                return factory.create(guardName, provider, config);
            }
        };
    }
}
