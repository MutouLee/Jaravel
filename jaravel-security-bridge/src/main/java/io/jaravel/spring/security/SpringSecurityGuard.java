package io.jaravel.spring.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.Guard;
import io.jaravel.auth.UserProvider;

/**
 * 读取 Spring Security 认证结果的 Guard。
 *
 * <pre>{@code
 * # config/auth.yml
 * auth:
 *   guards:
 *     web:
 *       driver: spring-security      # ← 认证交给 Spring
 *       provider: users              # ← 用户仍从 Jaravel 的 provider 取
 * }</pre>
 *
 * <p>配好之后 {@code Auth.user()}、{@code Gate}、{@code Policy}、
 * {@code middleware("auth")} 全部照常工作,而实际的认证(SAML / OIDC /
 * 企业 SSO / 复杂 OAuth2)由 Spring Security 的 Filter 链完成。
 * 这是 docs/08 §8.3 承诺的「企业用 Spring 做认证,用 Jaravel 做授权」。
 *
 * <h2>谁是唯一事实来源</h2>
 * <p>用这个 driver 时,{@link SecurityContextHolder} 是**唯一**的事实来源,
 * Jaravel 不再维护自己的一份登录状态。
 *
 * <p>这一点必须这样定,因为 docs/08 §8.1 第 4 条正是:
 * {@code SecurityContextHolder} 基于 ThreadLocal,Jaravel 的请求作用域基于
 * {@code ScopedValue},**双向同步两套上下文必然出现不一致**。于是这里只做
 * 单向读取 —— 本类不往 Jaravel 侧写任何登录态,只在同一个请求内缓存一次
 * 「principal → Authenticatable」的解析结果(省掉重复查库)。
 *
 * <h2>匿名认证是最容易踩的那个坑</h2>
 * <p>Spring Security 默认会给未登录请求装一个
 * {@link AnonymousAuthenticationToken} —— 它**不是 null**,且
 * {@code isAuthenticated()} 返回 **true**。所以
 * {@code getAuthentication() != null} 这个看起来正确的判断会让
 * 所有游客都变成已登录用户,{@code middleware("auth")} 彻底失效。
 * 见 {@link #currentAuthentication()}。
 */
public class SpringSecurityGuard implements Guard {

    private final String name;
    private final UserProvider provider;
    private final PrincipalResolver resolver;

    /** 可选:用于 {@link #validate} 的 Spring 认证管理器 */
    private final AuthenticationManager authenticationManager;

    /**
     * 本请求内的解析缓存。
     *
     * <p>不是跨请求的 —— 本类的实例由 {@code AuthManager} 放在
     * {@code ExecutionScope} 里,请求结束即丢(见 AuthManager 的类注释)。
     */
    private Authenticatable resolved;
    private Authentication resolvedFrom;

    public SpringSecurityGuard(String name, UserProvider provider) {
        this(name, provider, PrincipalResolver.byUsername("email"), null);
    }

    public SpringSecurityGuard(String name, UserProvider provider,
                               PrincipalResolver resolver,
                               AuthenticationManager authenticationManager) {
        this.name = name;
        this.provider = provider;
        this.resolver = resolver;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public String name() {
        return name;
    }

    // ================= 读取 =================

    @Override
    public Optional<Authenticatable> user() {
        Authentication authentication = currentAuthentication().orElse(null);
        if (authentication == null) {
            resolved = null;
            resolvedFrom = null;
            return Optional.empty();
        }

        // 同一个 Authentication 实例只解析一次 —— byUsername 要查库
        if (resolved != null && resolvedFrom == authentication) {
            return Optional.of(resolved);
        }

        Optional<Authenticatable> principal = resolver.resolve(authentication, provider);
        resolved = principal.orElse(null);
        resolvedFrom = principal.isPresent() ? authentication : null;
        return principal;
    }

    /**
     * 当前**真正**的认证,滤掉匿名与未认证。
     *
     * <p>三层过滤,每一层都对应一种真实存在的状态:
     * <ol>
     *   <li>{@code null} —— 没有任何 Filter 跑过(非 Web 线程、测试);</li>
     *   <li>{@link AnonymousAuthenticationToken} —— 游客。见类注释,
     *       **它的 isAuthenticated() 是 true**,所以必须按类型排除;</li>
     *   <li>{@code !isAuthenticated()} —— 认证流程进行中但未完成
     *       (例如刚从 Filter 收到凭据还没校验)。</li>
     * </ol>
     */
    protected Optional<Authentication> currentAuthentication() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) return Optional.empty();
        if (authentication instanceof AnonymousAuthenticationToken) return Optional.empty();
        if (!authentication.isAuthenticated()) return Optional.empty();

        return Optional.of(authentication);
    }

    /**
     * Spring 侧的权限集合({@code ROLE_ADMIN}、{@code SCOPE_write} …)。
     *
     * <p>SSO 场景里角色往往来自 IdP 断言,只在 Spring 侧存在 ——
     * 本方法是把它接进 Jaravel {@code Gate} 的入口,见
     * {@link SecurityBridge#registerAuthorityAbilities}。
     */
    public Set<String> authorities() {
        return currentAuthentication()
                .map(Authentication::getAuthorities)
                .map(SpringSecurityGuard::names)
                .orElseGet(Set::of);
    }

    private static Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        var result = new LinkedHashSet<String>();
        for (GrantedAuthority authority : authorities) {
            result.add(authority.getAuthority());
        }
        return result;
    }

    // ================= 校验 =================

    /**
     * 校验凭据但不登录。
     *
     * <p>有 {@link AuthenticationManager} 时交给 Spring —— 必须如此:
     * 企业环境里「密码对不对」的答案可能在 LDAP 或 IdP,而不在本地库。
     * 没有时退回 Jaravel 的 provider。
     */
    @Override
    public boolean validate(Map<String, Object> credentials) {
        if (authenticationManager == null) {
            return provider.retrieveByCredentials(credentials)
                    .filter(user -> provider.validateCredentials(user, credentials))
                    .isPresent();
        }

        Object username = credentials.get("email") != null
                ? credentials.get("email") : credentials.get("username");
        Object password = credentials.get("password");

        try {
            Authentication result = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            return result != null && result.isAuthenticated();
        } catch (AuthenticationException e) {
            // Spring 用异常表达「认证失败」,而 validate 的契约是返回布尔。
            // 不往外抛:调用方是「确认当前密码」之类的流程,
            // 失败是预期分支而不是错误
            return false;
        }
    }

    /**
     * 不支持 —— 用 spring-security driver 时登录态由 Spring 持有。
     *
     * <p>抛异常而不是静默写进 Jaravel 侧:后者会造成
     * 「setUser 之后 Auth.user() 仍是旧值」(因为 user() 读的是
     * SecurityContextHolder),那是最难查的一类 bug。
     */
    @Override
    public void setUser(Authenticatable user) {
        throw new UnsupportedOperationException("""
                spring-security guard 不能用 setUser —— 登录态的唯一事实来源是
                Spring 的 SecurityContextHolder,写进 Jaravel 侧会立刻被下一次
                Auth.user() 忽略。
                  · 生产代码:走 Spring 的认证流程(Filter / AuthenticationManager);
                  · 测试代码:用 SecurityBridge.actingAs(user) 直接设置
                    SecurityContextHolder,或 Spring 的 @WithMockUser。""");
    }

    @Override
    public UserProvider provider() {
        return provider;
    }

    // ================= principal → Authenticatable =================

    /**
     * 怎么把 Spring 的 principal 变成 Jaravel 的主体。
     *
     * <p>这是桥接里**唯一需要选择**的地方,因为两边的 principal 概念不同:
     * Spring 的可以是 {@link UserDetails}、一个 JWT claim 包、甚至一个字符串;
     * Jaravel 的 {@code Policy} 却常常需要真正的模型(
     * {@code can(user, post)} 里要读 {@code post.user_id == user.id})。
     */
    public interface PrincipalResolver {

        Optional<Authenticatable> resolve(Authentication authentication,
                                          UserProvider provider);

        /**
         * **默认策略**:拿 Spring 的用户名去 Jaravel 的 provider 查库。
         *
         * <p>这样 {@code Auth.user(User.class)} 返回真正的模型,Policy
         * 可以照常读它的字段 —— 代价是每个请求一次查库(本 Guard 内有缓存)。
         *
         * <p>SSO 场景下用户可能**在本地库里还不存在**(首次登录)。此时返回空,
         * 于是 {@code middleware("auth")} 会判定未登录 —— 这是故意的:
         * 自动建号(JIT provisioning)是业务决定,不该由桥接层偷偷做。
         * 要自动建号就自己实现本接口,或在 Spring 的
         * {@code AuthenticationSuccessHandler} 里建。
         *
         * @param column provider 里用来匹配的列,通常是 {@code email} 或 {@code name}
         */
        static PrincipalResolver byUsername(String column) {
            return (authentication, provider) -> {
                // principal 已经是 Jaravel 主体(自定义 Filter 直接塞进去的)
                if (authentication.getPrincipal() instanceof Authenticatable ready) {
                    return Optional.of(ready);
                }

                String username = authentication.getName();
                if (username == null || username.isBlank()) return Optional.empty();

                return provider.retrieveByCredentials(Map.of(column, username));
            };
        }

        /**
         * 不查库,直接把 Spring 的 principal 包起来。
         *
         * <p>适合纯无状态场景(网关已经验过 JWT,下游只需要知道「是谁」
         * 以及有哪些权限)。{@code Auth.id()} 可用,但 Policy 里
         * {@code Auth.user(User.class)} 拿不到模型 —— 见
         * {@link SpringPrincipal} 的注释。
         */
        static PrincipalResolver wrapping() {
            return (authentication, provider) -> {
                if (authentication.getPrincipal() instanceof Authenticatable ready) {
                    return Optional.of(ready);
                }
                return Optional.of(SpringPrincipal.of(authentication));
            };
        }

        /** 自定义:例如从 JWT 的 {@code sub} claim 按主键查 */
        static PrincipalResolver custom(
                Function<Authentication, Optional<Authenticatable>> mapper) {
            return (authentication, provider) -> mapper.apply(authentication);
        }
    }
}
