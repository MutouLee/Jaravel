package io.jaravel.eloquent.cast;

import io.jaravel.eloquent.meta.Cast;

/**
 * 列级加密的**占位实现**。
 *
 * <p>真正的加密需要 {@code APP_KEY} 与密钥管理,属于 jaravel-auth(v0.3)的职责。
 * 在那之前标注 {@code @Cast(Encrypted.class)} 会在**首次使用时**明确报错,
 * 而不是悄悄以明文落库 —— 后者是会上安全公告的那种 bug。
 */
public class Encrypted implements Cast<String, String> {

    @Override
    public String fromDatabase(String raw) {
        throw unavailable();
    }

    @Override
    public String toDatabase(String value) {
        throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
                "@Cast(Encrypted.class) 尚未实现(随 jaravel-auth 在 v0.3 提供)。"
              + "\n  刻意报错而不是以明文落库 —— 后者是会上安全公告的那种 bug。"
              + "\n  当前可选方案:"
              + "\n    · 自己实现 Cast<String,String> 并接上你的 KMS;"
              + "\n    · 或改用数据库的列级加密(如 MySQL 的 AES_ENCRYPT)。");
    }
}
