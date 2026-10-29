package io.jaravel.auth.hash;

/**
 * 密码哈希门面。
 *
 * <pre>{@code
 * String hash = Hash.make(password);
 * if (Hash.check(input, user.password)) { ... }
 * if (Hash.needsRehash(user.password)) { user.password = Hash.make(input); }
 * }</pre>
 */
public final class Hash {

    private Hash() {}

    private static volatile HashManager manager = new HashManager();

    public static void use(HashManager configured) {
        manager = configured;
    }

    public static HashManager manager() {
        return manager;
    }

    public static String make(String plaintext) {
        return manager.make(plaintext);
    }

    public static boolean check(String plaintext, String hash) {
        return manager.check(plaintext, hash);
    }

    public static boolean needsRehash(String hash) {
        return manager.needsRehash(hash);
    }

    public static HashManager.HashInfo info(String hash) {
        return manager.info(hash);
    }

    public static HashManager.Algorithm driver(String name) {
        return manager.driver(name);
    }
}
