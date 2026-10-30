package io.jaravel.auth.passwords;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.UserProvider;
import io.jaravel.auth.provider.AbstractUserProvider;
import io.jaravel.database.Connection;

/**
 * 密码重置。
 *
 * <pre>{@code
 * // 1. 发链接
 * var result = broker.sendResetLink(Map.of("email", email), (user, token) ->
 *         Mail.to(user).send(new ResetPasswordMail(token)));
 *
 * // 2. 重置
 * broker.reset(Map.of("email", e, "token", t, "password", p),
 *              (user, plain) -> { user.password = Hash.make(plain); user.save(); });
 * }</pre>
 *
 * <h2>四条安全要求(docs/08 §7.2)</h2>
 * <ol>
 *   <li><b>token 哈希存库</b> —— 数据库泄露时,存着的明文 token 等于
 *       一批可直接用的账号接管凭据;</li>
 *   <li><b>单次有效</b> —— 用完即删。不删的话,邮件被转发/归档后
 *       任何看到它的人都能反复改密码;</li>
 *   <li><b>过期</b> —— 默认 60 分钟;</li>
 *   <li><b>节流</b> —— 默认 60 秒内不重复发送。既防刷邮件,
 *       也防用它当邮箱枚举的放大器。</li>
 * </ol>
 *
 * <h2>永远不泄露「这个邮箱是否注册过」</h2>
 * <p>{@link #sendResetLink} 对「邮箱不存在」与「已发送」返回**同一个结果**
 * {@link Status#SENT},并且在用户不存在时也空转一次哈希。
 * 否则这个接口就成了最方便的邮箱枚举工具 —— 它不需要登录、不需要密码,
 * 而且通常没有验证码。
 */
public class PasswordBroker {

    public enum Status {
        /** 已发送(**或邮箱不存在** —— 刻意不区分) */
        SENT,
        /** 发送过于频繁 */
        THROTTLED,
        /** 重置成功 */
        RESET,
        /** token 无效或不存在 */
        INVALID_TOKEN,
        /** token 已过期 */
        EXPIRED_TOKEN,
        /** 凭据里的用户不存在 */
        INVALID_USER
    }

    private final UserProvider provider;
    private final Connection connection;
    private final String table;
    private final Duration expiry;
    private final Duration throttle;

    public PasswordBroker(UserProvider provider, Connection connection) {
        this(provider, connection, "password_reset_tokens",
             Duration.ofMinutes(60), Duration.ofSeconds(60));
    }

    public PasswordBroker(UserProvider provider, Connection connection, String table,
                          Duration expiry, Duration throttle) {
        this.provider = provider;
        this.connection = connection;
        this.table = table;
        this.expiry = expiry;
        this.throttle = throttle;
    }

    // ================= 发送 =================

    /**
     * 生成 token 并交给回调去发送。
     *
     * @param sender 收到 (用户, 明文 token)。**明文只在这里出现一次。**
     */
    public Status sendResetLink(Map<String, Object> credentials,
                                BiConsumer<Authenticatable, String> sender) {
        var user = provider.retrieveByCredentials(credentials);

        if (user.isEmpty()) {
            // 空转一次哈希,让「邮箱不存在」与「邮箱存在」耗时接近。
            // 然后返回 SENT —— 不泄露这个邮箱是否注册过。
            if (provider instanceof AbstractUserProvider base) {
                base.burnTimeOnMissingUser(Map.of("password", "timing-defense"));
            }
            return Status.SENT;
        }

        String identifier = identifierOf(user.get());
        if (recentlyRequested(identifier)) {
            return Status.THROTTLED;
        }

        String plain = generateToken();
        store(identifier, plain);
        sender.accept(user.get(), plain);
        return Status.SENT;
    }

    // ================= 重置 =================

    /**
     * 校验 token 并执行重置。
     *
     * @param resetter 收到 (用户, 新密码明文)。由调用方决定怎么哈希与保存 ——
     *                 框架不假设密码列叫什么,也不假设保存方式。
     */
    public Status reset(Map<String, Object> credentials,
                        BiConsumer<Authenticatable, String> resetter) {
        Object token = credentials.get("token");
        Object password = credentials.get("password");

        if (token == null || String.valueOf(token).isBlank()) {
            return Status.INVALID_TOKEN;
        }
        if (password == null || String.valueOf(password).isEmpty()) {
            throw new IllegalArgumentException(
                    "重置密码时 credentials 里必须有非空的 password。"
                  + "\n  密码的格式校验(长度、强度、confirmed)应在进入这里之前完成。");
        }

        // 查用户时要排除 token 与 password —— 它们不是查询条件
        var lookup = new java.util.LinkedHashMap<String, Object>(credentials);
        lookup.remove("token");

        var user = provider.retrieveByCredentials(lookup);
        if (user.isEmpty()) return Status.INVALID_USER;

        String identifier = identifierOf(user.get());
        var record = find(identifier);
        if (record.isEmpty()) return Status.INVALID_TOKEN;

        if (!tokenMatches(record.get().tokenHash(), String.valueOf(token))) {
            return Status.INVALID_TOKEN;
        }
        if (isExpired(record.get().createdAt())) {
            // 过期的记录顺手删掉,避免表里越积越多
            delete(identifier);
            return Status.EXPIRED_TOKEN;
        }

        resetter.accept(user.get(), String.valueOf(password));

        // **单次有效**:无论后续发生什么,这个 token 不能再用
        delete(identifier);

        // 事件在 token 删掉**之后**发 —— 一个「密码改了,把其它设备
        // 的会话踢掉」的监听器如果在这之前跑并且抛了异常,
        // 那个 token 会留在表里继续可用
        io.jaravel.events.Event.tryDispatch(
                new io.jaravel.auth.events.AuthEvents.PasswordReset(user.get()));
        return Status.RESET;
    }

    /** 只校验 token 是否有效,不执行重置 —— 用于「打开重置表单」那一步 */
    public Status validateToken(Map<String, Object> credentials) {
        var lookup = new java.util.LinkedHashMap<String, Object>(credentials);
        Object token = lookup.remove("token");
        if (token == null) return Status.INVALID_TOKEN;

        var user = provider.retrieveByCredentials(lookup);
        if (user.isEmpty()) return Status.INVALID_USER;

        var record = find(identifierOf(user.get()));
        if (record.isEmpty()) return Status.INVALID_TOKEN;
        if (!tokenMatches(record.get().tokenHash(), String.valueOf(token))) {
            return Status.INVALID_TOKEN;
        }
        return isExpired(record.get().createdAt()) ? Status.EXPIRED_TOKEN : Status.RESET;
    }

    // ================= 存储 =================

    private record TokenRecord(String identifier, String tokenHash, Instant createdAt) {}

    private void store(String identifier, String plainToken) {
        connection.transaction(() -> {
            // 同一个账号只保留最新的一个 token —— 先删再插。
            // 允许并存会让「旧链接仍然可用」,与单次有效的意图矛盾
            connection.table(table).where("email", identifier).delete();
            connection.table(table).insert(Map.of(
                    "email", identifier,
                    "token", sha256(plainToken),
                    "created_at", Instant.now()));
        });
    }

    private Optional<TokenRecord> find(String identifier) {
        return connection.table(table).where("email", identifier).get().first()
                .map(row -> new TokenRecord(
                        identifier,
                        String.valueOf(row.get("token")),
                        io.jaravel.support.Cast.to(row.get("created_at"), Instant.class)));
    }

    private void delete(String identifier) {
        connection.table(table).where("email", identifier).delete();
    }

    private boolean recentlyRequested(String identifier) {
        var record = find(identifier);
        if (record.isEmpty()) return false;
        return record.get().createdAt().plus(throttle).isAfter(Instant.now());
    }

    private boolean isExpired(Instant createdAt) {
        return createdAt == null || createdAt.plus(expiry).isBefore(Instant.now());
    }

    /** 清掉所有过期的 token。定时任务调用。 */
    public int purgeExpired() {
        return connection.table(table)
                .where("created_at", io.jaravel.database.query.QueryState.Op.LT,
                       Instant.now().minus(expiry))
                .delete();
    }

    // ================= 工具 =================

    /**
     * 用什么作为 token 的归属标识。
     *
     * <p>用主键而不是邮箱:邮箱可以改,而改了邮箱之后旧的重置链接
     * 还指向同一行记录 —— 那意味着用旧邮箱发出的链接能改新邮箱账号的密码。
     * 列名仍叫 {@code email} 是为了与 Laravel 的表结构兼容。
     */
    private String identifierOf(Authenticatable user) {
        return String.valueOf(user.getAuthIdentifier());
    }

    /**
     * token 是 32 字节随机值。
     *
     * <p>docs/08 §7.2 写的是「60 字节」—— 那说的是 remember token。
     * 32 字节(256 位)对一次性 token 已经远超需要:
     * 以每秒十亿次的速度猜,穷尽 2^256 需要超过宇宙年龄。
     */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 定时安全比较 —— token 可被逐字节爆破,而重置接口通常没有节流 */
    private static boolean tokenMatches(String storedHash, String presented) {
        return MessageDigest.isEqual(
                sha256(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用 SHA-256 而不是慢哈希。
     *
     * <p>与 API token 同理(见 {@code TokenGuard}):这是框架生成的
     * 32 字节随机值,不存在字典攻击,而慢哈希会让每次重置多两百毫秒
     * 且无法按哈希查库。
     */
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

    public Duration expiry() {
        return expiry;
    }

    public Duration throttle() {
        return throttle;
    }
}
