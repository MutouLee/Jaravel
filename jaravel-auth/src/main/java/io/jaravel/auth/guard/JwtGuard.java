package io.jaravel.auth.guard;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.Guard;
import io.jaravel.auth.UserProvider;
import io.jaravel.cache.Repository;

/**
 * 无状态 JWT 认证。
 *
 * <h2>默认按 sub 查库</h2>
 * <p>JWT 的卖点是「不用查库」,但那同时意味着**撤销不了** —— 用户被停用、
 * 权限被改、账号被删,token 在过期前都照样有效。
 *
 * <p>所以这里默认仍然按 {@code sub} 查一次用户。想要真正的无状态模式
 * 用 {@link #statelessMode()},但那时要接受:
 * <ul>
 *   <li>停用用户在 token 过期前仍能访问;</li>
 *   <li>claims 里的信息(角色、套餐)是签发时的快照。</li>
 * </ul>
 * <p>这个取舍必须由使用者明确选择,而不是被框架默认替他选了。
 *
 * <h2>吊销名单</h2>
 * <p>给了 cache 就启用 jti 黑名单,于是「登出」对 JWT 也有意义 ——
 * 把 jti 记进缓存直到 token 自然过期。没有 cache 时 {@code logout}
 * 会报错而不是静默无效。
 */
public class JwtGuard implements Guard {

    private final String name;
    private final UserProvider provider;
    private final Jwt jwt;
    private final Duration ttl;
    private final Repository revocations;    // 可为 null

    private boolean stateless;
    private String presentedToken;
    private Map<String, Object> claims;
    private Authenticatable cachedUser;
    private boolean resolved;

    public JwtGuard(String name, UserProvider provider, Jwt jwt, Duration ttl) {
        this(name, provider, jwt, ttl, null);
    }

    public JwtGuard(String name, UserProvider provider, Jwt jwt, Duration ttl,
                    Repository revocations) {
        this.name = name;
        this.provider = provider;
        this.jwt = jwt;
        this.ttl = ttl;
        this.revocations = revocations;
    }

    @Override public String name() { return name; }

    @Override public UserProvider provider() { return provider; }

    /**
     * 不查库,直接用 claims 构造一个轻量主体。
     *
     * <p>读 §类注释里的取舍再决定用它。
     */
    public JwtGuard statelessMode() {
        this.stateless = true;
        return this;
    }

    public void presentToken(String token) {
        this.presentedToken = token;
        this.resolved = false;
        this.cachedUser = null;
        this.claims = null;
    }

    // ================= 认证 =================

    @Override
    public Optional<Authenticatable> user() {
        if (resolved) return Optional.ofNullable(cachedUser);
        resolved = true;

        if (presentedToken == null || presentedToken.isBlank()) return Optional.empty();

        try {
            claims = jwt.verify(presentedToken, java.util.Set.of("sub"));
        } catch (Jwt.InvalidTokenException e) {
            // 无效 token = 未认证,不是 500。中间件会把它变成 401。
            return Optional.empty();
        }

        if (isRevoked(claims)) return Optional.empty();

        Object subject = claims.get("sub");
        if (stateless) {
            cachedUser = new ClaimsUser(subject, claims);
            return Optional.of(cachedUser);
        }

        var user = provider.retrieveById(subject);
        user.ifPresent(u -> cachedUser = u);
        return user;
    }

    @Override
    public boolean validate(Map<String, Object> credentials) {
        throw new UnsupportedOperationException("""
                JwtGuard 不支持 validate(凭据)。
                  JWT 的身份来自签过名的 token,没有「用凭据校验」这一步:
                    · 签发 token  → jwtGuard.issue(user)
                    · 校验身份     → presentToken(t); check()
                  要校验用户密码请用会话 guard。""");
    }

    @Override
    public void setUser(Authenticatable user) {
        this.cachedUser = user;
        this.resolved = true;
    }

    public void reset() {
        presentedToken = null;
        claims = null;
        cachedUser = null;
        resolved = false;
    }

    /** 当前 token 的全部 claims —— 自定义 claim(租户 id、套餐)从这里取 */
    public Optional<Map<String, Object>> claims() {
        if (!resolved) user();
        return Optional.ofNullable(claims);
    }

    // ================= 签发与吊销 =================

    public String issue(Authenticatable user) {
        return issue(user, Map.of());
    }

    /**
     * 签发。
     *
     * @param extraClaims 额外 claim。**不要放敏感信息** —— JWT 的 payload
     *                    只是 base64,任何人都能读。它是「被签名的」而不是
     *                    「被加密的」,这一点常被搞错。
     */
    public String issue(Authenticatable user, Map<String, Object> extraClaims) {
        var payload = new LinkedHashMap<String, Object>(extraClaims);
        payload.put("sub", user.getAuthIdentifier());
        return jwt.issue(payload, ttl);
    }

    /**
     * 吊销当前 token。
     *
     * <p>需要吊销名单(缓存)。没有的话直接报错 —— 「调用了 logout
     * 但 token 仍然有效」是个会被当成安全漏洞的行为,不该静默发生。
     */
    public void revokeCurrent() {
        if (revocations == null) {
            throw new UnsupportedOperationException("""
                    这个 JwtGuard 没有配置吊销名单,无法吊销 token。
                      JWT 是无状态的 —— 签出去的 token 在过期前天然有效,
                      「登出」需要一份服务端的黑名单。
                      做法:构造时传一个缓存 Repository:
                        new JwtGuard("api", provider, jwt, ttl, Cache.store())
                      不想维护黑名单的话,把 ttl 调短(几分钟)并用刷新令牌。""");
            }

        var current = claims().orElseThrow(() -> new IllegalStateException(
                "没有当前 token 可吊销"));
        Object jti = current.get("jti");
        if (jti == null) {
            throw new IllegalStateException(
                    "当前 token 没有 jti,无法单独吊销 —— 它不是本框架签发的。");
        }

        // 只需记到 token 自然过期为止
        Duration remaining = remainingLifetime(current);
        revocations.put(revocationKey(String.valueOf(jti)), true, remaining);
        reset();
    }

    private boolean isRevoked(Map<String, Object> claims) {
        if (revocations == null) return false;
        Object jti = claims.get("jti");
        if (jti == null) return false;
        return revocations.has(revocationKey(String.valueOf(jti)));
    }

    private static String revocationKey(String jti) {
        return "jwt:revoked:" + jti;
    }

    private Duration remainingLifetime(Map<String, Object> claims) {
        Object exp = claims.get("exp");
        if (exp instanceof Number n) {
            long seconds = n.longValue() - java.time.Instant.now().getEpochSecond();
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(1);
        }
        // 没有 exp 的 token 永不过期 —— 黑名单只能也永久保留
        return ttl == null ? Duration.ofDays(365) : ttl;
    }

    /**
     * 无状态模式下的主体:只有 claims,没有数据库记录。
     *
     * <p>{@code unwrap()} 返回 claims Map 而不是模型 —— 于是
     * {@code Auth.user(User.class)} 会返回空。这是刻意的:
     * 无状态模式下根本没有查过库,假装有一个 User 对象会更误导。
     */
    private record ClaimsUser(Object subject, Map<String, Object> claims)
            implements Authenticatable {

        @Override public Object getAuthIdentifier() { return subject; }

        @Override public Optional<String> getAuthPassword() { return Optional.empty(); }

        @Override public Object unwrap() { return claims; }

        @Override public String toString() {
            return "ClaimsUser(sub=" + subject + ", 无状态模式未查库)";
        }
    }
}
