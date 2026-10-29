package io.jaravel.core;

import java.util.Optional;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.container.Container;

/**
 * 应用全局入口。门面层通过它拿到容器。
 *
 * <p>这是整个框架里唯一的可变全局状态。刻意保持极小:只持有一个容器引用。
 * 所有真正的状态都在容器里。
 */
public final class Application {

    private Application() {}

    private static volatile Container container;
    private static volatile String basePath = ".";
    private static volatile String environment = "production";
    private static volatile boolean debug = false;

    public static void bootstrap(Container c) {
        container = c;
    }

    /** 测试与嵌入场景的逃生口 */
    public static void setContainer(Container c) {
        container = c;
    }

    public static Container container() {
        Container c = container;
        if (c == null) {
            throw new IllegalStateException(
                    "Jaravel 尚未初始化。\n"
                  + "  正常情况下 jaravel-spring-boot-starter 会在启动时自动完成;\n"
                  + "  单元测试中请调用 Application.setContainer(new SimpleContainer())。");
        }
        return c;
    }

    public static boolean isBootstrapped() {
        return container != null;
    }

    /** 清理全局状态。测试的 @AfterEach 应调用。 */
    public static void reset() {
        container = null;
        basePath = ".";
        environment = "production";
        debug = false;
    }

    public static <T> T make(Class<T> type) {
        return container().make(type);
    }

    public static <T> Optional<T> tryMake(Class<T> type) {
        return container().tryMake(type);
    }

    public static ConfigRepository config() {
        return make(ConfigRepository.class);
    }

    // ---------- 环境 ----------

    public static String basePath()           { return basePath; }
    public static void setBasePath(String p)  { basePath = p; }

    public static String environment()        { return environment; }
    public static void setEnvironment(String e) { environment = e; }

    public static boolean isLocal()           { return "local".equals(environment); }
    public static boolean isProduction()      { return "production".equals(environment); }
    public static boolean isTesting()         { return "testing".equals(environment); }

    public static boolean isDebug()           { return debug; }
    public static void setDebug(boolean d)    { debug = d; }
}
