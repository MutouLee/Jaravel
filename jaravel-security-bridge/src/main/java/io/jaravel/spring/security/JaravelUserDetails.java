package io.jaravel.spring.security;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import io.jaravel.auth.Authenticatable;

/**
 * 把 Jaravel 主体包成 Spring 的 {@link UserDetails}。
 *
 * <p>方向与 {@link SpringPrincipal} 相反:这边是「Spring 的 Filter 链
 * 认证,用户来自 Jaravel 的 provider」。
 *
 * <p>它同时实现 {@link Authenticatable},所以
 * {@link SpringSecurityGuard.PrincipalResolver#byUsername} 认得它并走
 * 「principal 已经是 Jaravel 主体」的快路径 —— **省掉一次回查**。
 * 两个方向一起用(Spring 认证 + Jaravel 用户表 + Jaravel 授权)是
 * 企业场景里最常见的组合,这条快路径正是为它准备的。
 */
public final class JaravelUserDetails implements UserDetails, Authenticatable {

    private final Authenticatable principal;
    private final String username;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean enabled;

    public JaravelUserDetails(Authenticatable principal, String username,
                              Collection<? extends GrantedAuthority> authorities,
                              boolean enabled) {
        this.principal = principal;
        this.username = username;
        this.authorities = Collections.unmodifiableCollection(authorities);
        this.enabled = enabled;
    }

    // ================= UserDetails =================

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * 已哈希的密码。
     *
     * <p>Spring 的契约允许返回 null(例如 SSO 用户),配 {@link JaravelPasswordEncoder}
     * 时 {@code DaoAuthenticationProvider} 会按「凭据不可用」处理。
     */
    @Override
    public String getPassword() {
        return principal.getAuthPassword().orElse(null);
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // isAccountNonExpired / isCredentialsNonExpired / isAccountNonLocked
    // 用接口默认值 true。
    //
    // Jaravel 没有对应概念 —— 账号锁定在 Jaravel 侧由 LoginThrottle
    // (临时、按 email+IP)而不是用户表上的布尔列表达。把它们映射成
    // 某个猜测出来的列会制造一个 Jaravel 自己从不读写的字段。
    // 需要这些语义的项目可自行扩展本类。

    // ================= Authenticatable =================

    @Override
    public Object getAuthIdentifier() {
        return principal.getAuthIdentifier();
    }

    @Override
    public String getAuthIdentifierName() {
        return principal.getAuthIdentifierName();
    }

    @Override
    public java.util.Optional<String> getAuthPassword() {
        return principal.getAuthPassword();
    }

    @Override
    public java.util.Optional<String> getRememberToken() {
        return principal.getRememberToken();
    }

    @Override
    public void setRememberToken(String token) {
        principal.setRememberToken(token);
    }

    /** 透传到底层模型 —— 让 {@code Auth.user(User.class)} 在两个方向上都能工作 */
    @Override
    public Object unwrap() {
        return principal.unwrap();
    }

    public Authenticatable principal() {
        return principal;
    }

    @Override
    public String toString() {
        return "JaravelUserDetails[" + username + "]";
    }
}
