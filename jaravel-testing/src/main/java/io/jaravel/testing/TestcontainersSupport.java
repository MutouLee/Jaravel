package io.jaravel.testing;

import org.testcontainers.DockerClientFactory;

/**
 * Testcontainers 的环境适配(docs/14 §2.2)。
 *
 * <p>两件事:
 * <ol>
 *   <li>测试类上的 Docker 条件(JUnit 的 {@code @EnabledIf("dockerAvailable")})
 *       应该在**上下文创建之前**判断,而不是让容器启动失败;</li>
 *   <li>Docker Engine 29 不再接受旧版 docker-java 的默认 API 版本。
 *       这里在第一次连 Docker 前设置 {@code api.version},不依赖具体
 *       补丁版本的默认行为(可用 {@code -Dapi.version=...} 或
 *       {@code -Djaravel.testcontainers.api-version=...} 覆盖)。</li>
 * </ol>
 *
 * <p>只应在测试代码里调用;它依赖 Testcontainers(在 jaravel-testing 里是 optional)。
 */
public final class TestcontainersSupport {

    /** Docker 29 的最低 API 版本;更老的 Docker 请覆盖这个值 */
    public static final String DEFAULT_API_VERSION = "1.44";

    private TestcontainersSupport() {
    }

    /** 只在显式开启时跑容器测试(与浏览器测试同一个策略:默认套件不拉镜像) */
    public static boolean enabled() {
        String flag = System.getProperty("jaravel.testcontainers");
        if (flag == null || flag.isBlank()) flag = System.getenv("JARAVEL_TESTCONTAINERS");
        return flag != null && !flag.isBlank()
                && !flag.equals("0") && !flag.equalsIgnoreCase("false");
    }

    /** {@code @EnabledIf("dockerAvailable")} 的实现:没有 Docker 返回 false,不抛异常 */
    public static boolean dockerAvailable() {
        ensureApiVersion();
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** 在第一次连 Docker 前调用;已设置 {@code api.version} 时不动 */
    public static void ensureApiVersion() {
        if (System.getProperty("api.version") != null) return;

        String configured = System.getProperty(
                "jaravel.testcontainers.api-version", DEFAULT_API_VERSION);
        System.setProperty("api.version", configured);
    }
}
