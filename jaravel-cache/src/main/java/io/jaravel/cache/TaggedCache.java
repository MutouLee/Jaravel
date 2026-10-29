package io.jaravel.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 标签化缓存:按组失效。
 *
 * <pre>{@code
 * Cache.tags("users", "posts").put("user:1:posts", posts, ttl);
 * Cache.tags("users").flush();     // 失效所有带 users 标签的条目
 * }</pre>
 *
 * <h2>实现:标签版本号,而不是成员清单</h2>
 * <p>直觉做法是「每个标签维护一个成员键的集合,flush 时逐个删」。它有两个
 * 硬伤:集合本身会无界增长(删过的键还留在里面),而且「加成员」与「写值」
 * 不是原子的,崩在中间就会漏删。
 *
 * <p>这里改成:每个标签有一个单调递增的版本号,实际的缓存键里**拼进当前版本**。
 * {@code flush} 只是把版本号 +1 —— 旧键一瞬间全部失联,等 TTL 到了自然淘汰。
 * 代价是旧数据在过期前仍占空间,收益是失效操作 O(1)、原子、无状态泄漏。
 *
 * <h2>与 docs/09 的差异</h2>
 * <p>文档原本规定只有 {@code redis} / {@code caffeine} 支持标签,其它驱动
 * 抛异常。版本号方案只依赖 {@link CacheStore#increment},而那是 SPI 的必备
 * 方法,所以**所有驱动都能支持标签**。文档当初担心的「静默降级导致缓存永不
 * 失效」在这个方案下不存在,所以放开了限制。
 */
public final class TaggedCache {

    private final Repository cache;
    private final List<String> tags;

    TaggedCache(Repository cache, List<String> tags) {
        this.cache = cache;
        // 排序:tags("a","b") 与 tags("b","a") 必须命中同一个条目,
        // 否则同一份数据会按调用写法的不同存成两份
        this.tags = tags.stream().distinct().sorted().toList();
    }

    public List<String> tags() {
        return tags;
    }

    // ================= 读写 =================

    public Optional<Object> get(String key) {
        return cache.get(taggedKey(key));
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return cache.get(taggedKey(key), type);
    }

    public <T> T get(String key, Class<T> type, T fallback) {
        return cache.get(taggedKey(key), type, fallback);
    }

    public boolean has(String key) {
        return cache.has(taggedKey(key));
    }

    public void put(String key, Object value, Duration ttl) {
        cache.put(taggedKey(key), value, ttl);
    }

    public void put(String key, Object value) {
        cache.put(taggedKey(key), value);
    }

    public void forever(String key, Object value) {
        cache.forever(taggedKey(key), value);
    }

    public boolean add(String key, Object value, Duration ttl) {
        return cache.add(taggedKey(key), value, ttl);
    }

    public <T> T remember(String key, Duration ttl, Supplier<T> callback) {
        return cache.remember(taggedKey(key), ttl, callback);
    }

    public <T> T remember(String key, Duration ttl, Class<T> type, Supplier<T> callback) {
        return cache.remember(taggedKey(key), ttl, type, callback);
    }

    public <T> T rememberForever(String key, Supplier<T> callback) {
        return cache.rememberForever(taggedKey(key), callback);
    }

    public void forget(String key) {
        cache.forget(taggedKey(key));
    }

    public <T> Optional<T> pull(String key, Class<T> type) {
        return cache.pull(taggedKey(key), type);
    }

    // ================= 失效 =================

    /**
     * 失效本组标签下的全部条目。
     *
     * <p>语义是「任一标签被 flush,条目即失效」—— 因为键里拼的是**所有**
     * 标签的版本,其中任何一个变了,键就对不上了。这与 Laravel 一致:
     * {@code tags("users","posts")} 写入的条目,{@code tags("users").flush()}
     * 能把它清掉。
     */
    public void flush() {
        for (String tag : tags) {
            cache.increment(versionKey(tag));
        }
    }

    // ================= 键的构造 =================

    /** {@code tag:users:v} —— 存版本号本身 */
    private static String versionKey(String tag) {
        return "tag:" + tag + ":v";
    }

    private long version(String tag) {
        // 没有版本号时视为 0,而不是去初始化它 ——
        // 少一次写入,且并发初始化不会产生两个不同的起始版本
        return cache.get(versionKey(tag), Long.class, 0L);
    }

    /**
     * 实际的缓存键:{@code tagged:<标签名与版本>:<原键>}。
     *
     * <p>版本直接拼进键,所以 {@code get} 只需一次读 —— 但要先读各标签的版本,
     * 即 N+1 次读。标签通常只有一两个,这个代价可接受;要再省就得把版本缓存在
     * 进程内,而那会让「别的实例 flush 了标签」延迟生效 —— 对缓存失效来说,
     * 延迟生效比多一次读危险得多。
     */
    String taggedKey(String key) {
        var sb = new StringBuilder("tagged:");
        for (String tag : tags) {
            sb.append(tag).append('@').append(version(tag)).append('|');
        }
        return sb.append(key).toString();
    }
}
