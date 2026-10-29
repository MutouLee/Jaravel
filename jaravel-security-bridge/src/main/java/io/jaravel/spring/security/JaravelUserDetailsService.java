package io.jaravel.spring.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.UserProvider;

/**
 * 让 Spring Security 的 Filter 链用 Jaravel 的用户表。
 *
 * <pre>{@code
 * @Bean
 * UserDetailsService userDetailsService() {
 *     return new JaravelUserDetailsService(Auth.manager().provider("users"));
 * }
 *
 * @Bean
 * PasswordEncoder passwordEncoder() {
 *     return new JaravelPasswordEncoder(Hash.manager());   // 共用一套哈希配置
 * }
 * }</pre>
 *
 * <p>这个方向的用途:项目需要 Spring Security 的某个具体能力
 * (OAuth2 Resource Server、SAML、method security、企业 SSO),
 * 但不想再维护第二份用户表和第二套密码哈希配置。
 *
 * <h2>为什么必须和 {@link JaravelPasswordEncoder} 一起用</h2>
 * <p>Jaravel 默认用 PBKDF2-HMAC-SHA512,而 Spring 的
 * {@code DaoAuthenticationProvider} 默认 encoder 是 bcrypt。只换
 * UserDetailsService 不换 encoder,会让所有现有用户的密码一律校验失败 ——
 * 而错误信息只是「用户名或密码错误」,查起来相当痛苦。
 */
public class JaravelUserDetailsService implements UserDetailsService {

    private final UserProvider provider;
    private final String column;
    private final Function<Authenticatable, Collection<? extends GrantedAuthority>>
            authorityMapper;
    private final Predicate<Authenticatable> enabled;

    public JaravelUserDetailsService(UserProvider provider) {
        this(provider, "email", user -> List.of(), user -> true);
    }

    /**
     * @param column          provider 里用来匹配用户名的列
     * @param authorityMapper 模型 → Spring 权限。**默认返回空集合** ——
     *                        见 {@link #noAuthoritiesByDefault()}
     * @param enabled         账号是否可用
     */
    public JaravelUserDetailsService(
            UserProvider provider, String column,
            Function<Authenticatable, Collection<? extends GrantedAuthority>> authorityMapper,
            Predicate<Authenticatable> enabled) {
        this.provider = provider;
        this.column = column;
        this.authorityMapper = authorityMapper;
        this.enabled = enabled;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("用户名为空");
        }

        Authenticatable user = provider
                .retrieveByCredentials(Map.of(column, username))
                .orElseThrow(() -> new UsernameNotFoundException(
                        // 不回显 username:这条异常消息可能进日志,
                        // 而日志里堆满待枚举的邮箱不是好事
                        "在 provider \"" + provider.name() + "\" 的 " + column
                      + " 列上找不到该用户"));

        return new JaravelUserDetails(user, username,
                                      authorityMapper.apply(user), enabled.test(user));
    }

    /**
     * 为什么默认不给任何权限。
     *
     * <p>Jaravel 的授权模型是 Gate / Policy,用户表里通常**没有** roles 列。
     * 凭空补一个 {@code ROLE_USER} 会让 Spring 侧的
     * {@code hasRole("USER")} 静默通过 —— 那是一条没人写过、也没人测过的
     * 授权规则,却生效了。
     *
     * <p>需要权限时显式传 {@code authorityMapper},从你自己的 roles 表读。
     */
    static String noAuthoritiesByDefault() {
        return "见 Javadoc";
    }

    public UserProvider provider() {
        return provider;
    }
}
