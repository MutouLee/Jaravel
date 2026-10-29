package io.jaravel.spring.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.access.Decision;
import io.jaravel.auth.access.GateRegistry;

/**
 * 桥接的装配入口。
 *
 * <pre>{@code
 * // 1. 让 web guard 读 Spring 的认证结果
 * SecurityBridge.registerGuard(Auth.manager(), "web", "users");
 *
 * // 2. 让 Spring 的权限能在 Gate 里用
 * SecurityBridge.registerAuthorityAbilities(Gate.registry());
 * //    之后:Gate.allows("authority", "ROLE_ADMIN")
 * //          Gate.allows("role", "ADMIN")
 * }</pre>
 *
 * <p>starter 里 {@code auth.guards.<name>.driver == "spring-security"} 时
 * 会自动调第 1 步;第 2 步是显式的,因为「把 IdP 下发的角色当作授权依据」
 * 是个安全决定,不该默认打开。
 */
public final class SecurityBridge {

    private SecurityBridge() {
    }

    /** {@code driver: spring-security} 在 config/auth.yml 里的名字 */
    public static final String DRIVER = "spring-security";

    // ================= 装配 =================

    /**
     * 注册一个读 Spring 认证结果的 guard(默认 {@code byUsername("email")} 策略)。
     */
    public static AuthManager registerGuard(AuthManager auth, String guardName,
                                            String providerName) {
        return registerGuard(auth, guardName, providerName,
                SpringSecurityGuard.PrincipalResolver.byUsername("email"), null);
    }

    public static AuthManager registerGuard(
            AuthManager auth, String guardName, String providerName,
            SpringSecurityGuard.PrincipalResolver resolver,
            AuthenticationManager authenticationManager) {

        // 工厂而非实例 —— guard 缓存「当前用户」,必须请求级。
        // 见 AuthManager 的类注释
        return auth.registerGuard(guardName, providerName,
                provider -> new SpringSecurityGuard(
                        guardName, provider, resolver, authenticationManager));
    }

    /**
     * 把 Spring 的权限接进 Gate,注册两个 ability:
     *
     * <ul>
     *   <li>{@code authority} —— 原样比对:{@code Gate.allows("authority", "SCOPE_write")}</li>
     *   <li>{@code role} —— 自动补 {@code ROLE_} 前缀:{@code Gate.allows("role", "ADMIN")}</li>
     * </ul>
     *
     * <h2>为什么 role 要自动补前缀</h2>
     * <p>Spring 把角色存成 {@code ROLE_ADMIN},但它的 {@code hasRole("ADMIN")}
     * 会自动补前缀,而 {@code hasAuthority("ADMIN")} 不会。这是 Spring Security
     * 最经典的困惑点之一。桥接层照搬这个约定,好让两边写法一致 ——
     * 自己发明第三套规则只会让人更糊。
     *
     * <h2>读的是 SecurityContextHolder,不是传进来的 user</h2>
     * <p>权限来自当前 Authentication,而 Gate 的 rule 收到的 user 可能是
     * {@code Gate.forUser(someOtherUser)} 指定的另一个人。这两者不一致时
     * 本 ability 会拒绝 —— 否则 {@code Gate.forUser(别人).allows("role","ADMIN")}
     * 会拿**当前登录者**的角色去回答关于别人的问题,那是一个静默的越权。
     */
    public static GateRegistry registerAuthorityAbilities(GateRegistry gate) {
        gate.define("authority", (user, args) -> hasAuthority(user, args, false));
        gate.define("role", (user, args) -> hasAuthority(user, args, true));
        return gate;
    }

    private static Decision hasAuthority(Optional<Authenticatable> user,
                                         List<Object> args, boolean rolePrefix) {
        if (args.isEmpty() || args.get(0) == null) {
            return Decision.deny("""
                    ability "%s" 需要一个参数:要检查的%s。
                      写法:Gate.allows("%s", "%s")"""
                    .formatted(rolePrefix ? "role" : "authority",
                               rolePrefix ? "角色名" : "权限名",
                               rolePrefix ? "role" : "authority",
                               rolePrefix ? "ADMIN" : "ROLE_ADMIN"));
        }

        String wanted = String.valueOf(args.get(0));
        if (rolePrefix && !wanted.startsWith("ROLE_")) {
            wanted = "ROLE_" + wanted;
        }

        if (user.isEmpty()) return Decision.deny("未登录");

        Set<String> held = authoritiesOf(user.get());
        if (held.contains(wanted)) return Decision.allow();

        return Decision.deny("缺少权限 " + wanted
                + (held.isEmpty() ? "(该主体没有任何 Spring 权限)" : ",持有:" + held));
    }

    /**
     * 取某个主体的 Spring 权限。
     *
     * <p>只在它确实是 Spring 侧主体时返回 —— 见
     * {@link #registerAuthorityAbilities} 注释的第二节。
     */
    public static Set<String> authoritiesOf(Authenticatable user) {
        if (user instanceof SpringPrincipal principal) {
            return principal.authorities();
        }
        if (user instanceof JaravelUserDetails details) {
            return names(details.getAuthorities());
        }

        // 不是 Spring 侧的主体 —— 比对当前 Authentication 是不是同一个人,
        // 是才给它的权限
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return Set.of();

        Object current = authentication.getPrincipal();
        boolean samePerson = current instanceof Authenticatable other
                && java.util.Objects.equals(other.getAuthIdentifier(),
                                            user.getAuthIdentifier());
        if (!samePerson) {
            samePerson = java.util.Objects.equals(
                    authentication.getName(), String.valueOf(user.getAuthIdentifier()));
        }

        return samePerson ? names(authentication.getAuthorities()) : Set.of();
    }

    private static Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        var result = new java.util.LinkedHashSet<String>();
        for (GrantedAuthority authority : authorities) {
            result.add(authority.getAuthority());
        }
        return result;
    }

    // ================= 测试辅助 =================

    /**
     * 测试里直接设定 Spring 的当前用户。
     *
     * <p>{@link SpringSecurityGuard#setUser} 刻意抛异常(见那边的注释),
     * 所以测试要走这里。它写的是 {@link SecurityContextHolder} ——
     * 也就是这套配置下真正的事实来源。
     *
     * <p><b>必须在 finally 里调 {@link #clear()}</b>:SecurityContextHolder
     * 默认是 ThreadLocal,JUnit 复用线程,不清会把上一个测试的用户
     * 带进下一个。
     */
    public static void actingAs(Authenticatable user, String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();

        var token = UsernamePasswordAuthenticationToken.authenticated(
                user, "N/A", granted);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
