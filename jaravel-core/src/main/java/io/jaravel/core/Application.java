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

    /**
     * 解析不出来就返回空 —— **容器还没起来时也返回空**,而不是抛异常。
     *
     * <p>方法名叫 try 就不该抛。这件事有具体的后果:开发期错误页用它
     * 去取数据库连接与配置来渲染 SQL / 配置面板,而启动失败时容器
     * 恰恰是没起来的 —— 那时让 tryMake 抛异常会让**错误页自己崩掉**,
     * 用户连原始的启动失败原因都看不到。
     */
    public static <T> Optional<T> tryMake(Class<T> type) {
        if (!isBootstrapped()) return Optional.empty();
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
