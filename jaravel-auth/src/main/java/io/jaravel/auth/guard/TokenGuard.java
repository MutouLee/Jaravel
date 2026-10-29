package io.jaravel.auth.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.Guard;
import io.jaravel.auth.UserProvider;
import io.jaravel.database.Connection;

/**
 * API Token 认证:{@code Authorization: Bearer <token>}。
 *
 * <h2>Token 哈希存库,明文只在签发时出现一次</h2>
 * <p>这是本类最重要的一点。明文存库的话,数据库泄露 = 所有 API token 泄露,
 * 而 token 往往比密码权限更大(它们常被配进 CI、脚本、第三方集成)。
 *
 * <p>哈希用 **SHA-256 而不是 PBKDF2/bcrypt**。与密码相反的选择,理由:
 * <ul>
 *   <li>token 是**我们生成的 32 字节随机值**,没有「弱口令」可言 ——
 *       慢哈希防的是字典攻击,而这里字典攻击不存在;</li>
 *   <li>每个 API 请求都要校验一次。用 PBKDF2(210k 迭代)意味着
 *       每个请求多 200ms,那是不可接受的;</li>
 *   <li>SHA-256 是确定性的,因此可以**按哈希查库**(一次索引查询)。
 *       加盐的慢哈希做不到这件事,只能全表扫。</li>
 * </ul>
 * <p>这个取舍的前提是「token 必须有足够熵」—— 所以生成由框架负责,
 * 不接受用户自带 token。
 *
 * <h2>token 字符串的格式</h2>
 * <pre>
 * &lt;token id&gt;|&lt;明文随机值&gt;
 * </pre>
 * <p>带 id 的作用是**让查库成为按主键查**而不是按哈希列查。
 * 没有它也能工作(按哈希查),但那要求哈希列有索引,
 * 而带 id 顺带让「这个 token 属于谁、何时创建」可追溯。
 */
public class TokenGuard implements Guard {

    private final String name;
    private final UserProvider provider;
    private final Connection connection;
    private final String table;

    private Authenticatable cachedUser;
    private boolean resolved;

    /** 本次请求携带的 token 原文,由中间件/适配器注入 */
    private String presentedToken;

    public TokenGuard(String name, UserProvider provider, Connection connection) {
        this(name, provider, connection, "personal_access_tokens");
    }

    public TokenGuard(String name, UserProvider provider, Connection connection, String table) {
        this.name = name;
        this.provider = provider;
        this.connection = connection;
        this.table = table;
    }

    @Override public String name() { return name; }

    @Override public UserProvider provider() { return provider; }

    /** 由适配器从 {@code Authorization} 头取出后交进来 */
    public void presentToken(String token) {
        this.presentedToken = token;
        this.resolved = false;
        this.cachedUser = null;
    }

    @Override
    public Optional<Authenticatable> user() {
        if (resolved) return Optional.ofNullable(cachedUser);
        resolved = true;

        if (presentedToken == null || presentedToken.isBlank()) return Optional.empty();

        var record = lookup(presentedToken);
        if (record.isEmpty()) return Optional.empty();

        var user = provider.retrieveById(record.get().userId());
        user.ifPresent(u -> {
            cachedUser = u;
            touchLastUsed(record.get().id());
        });
        return user;
    }

    /**
     * Token 认证不支持用凭据校验 —— 它没有「凭据」这个概念。
     *
     * <p>报错而不是返回 false:返回 false 会让
     * {@code Auth.guard("api").validate(...)} 看起来像「密码错了」。
     */
    @Override
    public boolean validate(Map<String, Object> credentials) {
        throw new UnsupportedOperationException("""
                TokenGuard 不支持 validate(凭据) —— API token 认证没有「凭据校验」这一步。
                  要校验一个 token 是否有效:guard.presentToken(t); guard.check();
                  要校验用户密码(改密码前的确认):用会话 guard
                  Auth.guard("web").validate(...)。""");
    }

    @Override
    public void setUser(Authenticatable user) {
        this.cachedUser = user;
        this.resolved = true;
    }

    public void reset() {
        cachedUser = null;
        resolved = false;
        presentedToken = null;
    }

    // ================= 签发与撤销 =================

    /**
     * 签发一个新 token。
     *
     * @return 明文 token —— **只在这里出现一次**,之后无法再取回
     */
    public NewToken issue(Authenticatable user, String tokenName, Instant expiresAt) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("name", tokenName);
        values.put("user_id", user.getAuthIdentifier());
        values.put("token_hash", sha256(plain));
        values.put("created_at", Instant.now());
        values.put("expires_at", expiresAt);

        long id = connection.table(table).insertGetId(values)
                .orElseThrow(() -> new IllegalStateException(
                        "插入 token 后未取到自增主键 —— 检查 " + table + " 表是否有自增主键"));

        return new NewToken(id, id + "|" + plain, tokenName, expiresAt);
    }

    public NewToken issue(Authenticatable user, String tokenName) {
        return issue(user, tokenName, null);
    }

    public void revoke(long tokenId) {
        connection.table(table).where("id", tokenId).delete();
    }

    /** 撤销某用户的全部 token —— 「退出所有设备」的 API 版本 */
    public int revokeAll(Authenticatable user) {
        return connection.table(table)
                .where("user_id", user.getAuthIdentifier())
                .delete();
    }

    public int purgeExpired() {
        return connection.table(table)
                .whereNotNull("expires_at")
                .where("expires_at", io.jaravel.database.query.QueryState.Op.LTE, Instant.now())
                .delete();
    }

    // ================= 查库 =================

    private record TokenRecord(long id, Object userId) {}

    private Optional<TokenRecord> lookup(String presented) {
        int separator = presented.indexOf('|');
        if (separator <= 0 || separator == presented.length() - 1) {
            return Optional.empty();
        }

        long id;
        try {
            id = Long.parseLong(presented.substring(0, separator));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String plain = presented.substring(separator + 1);

        var row = connection.table(table).where("id", id).get().first();
        if (row.isEmpty()) return Optional.empty();

        String storedHash = String.valueOf(row.get().get("token_hash"));
        // **定时安全比较** —— 普通 equals 会在第一个不同的字符返回,
        // 让攻击者可以逐字节爆破(token 比密码更值得这样攻击:
        // 它没有登录节流)
        if (!MessageDigest.isEqual(
                sha256(plain).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        Object expiresAt = row.get().get("expires_at");
        if (expiresAt != null) {
            Instant expiry = io.jaravel.support.Cast.to(expiresAt, Instant.class);
            if (Instant.now().isAfter(expiry)) return Optional.empty();
        }

        return Optional.of(new TokenRecord(id, row.get().get("user_id")));
    }

    /**
     * 记录最后使用时间。
     *
     * <p>**失败不影响认证**:这是审计信息,而让一次 UPDATE 失败导致
     * API 调用 500 是本末倒置。
     */
    private void touchLastUsed(long tokenId) {
        try {
            connection.table(table).where("id", tokenId)
                    .update(Map.of("last_used_at", Instant.now()));
        } catch (RuntimeException e) {
            // 列不存在或库只读 —— 认证已经成功,不该因为审计写失败而拒绝请求
        }
    }

    static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256,这不该发生", e);
        }
    }

    /**
     * 新签发的 token。
     *
     * @param plainText 明文,**只在签发时可得**。展示给用户后就无法再取回 ——
     *                  这正是「哈希存库」的直接后果,也是必须如此的理由。
     */
    public record NewToken(long id, String plainText, String name, Instant expiresAt) {

        @Override public String toString() {
            // 刻意不打印明文:toString 很容易进日志
            return "NewToken(id=" + id + ", name=" + name + ", plainText=<隐藏>)";
        }
    }
}
