package io.jaravel.facades;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.facade.Facade;

/** 配置门面。点号路径访问:{@code Config.string("app.name")} */
public final class Config extends Facade {

    private Config() {}

    private static ConfigRepository repo() {
        return resolve(ConfigRepository.class);
    }

    public static boolean has(String path)                 { return repo().has(path); }
    public static Optional<Object> raw(String path)         { return repo().raw(path); }
    public static <T> Optional<T> get(String p, Class<T> t) { return repo().get(p, t); }

    public static String string(String path)                { return repo().string(path); }
    public static String string(String path, String def)    { return repo().string(path, def); }
    public static int integer(String path)                  { return repo().integer(path); }
    public static int integer(String path, int def)          { return repo().integer(path, def); }
    public static boolean bool(String path)                 { return repo().bool(path); }
    public static boolean bool(String path, boolean def)    { return repo().bool(path, def); }
    public static Duration duration(String p, Duration d)   { return repo().duration(p, d); }
    public static List<String> list(String path)            { return repo().stringList(path); }
    public static Map<String, Object> section(String path)  { return repo().section(path); }

    /** 调试用:全部配置(敏感键已脱敏) */
    public static Map<String, Object> all()                 { return repo().all(); }
}
