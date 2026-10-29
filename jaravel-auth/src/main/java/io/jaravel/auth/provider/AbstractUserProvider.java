package io.jaravel.auth.provider;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.UserProvider;
import io.jaravel.auth.hash.HashManager;

/**
 * 两件所有提供器都必须做对的事:时序攻击防护、remember 令牌的定时安全比较。
 *
 * <p>放在基类而不是让每个提供器各自实现 —— 这两件事都属于「忘了做也能跑,
 * 但安全性静默降低」的类别,正是最该集中处理的。
 */
public abstract class AbstractUserProvider implements UserProvider {

    /**
     * 用户不存在时用来「空转」一次哈希校验的假哈希。
     *
     * <p>必须是**真的能被校验算法处理**的格式,否则 check 会提前返回,
     * 耗时与真实校验不同,防护就失效了。这里用 pbkdf2 的最低参数生成一个
     * 固定值;它对应的明文无人知晓且无关紧要。
     */
    private static final String DUMMY_HASH =
            "$pbkdf2-sha512$i=10000$AAAAAAAAAAAAAAAAAAAAAA==$"
          + "ZGVsaWJlcmF0ZWx5LW5vdC1hLXJlYWwtaGFzaC1qdXN0LWZvci10aW1pbmctZGVmZW5zZQ==";

    protected final HashManager hasher;

    protected AbstractUserProvider(HashManager hasher) {
        this.hasher = hasher;
    }

    /**
     * 校验密码。
     *
     * <p>**用户为 null 时仍然跑一次哈希校验。** 这是 docs/08 §3.2 要求的
     * 时序攻击防护:否则「邮箱不存在」会立刻返回(几微秒),
     * 而「邮箱存在但密码错」要跑完一次 PBKDF2(两百毫秒)。
     * 这个差异大到可以用来**枚举已注册邮箱** —— 对于用邮箱登录的站点,
     * 等于泄露用户名单。
     */
    @Override
    public boolean validateCredentials(Authenticatable user, Map<String, Object> credentials) {
        String plaintext = passwordFrom(credentials);
        if (plaintext == null || plaintext.isEmpty()) return false;

        String hash = user == null
                ? DUMMY_HASH
                : user.getAuthPassword().orElse(DUMMY_HASH);

        boolean matched = hasher.check(plaintext, hash);
        // 用户为 null 时无论算出什么都返回 false —— 上面那次校验只为耗时
        return user != null && matched;
    }

    /** 空转一次哈希,供 Guard 在「用户不存在」分支显式调用 */
    public void burnTimeOnMissingUser(Map<String, Object> credentials) {
        validateCredentials(null, credentials);
    }

    protected static String passwordFrom(Map<String, Object> credentials) {
        Object value = credentials.get("password");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * remember 令牌的比较。
     *
     * <p>必须定时安全:令牌是 60 字符的随机串,普通 equals 会在第一个
     * 不同的字符上返回,让攻击者可以逐字符爆破。
     *
     * <p>同时要求长度一致才比较 —— {@code MessageDigest.isEqual} 对
     * 不同长度会立刻返回,但那个信息本身无害(令牌长度是固定的公开参数)。
     */
    protected static boolean tokensMatch(String expected, String actual) {
        if (expected == null || actual == null) return false;
        if (expected.isBlank() || actual.isBlank()) return false;

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 凭据里除 password 外的字段 —— 它们是查询条件。
     *
     * <p>{@code attempt(Map.of("email", e, "password", p, "status", "active"))}
     * 里的 status 也要进 where,这是 docs/08 §2 列出的用法。
     */
    protected static Map<String, Object> queryableCredentials(Map<String, Object> credentials) {
        var out = new java.util.LinkedHashMap<String, Object>();
        credentials.forEach((key, value) -> {
            if (key.equals("password")) return;
            // 以 password 开头的其它键(password_confirmation)同样排除
            if (key.startsWith("password")) return;
            out.put(key, value);
        });
        if (out.isEmpty()) {
            throw new IllegalArgumentException("""
                    凭据里除 password 之外没有任何可查询的字段。
                      至少要有一个标识字段,如:
                        Auth.attempt(Map.of("email", email, "password", password))
                      只给 password 的话无法定位用户 —— 而「随便匹配一个」
                      显然不是想要的行为,所以这里直接报错。""");
        }
        return out;
    }

    @Override
    public Optional<Authenticatable> retrieveByToken(Object id, String token) {
        return retrieveById(id)
                .filter(user -> tokensMatch(user.getRememberToken().orElse(null), token));
    }
}
