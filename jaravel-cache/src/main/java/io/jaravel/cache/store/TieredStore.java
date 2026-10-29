package io.jaravel.cache.store;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.cache.CacheStore;

/**
 * 两级缓存:L1 本地(快、不共享)+ L2 共享(慢、权威)。
 *
 * <p>读:先问 L1,未命中再问 L2 并回填 L1。
 * 写:同时写两级。删:同时删两级。
 *
 * <h2>一致性边界(必须知道)</h2>
 * <p>**L1 不会感知其它实例对 L2 的修改。** 实例 A 写了 L2,实例 B 的 L1
 * 仍持有旧值,直到 L1 的 TTL 到期。所以:
 * <ul>
 *   <li>L1 的 TTL 必须短({@code l1Ttl},默认 10 秒)—— 它就是「最大不一致窗口」;</li>
 *   <li>适合读多写少、且能容忍秒级陈旧的数据(配置、字典、热点详情);</li>
 *   <li><b>不适合</b>会话、锁、计数器 —— 这些要求立刻可见。</li>
 * </ul>
 *
 * <p>因此 {@link #add} 与 {@link #increment} **只走 L2**:它们的语义是原子性,
 * 而本地副本会破坏原子性。这不是优化,是正确性要求。
 */
public class TieredStore implements CacheStore {

    private final CacheStore l1;
    private final CacheStore l2;
    private final Duration l1Ttl;

    public TieredStore(CacheStore l1, CacheStore l2, Duration l1Ttl) {
        if (l1Ttl == null || l1Ttl.isZero() || l1Ttl.isNegative()) {
            throw new IllegalArgumentException("""
                    两级缓存的 L1 必须有正的 TTL,当前是 %s。
                      L1 的 TTL 就是「其它实例的写入最晚多久才被看到」——
                      设成永久意味着本地副本永不更新,等于缓存了一个
                      再也不会变的旧值。"""
                    .formatted(l1Ttl));
        }
        this.l1 = l1;
        this.l2 = l2;
        this.l1Ttl = l1Ttl;
    }

    @Override public String name() { return "tiered(" + l1.name() + "+" + l2.name() + ")"; }

    @Override public boolean isShared() { return l2.isShared(); }

    @Override
    public Optional<Object> get(String key) {
        var local = l1.get(key);
        if (local.isPresent()) return local;

        var shared = l2.get(key);
        shared.ifPresent(v -> l1.put(key, v, backfillTtl(key)));
        return shared;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        l2.put(key, value, ttl);
        l1.put(key, value, shorterOf(ttl));
    }

    /** 只走 L2 —— 原子性不能由本地副本参与 */
    @Override
    public boolean add(String key, Object value, Duration ttl) {
        boolean inserted = l2.add(key, value, ttl);
        if (inserted) l1.put(key, value, shorterOf(ttl));
        return inserted;
    }

    @Override
    public void forget(String key) {
        l2.forget(key);
        l1.forget(key);
    }

    @Override
    public void flush() {
        l2.flush();
        l1.flush();
    }

    /**
     * 只走 L2,并**让 L1 失效**而不是回填。
     *
     * <p>回填会让下一次 get 读到这个实例看到的计数,而别的实例可能已经加过 ——
     * 计数器的用途(限流、库存)容不下这种偏差。
     */
    @Override
    public long increment(String key, long delta) {
        long updated = l2.increment(key, delta);
        l1.forget(key);
        return updated;
    }

    @Override
    public Map<String, Object> many(List<String> keys) {
        var out = new LinkedHashMap<String, Object>();
        var missing = new java.util.ArrayList<String>();

        for (String key : keys) {
            var local = l1.get(key);
            if (local.isPresent()) out.put(key, local.get());
            else missing.add(key);
        }
        if (!missing.isEmpty()) {
            var shared = l2.many(missing);
            shared.forEach((k, v) -> {
                out.put(k, v);
                l1.put(k, v, l1Ttl);
            });
        }
        return out;
    }

    @Override
    public void putMany(Map<String, Object> values, Duration ttl) {
        l2.putMany(values, ttl);
        l1.putMany(values, shorterOf(ttl));
    }

    @Override
    public void forgetMany(List<String> keys) {
        l2.forgetMany(keys);
        l1.forgetMany(keys);
    }

    /** L1 的 TTL 取「L1 上限」与「条目本身 TTL」的较小值 */
    private Duration shorterOf(Duration ttl) {
        if (ttl == null) return l1Ttl;
        return ttl.compareTo(l1Ttl) < 0 ? ttl : l1Ttl;
    }

    /**
     * 回填 L1 时该用多长的 TTL。
     *
     * <p>**不能直接用 l1Ttl**:一个在 L2 里只剩 80ms 的条目,被回填成活 10 秒后,
     * L2 过期了 L1 还在 —— 读到的是已经不该存在的值。限流计数器因此永不重置,
     * 被限流的用户永久封锁。契约测试的「限流窗口会真的过期」用例抓到了这个。
     *
     * <p>所以取 {@code min(l1Ttl, L2 剩余)}。L2 答不上剩余时长时才退回 l1Ttl ——
     * 那种驱动下这个风险依然存在,只是没有信息可用。
     */
    private Duration backfillTtl(String key) {
        var remaining = l2.ttl(key);
        if (remaining.isEmpty()) return l1Ttl;
        if (remaining.get().isZero()) return l1Ttl;        // 永久条目,用 L1 上限
        return shorterOf(remaining.get());
    }

    @Override
    public java.util.Optional<Duration> ttl(String key) {
        return l2.ttl(key);
    }

    public CacheStore l1() { return l1; }
    public CacheStore l2() { return l2; }
    public Duration l1Ttl() { return l1Ttl; }
}
