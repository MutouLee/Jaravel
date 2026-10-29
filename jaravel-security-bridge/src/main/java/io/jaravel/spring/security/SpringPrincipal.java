package io.jaravel.spring.security;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import io.jaravel.auth.Authenticatable;

/**
 * 把 Spring 的 principal 直接当作 Jaravel 主体,**不查库**。
 *
 * <p>{@link SpringSecurityGuard.PrincipalResolver#wrapping()} 用它。
 *
 * <h2>它拿不到模型,这是刻意的</h2>
 * <p>{@code Auth.user(User.class)} 在这个主体上返回空 —— 因为确实没有
 * {@code User} 实例。如果 Policy 需要模型(绝大多数需要),就该用默认的
 * {@code byUsername} 策略查库;用 {@code wrapping()} 等于明确声明
 * 「本服务不需要本地用户表」。
 *
 * <p>让它假装有模型(比如凭空 new 一个 User 填上 id)会更糟:
 * Policy 里的 {@code post.userId.equals(user.id)} 会在一个
 * 半空的对象上比较,结果是静默的授权错误。
 */
public final class SpringPrincipal implements Authenticatable {

    private final String identifier;
    private final Set<String> authorities;
    private final Object springPrincipal;

    private SpringPrincipal(String identifier, Set<String> authorities,
                            Object springPrincipal) {
        this.identifier = identifier;
        this.authorities = Set.copyOf(authorities);
        this.springPrincipal = springPrincipal;
    }

    public static SpringPrincipal of(Authentication authentication) {
        var authorities = new LinkedHashSet<String>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            authorities.add(authority.getAuthority());
        }
        return new SpringPrincipal(authentication.getName(), authorities,
                                   authentication.getPrincipal());
    }

    @Override
    public Object getAuthIdentifier() {
        return identifier;
    }

    /**
     * 用户名而不是 {@code id}。
     *
     * <p>这个主体不来自数据库,没有自增主键 —— 它的身份就是用户名
     * (或 JWT 的 sub)。谎称列名是 {@code id} 会让任何按
     * {@code where id = ?} 的查询拼出错误的 SQL。
     */
    @Override
    public String getAuthIdentifierName() {
        return "username";
    }

    /**
     * 永远为空。
     *
     * <p>密码校验发生在 Spring 侧(可能根本不涉及密码 —— SAML / OIDC
     * 场景下本服务从不接触凭据)。返回 {@code Optional.empty()} 会让
     * Jaravel 的 {@code Hash.check} 路径直接判失败,而不是拿着
     * {@code null} 去哈希。
     */
    @Override
    public Optional<String> getAuthPassword() {
        return Optional.empty();
    }

    public Set<String> authorities() {
        return authorities;
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    /**
     * 返回 Spring 的原始 principal。
     *
     * <p>通常是 {@link UserDetails} 或一个 JWT claim 包。
     * {@code Auth.user(Jwt.class)} 之类的调用由此可用。
     */
    @Override
    public Object unwrap() {
        return springPrincipal;
    }

    @Override
    public String toString() {
        return "SpringPrincipal[" + identifier + " " + authorities + "]";
    }
}
