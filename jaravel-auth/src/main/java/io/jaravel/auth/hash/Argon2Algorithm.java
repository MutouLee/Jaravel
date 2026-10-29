package io.jaravel.auth.hash;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * argon2id —— OWASP 当前的首选密码哈希算法。
 *
 * <p>经 {@code spring-security-crypto} 的 Argon2PasswordEncoder
 * (底层是 bouncycastle)。同样用反射,理由见 {@link BcryptAlgorithm}。
 *
 * <h2>参数:19MiB / 2 轮 / 并行度 1</h2>
 * <p>取 OWASP 2023 的建议值,比 docs/08 写的 (64MiB, 4, 2) 低。
 * 理由是并发成本:argon2 的内存是**每次哈希**占用的,64MiB × 100 个并发登录
 * 就是 6.4GB —— 一次登录高峰能把进程打爆,而这正是攻击者会做的事。
 * OWASP 的建议值已经把这个权衡考虑进去了。
 */
public final class Argon2Algorithm implements HashManager.Algorithm {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MEMORY_KIB = 19456;   // 19 MiB
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;

    private final Object encoder;
    private final Method encode;
    private final Method matches;

    public Argon2Algorithm() {
        try {
            Class<?> type = Class.forName(
                    "org.springframework.security.crypto.argon2.Argon2PasswordEncoder");
            this.encoder = type.getConstructor(int.class, int.class, int.class,
                                               int.class, int.class)
                    .newInstance(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
            this.encode = type.getMethod("encode", CharSequence.class);
            this.matches = type.getMethod("matches", CharSequence.class, String.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "argon2id 需要 spring-security-crypto 与 bouncycastle,"
                  + "但它们不在 classpath 上。"
                  + "\n  加上依赖,或把 auth.hashing.driver 改成 bcrypt / pbkdf2。", e);
        }
    }

    @Override public String name() { return "argon2id"; }

    @Override public boolean recognizes(String hash) {
        return hash != null && hash.startsWith("$argon2id$");
    }

    @Override public String make(String plaintext) {
        return (String) invoke(encode, plaintext);
    }

    @Override public boolean check(String plaintext, String hash) {
        if (plaintext == null || hash == null) return false;
        return (Boolean) invoke(matches, plaintext, hash);
    }

    /**
     * 参数是否已过时。
     *
     * <p>自己从哈希串里解析 {@code m=,t=,p=} 来比,而不是调 encoder 的
     * {@code upgradeEncoding} —— 那个方法在部分版本里对 argon2 恒返回 false,
     * 于是提高参数后永远不会触发重算。
     */
    @Override
    public boolean needsRehash(String hash) {
        if (!recognizes(hash)) return true;
        try {
            // $argon2id$v=19$m=19456,t=2,p=1$salt$hash
            String params = hash.split("\\$")[3];
            int memory = 0;
            int iterations = 0;
            for (String pair : params.split(",")) {
                String[] kv = pair.split("=");
                if (kv[0].equals("m")) memory = Integer.parseInt(kv[1]);
                if (kv[0].equals("t")) iterations = Integer.parseInt(kv[1]);
            }
            return memory < MEMORY_KIB || iterations < ITERATIONS;
        } catch (RuntimeException e) {
            return true;      // 解析不了就当该升级
        }
    }

    private Object invoke(Method method, Object... args) {
        try {
            return method.invoke(encoder, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("调用 argon2id 失败:" + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("argon2id 执行失败:" + cause.getMessage(), cause);
        }
    }
}
