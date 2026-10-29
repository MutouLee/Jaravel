package io.jaravel.auth.hash;

import io.jaravel.core.crypt.Hasher;

/** 默认算法:JDK 内置的 PBKDF2-HMAC-SHA512,零外部依赖 */
public final class Pbkdf2Algorithm implements HashManager.Algorithm {

    private final Hasher hasher;

    public Pbkdf2Algorithm(Hasher hasher) {
        this.hasher = hasher;
    }

    @Override public String name() { return "pbkdf2"; }

    @Override public boolean recognizes(String hash) {
        return hash != null && hash.startsWith("$pbkdf2-sha512$");
    }

    @Override public String make(String plaintext) { return hasher.make(plaintext); }

    @Override public boolean check(String plaintext, String hash) {
        return hasher.check(plaintext, hash);
    }

    @Override public boolean needsRehash(String hash) { return hasher.needsRehash(hash); }
}
