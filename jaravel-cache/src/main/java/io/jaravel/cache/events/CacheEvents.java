package io.jaravel.cache.events;

import java.time.Duration;

/**
 * 缓存事件(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class CacheMetrics {
 *     public void handle(CacheEvents.Hit e)    { metrics.increment("cache.hit"); }
 *     public void handle(CacheEvents.Missed e) { metrics.increment("cache.miss"); }
 * }
 * }</pre>
 *
 * <h2>这是最热的那条路径</h2>
 * <p>每次缓存读写各一次。所以发出它们的地方一律先问
 * {@code Event.listening(...)} —— 没人监听时连 record 都不构造。
 *
 * <p>更要紧的是:**key 用的是去掉前缀的那个**。
 * 缓存有一个配置化的前缀({@code cache.prefix}),监听器看到带前缀的 key
 * 没有意义 —— 它想按业务 key 做统计,而前缀会随环境变
 * (dev/prod 常配不同前缀),于是同一个指标在两个环境下名字不同。
 *
 * <h2>Hit 不带值</h2>
 * <p>刻意不带:缓存里存的可能是一个几百 KB 的对象,而一个
 * 「统计命中率」的监听器不需要它。带上它意味着每次命中都多一次
 * 引用传递,并且让一个监听器有机会**修改**那个共享对象
 * (array / caffeine 驱动存的是对象引用,不是副本)。
 */
public final class CacheEvents {

    private CacheEvents() {
    }

    /**
     * 命中。
     *
     * @param key 业务 key(**不带** cache.prefix)
     */
    public record Hit(String key) {
    }

    /** 未命中 */
    public record Missed(String key) {
    }

    /**
     * 写入。
     *
     * @param ttl 存活时间,null = 永久
     */
    public record KeyWritten(String key, Duration ttl) {
        public boolean isForever() {
            return ttl == null;
        }
    }

    /** 删除。**不区分「本来有没有」** —— 驱动层的 forget 不返回这个信息 */
    public record KeyForgotten(String key) {
    }
}
