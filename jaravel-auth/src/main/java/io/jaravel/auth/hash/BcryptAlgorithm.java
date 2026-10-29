package io.jaravel.auth.hash;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * bcrypt,经 {@code spring-security-crypto} 的 BCryptPasswordEncoder。
 *
 * <h2>为什么用反射而不是直接调用</h2>
 * <p>{@code spring-security-crypto} 是 optional 依赖 —— 不用 bcrypt 的项目
 * 不该被迫拉它。直接 import 会让这个类在依赖缺失时抛
 * {@code NoClassDefFoundError},而那发生在类加载期,报错指向的位置
 * 离「你配了 bcrypt 但没加依赖」这个根因很远。
 *
 * <p>反射的代价是每次哈希多几纳秒 —— 相对于 bcrypt 本身的几十毫秒可以忽略。
 * {@link HashManager} 只在依赖存在时才注册本类。
 *
 * <h2>为什么不自己实现 bcrypt</h2>
 * <p>docs/08 §4 的原则:不自己写密码学代码。bcrypt 的 Blowfish 变体有若干
 * 实现陷阱(72 字节截断、{@code $2a}/{@code $2b}/{@code $2y} 的差异),
 * 复用经过审计的实现是唯一正确选择。
 */
public final class BcryptAlgorithm implements HashManager.Algorithm {

    /** bcrypt 的历史限制:超过 72 字节的部分被**静默忽略** */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final Object encoder;
    private final Method encode;
    private final Method matches;
    private final Method upgradeEncoding;
    private final int rounds;

    public BcryptAlgorithm(int rounds) {
        if (rounds < 10 || rounds > 16) {
            throw new IllegalArgumentException("""
                    bcrypt 的 cost 应在 10..16 之间,当前是 %d。
                      低于 10 在现代硬件上太快(2026 年的参考值是 12);
                      高于 16 会让每次登录耗时数秒,成为自己的 DoS。"""
                    .formatted(rounds));
        }
        this.rounds = rounds;
        try {
            Class<?> type = Class.forName(
                    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder");
            this.encoder = type.getConstructor(int.class).newInstance(rounds);
            this.encode = type.getMethod("encode", CharSequence.class);
            this.matches = type.getMethod("matches", CharSequence.class, String.class);
            this.upgradeEncoding = type.getMethod("upgradeEncoding", String.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "bcrypt 需要 spring-security-crypto,但它不在 classpath 上。"
                  + "\n  加上依赖,或把 auth.hashing.driver 改回 pbkdf2。", e);
        }
    }

    @Override public String name() { return "bcrypt"; }

    @Override public boolean recognizes(String hash) {
        return hash != null && (hash.startsWith("$2a$") || hash.startsWith("$2b$")
                             || hash.startsWith("$2y$"));
    }

    @Override
    public String make(String plaintext) {
        requireWithinBcryptLimit(plaintext);
        return (String) invoke(encode, plaintext);
    }

    @Override
    public boolean check(String plaintext, String hash) {
        if (plaintext == null || hash == null) return false;
        // 校验时**不**检查长度上限:历史数据可能是用超长密码建的,
        // 拒绝校验等于把那些用户锁在门外
        return (Boolean) invoke(matches, plaintext, hash);
    }

    @Override
    public boolean needsRehash(String hash) {
        return (Boolean) invoke(upgradeEncoding, hash);
    }

    public int rounds() {
        return rounds;
    }

    /**
     * bcrypt 会**静默截断**超过 72 字节的密码。
     *
     * <p>后果:两个前 72 字节相同的超长密码得到同一个哈希,于是一个
     * 「很长的密码」实际强度只有 72 字节。静默是最糟的部分 ——
     * 用户以为自己设了更强的密码。所以在**设置**密码时就拒绝,
     * 而不是等到泄露之后才发现。
     *
     * <p>中文每字 3 字节,72 字节只有 24 个汉字 —— 这个限制对中文用户
     * 比对英文用户紧得多,所以报错里要说出来。
     */
    private static void requireWithinBcryptLimit(String plaintext) {
        if (plaintext == null) return;
        int bytes = plaintext.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > BCRYPT_MAX_BYTES) {
            throw new IllegalArgumentException("""
                    密码 %d 字节,超过 bcrypt 的 %d 字节上限。
                      bcrypt 会**静默截断**超出部分 —— 两个前 72 字节相同的
                      超长密码会得到同一个哈希,而用户以为自己设了更强的密码。
                      处理方式:
                        · 在校验规则里加 max:72;
                        · 或换成 argon2id / pbkdf2(它们没有这个限制)。
                      注意中文每字 3 字节,72 字节只有 24 个汉字。"""
                    .formatted(bytes, BCRYPT_MAX_BYTES));
        }
    }

    private Object invoke(Method method, Object... args) {
        try {
            return method.invoke(encoder, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("调用 bcrypt 失败:" + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("bcrypt 执行失败:" + cause.getMessage(), cause);
        }
    }
}
