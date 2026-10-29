package io.jaravel.auth.hash;

import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.core.crypt.Hasher;
import io.jaravel.support.Arr;

/**
 * 密码哈希的算法注册表与门面。
 *
 * <pre>{@code
 * Hash.make("secret");
 * Hash.check("secret", hash);
 * Hash.needsRehash(hash);                  // 参数升级后该重算
 * Hash.driver("bcrypt").make("secret");    // 指定算法
 * }</pre>
 *
 * <h2>默认算法:PBKDF2-HMAC-SHA512(与 docs/08 §4 不同)</h2>
 * <p>docs/08 规定默认 bcrypt(cost 12),实现用 spring-security-crypto。
 * 落地时把默认改成了 core 里的 PBKDF2,原因:
 * <ul>
 *   <li>ADR-001 要求核心模块零外部依赖,而密码哈希是 {@code jaravel-core}
 *       就需要的能力(加密列、签名 URL 同理);</li>
 *   <li>JDK 内置 PBKDF2,不内置 bcrypt。PBKDF2 是 OWASP 认可的算法。</li>
 * </ul>
 *
 * <p><b>但 bcrypt 与 argon2id 仍然可用</b> —— 只要 classpath 上有
 * {@code spring-security-crypto}(bcrypt)或额外的 bouncycastle(argon2id),
 * 本类会自动注册它们,配置里写 {@code auth.hashing.driver: bcrypt} 即可。
 * 没有这些依赖时只注册 pbkdf2,且**不会静默回落** —— 配了 bcrypt 却没依赖
 * 会在启动时报错,而不是悄悄用一个更弱的算法。
 *
 * <p>算法选择的权衡(PBKDF2 不是内存困难的)写在 {@link Hasher} 的注释里。
 */
public class HashManager {

    private final Map<String, Algorithm> algorithms = new LinkedHashMap<>();
    private String defaultDriver;

    public HashManager() {
        register(new Pbkdf2Algorithm(new Hasher()));
        registerIfAvailable();
        this.defaultDriver = "pbkdf2";
    }

    /** 测试用:pbkdf2 迭代次数调低,否则每次 make 要 200ms */
    public static HashManager forTests() {
        var manager = new HashManager();
        manager.register(new Pbkdf2Algorithm(Hasher.forTests()));
        return manager;
    }

    public HashManager register(Algorithm algorithm) {
        algorithms.put(algorithm.name(), algorithm);
        return this;
    }

    public HashManager defaultDriver(String name) {
        if (!algorithms.containsKey(name)) {
            throw new IllegalStateException("""
                    配置的密码算法 "%s" 不可用。%s
                      已注册:%s

                      若想用 bcrypt / argon2id,把依赖加进来:
                        <dependency>
                          <groupId>org.springframework.security</groupId>
                          <artifactId>spring-security-crypto</artifactId>
                        </dependency>
                      argon2id 还需要 org.bouncycastle:bcprov-jdk18on。

                      这里**不会静默回落到 pbkdf2** —— 配了一个算法却跑着另一个,
                      是最不该让人猜的事。"""
                    .formatted(name,
                               Arr.closest(name, algorithms.keySet().stream().toList())
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                               algorithms.keySet()));
        }
        this.defaultDriver = name;
        return this;
    }

    public String defaultDriverName() {
        return defaultDriver;
    }

    public java.util.Set<String> available() {
        return java.util.Set.copyOf(algorithms.keySet());
    }

    public Algorithm driver() {
        return driver(defaultDriver);
    }

    public Algorithm driver(String name) {
        Algorithm algorithm = algorithms.get(name);
        if (algorithm == null) {
            throw new IllegalStateException(
                    "没有名为 \"" + name + "\" 的密码算法。已注册:" + algorithms.keySet());
        }
        return algorithm;
    }

    // ================= 转发 =================

    public String make(String plaintext) {
        return driver().make(plaintext);
    }

    /**
     * 校验。
     *
     * <p>**按哈希自身的前缀选算法**,而不是用当前默认算法。
     * 这样从 bcrypt 迁到 argon2id 之后,老用户仍然能登录 ——
     * 否则换算法等于把所有人锁在门外。
     */
    public boolean check(String plaintext, String hash) {
        if (plaintext == null || hash == null) return false;

        Algorithm algorithm = detect(hash);
        return algorithm != null && algorithm.check(plaintext, hash);
    }

    public boolean needsRehash(String hash) {
        if (hash == null) return true;

        Algorithm algorithm = detect(hash);
        // 不是当前默认算法产出的 → 需要重算(这正是迁移算法的方式)
        if (algorithm == null) return true;
        if (!algorithm.name().equals(defaultDriver)) return true;
        return algorithm.needsRehash(hash);
    }

    public HashInfo info(String hash) {
        Algorithm algorithm = detect(hash);
        return new HashInfo(
                algorithm == null ? "unknown" : algorithm.name(),
                algorithm != null && algorithm.needsRehash(hash),
                algorithm == null || !algorithm.name().equals(defaultDriver));
    }

    /** 从哈希前缀认出算法;认不出返回 null */
    private Algorithm detect(String hash) {
        for (Algorithm algorithm : algorithms.values()) {
            if (algorithm.recognizes(hash)) return algorithm;
        }
        return null;
    }

    /**
     * 探测可选依赖并注册。
     *
     * <p>用反射判断 classpath 而不是直接 import —— 后者会让
     * {@code spring-security-crypto} 从 optional 变成硬依赖。
     */
    private void registerIfAvailable() {
        if (classPresent("org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder")) {
            register(new BcryptAlgorithm(12));
        }
        if (classPresent("org.springframework.security.crypto.argon2.Argon2PasswordEncoder")
                && classPresent("org.bouncycastle.crypto.generators.Argon2BytesGenerator")) {
            register(new Argon2Algorithm());
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, HashManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * 一个哈希算法。
     *
     * <p>{@link #recognizes} 是关键:它让「多种算法共存」成为可能,
     * 从而让换算法成为一次平滑迁移而不是一次全员锁门。
     */
    public interface Algorithm {
        String name();

        /** 这个哈希是不是本算法产出的 —— 看前缀即可 */
        boolean recognizes(String hash);

        String make(String plaintext);

        boolean check(String plaintext, String hash);

        boolean needsRehash(String hash);
    }

    public record HashInfo(String algorithm, boolean parametersOutdated,
                           boolean algorithmOutdated) {

        public boolean needsRehash() {
            return parametersOutdated || algorithmOutdated;
        }
    }
}
