package io.jaravel.core.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配置读取契约。点号路径访问:{@code config.get("database.connections.mysql.port")}。
 */
public interface ConfigRepository {

    Optional<Object> raw(String path);

    boolean has(String path);

    <T> Optional<T> get(String path, Class<T> type);

    <T> T get(String path, Class<T> type, T defaultValue);

    // ---------- 便捷取值器(覆盖 95% 的调用点) ----------

    default String string(String path)                  { return get(path, String.class, null); }
    default String string(String path, String def)      { return get(path, String.class, def); }
    default int integer(String path)                    { return get(path, Integer.class, 0); }
    default int integer(String path, int def)           { return get(path, Integer.class, def); }
    default long longValue(String path, long def)       { return get(path, Long.class, def); }
    default boolean bool(String path)                   { return get(path, Boolean.class, false); }
    default boolean bool(String path, boolean def)      { return get(path, Boolean.class, def); }
    default Duration duration(String path, Duration d)  { return get(path, Duration.class, d); }

    List<String> stringList(String path);

    /** 取出整个子树,用于「遍历所有 connections」这类场景 */
    Map<String, Object> section(String path);

    /** 调试用:扁平化后的全部配置(敏感键已脱敏) */
    Map<String, Object> all();

    /**
     * 这个键的值敏感吗 —— 打码的唯一判据。
     *
     * <h2>★ 为什么它在接口上(而且是 public)</h2>
     * <p>原先这条规则是 {@code MapConfigRepository} 的私有方法,只给
     * {@link #all()} 用。{@code jaravel config:show} 需要同一条规则 ——
     * 因为它两样都要:{@code all()} 打码但**不解析占位符**,
     * {@link #section(String)} 解析但**不打码**。
     *
     * <p>把判据复制一份到命令里,代价是它会漂:将来往这张表里加一个词
     * (比如 {@code private_key})时,只有一处会改,而**另一处漏掉的后果是
     * 密码被打到终端或 CI 日志里**。所以它必须只有一份。
     *
     * <p>按**子串**匹配而不是精确相等:真实的键名是
     * {@code database.connections.mysql.password}、{@code app.key}、
     * {@code services.stripe.secret} —— 精确匹配一个都挡不住。
     * 代价是误报(一个叫 {@code cache.keyPrefix} 的键会被打码),
     * 而这个方向的误报是可以接受的那一边。
     */
    static boolean isSensitive(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    /** 打码用的掩码 —— 长度固定,不泄露原值长度 */
    String MASK = "********";

    List<String> SENSITIVE_WORDS =
            List.of("password", "secret", "key", "token", "credential", "dsn");

    class MissingConfigException extends RuntimeException {
        public MissingConfigException(String message) { super(message); }
    }
}
