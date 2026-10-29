package io.jaravel.session;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 会话存储 SPI。
 *
 * <p>只有三个方法 —— flash、CSRF、id 轮换全部在 {@link SessionData} 层实现,
 * 驱动只负责「按 id 存取一个 Map」。
 */
public interface SessionStore {

    String name();

    /** 读不到(不存在或已过期)返回空 */
    Optional<Map<String, Object>> read(String id);

    void write(String id, Map<String, Object> data, Duration lifetime);

    void destroy(String id);

    /**
     * 这个驱动能否跨进程共享。
     *
     * <p>多实例部署时用进程内驱动存会话,症状是「刷新页面随机掉登录」——
     * 请求打到另一个实例就读不到会话。单机开发永远测不出来,所以
     * 启动时要能检查这件事。
     */
    default boolean isShared() {
        return true;
    }

    /**
     * 清理过期会话。
     *
     * <p>有 TTL 的驱动(cache / redis)不需要做什么;database 驱动需要
     * 定期删行,否则 sessions 表会无限增长。
     */
    default int purgeExpired(Duration lifetime) {
        return 0;
    }
}
