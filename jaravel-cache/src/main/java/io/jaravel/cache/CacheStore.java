package io.jaravel.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 驱动 SPI。实现一个驱动只需这 8 个方法。
 *
 * <p>刻意做得小:{@code remember} / {@code pull} / 标签 / 锁 全部在
 * {@link Repository} 层用这几个原语实现,**驱动不需要各自实现一遍** ——
 * 否则 6 个驱动就会有 6 份 remember 语义,而它们一定会分叉。
 *
 * <h2>关于 {@link #add}</h2>
 * <p>它必须是**原子的** put-if-absent。原子锁与限流都架在它上面,
 * 「先 has 再 put」的实现会在并发下让两个进程同时拿到锁。
 * 这是整个 SPI 里唯一有并发语义要求的方法,所以单独说明。
 */
public interface CacheStore {

    /** 驱动名,用于报错与诊断 */
    String name();

    Optional<Object> get(String key);

    /** @param ttl null 表示永久 */
    void put(String key, Object value, Duration ttl);

    /**
     * 原子的「仅当不存在时写入」。
     *
     * @return 真正写入了才返回 true
     */
    boolean add(String key, Object value, Duration ttl);

    void forget(String key);

    void flush();

    /**
     * 原子增减。
     *
     * @return 增减后的值
     */
    long increment(String key, long delta);

    // ---------- 批量:默认逐个,驱动可覆盖成一次往返 ----------

    default Map<String, Object> many(List<String> keys) {
        var out = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            get(key).ifPresent(v -> out.put(key, v));
        }
        return out;
    }

    default void putMany(Map<String, Object> values, Duration ttl) {
        values.forEach((k, v) -> put(k, v, ttl));
    }

    default void forgetMany(List<String> keys) {
        keys.forEach(this::forget);
    }

    /**
     * 键的剩余存活时间。
     *
     * <p>默认 {@code empty} 表示「这个驱动答不上来」。两个用途:
     * <ul>
     *   <li><b>两级缓存回填</b>:L1 的 TTL 不能超过 L2 条目的剩余寿命,
     *       否则一个只剩 80ms 的条目会被回填成活 10 秒 —— L2 过期了 L1 还在,
     *       读到的是已经不该存在的值。这是实测踩到的,见
     *       {@code CacheStoreContractTest} 的限流窗口用例;</li>
     *   <li>诊断面板显示「还剩多久过期」。</li>
     * </ul>
     *
     * @return 空 = 驱动不支持查询;{@code Optional.of(ZERO)} = 永久;其余为剩余时长
     */
    default Optional<Duration> ttl(String key) {
        return Optional.empty();
    }

    /**
     * 这个驱动能否做到「写入后立刻对其它进程可见」。
     *
     * <p>决定它能不能当会话存储或分布式锁的后端。{@code caffeine} / {@code array}
     * 是进程内的,多实例部署时用它们存会话会表现为「刷新页面就掉登录」——
     * 一个只在多实例下出现、单机永远测不出来的故障。
     * 所以这个信息必须可查询,而不是靠使用者记住哪些驱动是本地的。
     */
    default boolean isShared() {
        return false;
    }
}
