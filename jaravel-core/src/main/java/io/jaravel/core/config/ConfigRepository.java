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

    class MissingConfigException extends RuntimeException {
        public MissingConfigException(String message) { super(message); }
    }
}
