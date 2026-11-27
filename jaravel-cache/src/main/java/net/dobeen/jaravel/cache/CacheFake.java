package net.dobeen.jaravel.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import net.dobeen.jaravel.cache.store.ArrayStore;

/**
 * 测试用的记录型缓存(docs/14 §4)。
 *
 * <pre>{@code
 * var cache = Cache.fake();
 *
 * service.dashboard(userId);
 *
 * cache.assertMissed("dashboard:7");     // 第一次是未命中
 * cache.assertPut("dashboard:7");        // 然后被写进去了
 *
 * service.dashboard(userId);
 * cache.assertHit("dashboard:7");        // 第二次命中,没再算一遍
 * }</pre>
 *
 * <h2>★ 它与「换成 array 驱动」的区别</h2>
 * <p>只要隔离,{@code Cache.use(CacheManager.inMemory())} 就够了。
 * fake 多出来的是**记录**:哪个键命中、哪个键未命中、哪个键被写过/删过。
 * 「加了缓存」这件事唯一值得测的断言就是「第二次没有再去算」,
 * 而那件事只能从命中记录上看出来 —— 从返回值上看不出来,
 * 两次调用的返回值本来就该一样。
 *
 * <h2>★ 一条必须知道的边界:注入了 Repository 的代码不受影响</h2>
 * <p>fake 换掉的是 {@link CacheManager} 的默认 store,所以走
 * {@code Cache} 门面(每次都经 manager 解析)的代码会被它接住;
 * 而**构造注入了 {@code Repository} bean** 的代码持有的是旧实例,
 * 它不会被换掉。
 *
 * <p>这不完全是缺点:框架自己的会话存储、限流器、队列的唯一性锁都是
 * 注入式的 —— 一个会把它们一起换掉的 fake 会让「测一下缓存」顺带
 * 清掉当前测试的登录态。要连注入的那份也换,直接在测试里构造
 * {@code new Repository(new CacheFake(...))} 并注入它。
 */
public final class CacheFake implements CacheStore {

    /** fake 注册到 {@link CacheManager} 时用的 store 名 */
    public static final String STORE_NAME = "fake";

    /** 一次操作 */
    public enum Kind { HIT, MISS, PUT, FORGET, FLUSH, INCREMENT }

    public record Op(Kind kind, String key) {
    }

    private final CacheStore delegate;

    /**
     * manager 的键前缀,按 {@code Repository.prefixed} 的拼法(带冒号)。
     *
     * <p>记录里要去掉它:断言写的是业务里那个键({@code "dashboard:7"}),
     * 而驱动看到的是 {@code "myapp_cache:dashboard:7"}。让用户在断言里把
     * 前缀拼出来,等于让每条断言都依赖一个配置项。
     */
    private final String fullPrefix;

    private final List<Op> ops = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CacheFake() {
        this(new ArrayStore(), "");
    }

    public CacheFake(CacheStore delegate, String prefix) {
        this.delegate = delegate;
        this.fullPrefix = prefix == null || prefix.isEmpty() ? "" : prefix + ":";
    }

    @Override
    public String name() {
        return "fake";
    }

    private String strip(String key) {
        return !fullPrefix.isEmpty() && key.startsWith(fullPrefix)
                ? key.substring(fullPrefix.length())
                : key;
    }

    // ================= SPI =================

    @Override
    public Optional<Object> get(String key) {
        Optional<Object> found = delegate.get(key);
        ops.add(new Op(found.isPresent() ? Kind.HIT : Kind.MISS, strip(key)));
        return found;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        delegate.put(key, value, ttl);
        ops.add(new Op(Kind.PUT, strip(key)));
    }

    @Override
    public boolean add(String key, Object value, Duration ttl) {
        boolean written = delegate.add(key, value, ttl);
        if (written) ops.add(new Op(Kind.PUT, strip(key)));
        return written;
    }

    @Override
    public void forget(String key) {
        delegate.forget(key);
        ops.add(new Op(Kind.FORGET, strip(key)));
    }

    @Override
    public void flush() {
        delegate.flush();
        ops.add(new Op(Kind.FLUSH, ""));
    }

    @Override
    public long increment(String key, long delta) {
        long value = delegate.increment(key, delta);
        ops.add(new Op(Kind.INCREMENT, strip(key)));
        return value;
    }

    @Override
    public Map<String, Object> many(List<String> keys) {
        // 不转发给 delegate.many:那会绕过 get 的记录,于是
        // Cache.many(...) 的命中/未命中在记录里消失
        var out = new java.util.LinkedHashMap<String, Object>();
        for (String key : keys) {
            get(key).ifPresent(v -> out.put(key, v));
        }
        return out;
    }

    @Override
    public Optional<Duration> ttl(String key) {
        return delegate.ttl(key);
    }

    // ================= 记录 =================

    public List<Op> operations() {
        return List.copyOf(ops);
    }

    public List<String> keysPut() {
        return ops.stream().filter(o -> o.kind() == Kind.PUT)
                .map(Op::key).distinct().toList();
    }

    public int hits() {
        return (int) ops.stream().filter(o -> o.kind() == Kind.HIT).count();
    }

    public int misses() {
        return (int) ops.stream().filter(o -> o.kind() == Kind.MISS).count();
    }

    /** 清空记录,保留已缓存的值 —— 用于「第二次调用才是我要测的那次」 */
    public CacheFake forgetOperations() {
        ops.clear();
        return this;
    }

    // ================= 断言 =================

    public void assertHit(String key) {
        if (has(Kind.HIT, key)) return;
        throw new AssertionError("""
                键 "%s" 没有命中过。%s
                  「加了缓存」唯一值得测的断言是「第二次没有再去算」——
                  若第一次调用也算在内,先 forgetOperations() 再跑第二次。"""
                .formatted(key, recorded()));
    }

    public void assertMissed(String key) {
        if (has(Kind.MISS, key)) return;
        throw new AssertionError("键 \"%s\" 没有未命中过。%s".formatted(key, recorded()));
    }

    public void assertPut(String key) {
        if (has(Kind.PUT, key)) return;
        throw new AssertionError("键 \"%s\" 没有被写入过。%s".formatted(key, recorded()));
    }

    public void assertForgotten(String key) {
        if (has(Kind.FORGET, key)) return;
        throw new AssertionError("键 \"%s\" 没有被删除过。%s".formatted(key, recorded()));
    }

    /** 当前**存着**这个键(与「写过」不同:写过之后可能已被删或已过期) */
    public void assertHas(String key) {
        if (delegate.get(fullPrefix + key).isPresent()) return;
        throw new AssertionError("""
                缓存里现在没有键 "%s"。%s
                  「写过」与「现在还在」是两件事(可能已被删或已过期),
                  要断言前者用 assertPut。"""
                .formatted(key, recorded()));
    }

    public void assertMissing(String key) {
        if (delegate.get(fullPrefix + key).isEmpty()) return;
        throw new AssertionError("缓存里仍有键 \"%s\"。%s".formatted(key, recorded()));
    }

    public void assertNothingPut() {
        List<String> put = keysPut();
        if (put.isEmpty()) return;
        throw new AssertionError("期望什么都没写进缓存,实际写了 %s。%s"
                .formatted(put, recorded()));
    }

    private boolean has(Kind kind, String key) {
        return ops.stream().anyMatch(o -> o.kind() == kind && o.key().equals(key));
    }

    /** 失败消息里附上实际发生了什么 —— 否则查不出是键拼错还是压根没走到 */
    private String recorded() {
        if (ops.isEmpty()) return "\n  已记录:(空 —— fake 之后没有任何缓存操作)";
        return ops.stream()
                .map(o -> o.kind() + (o.key().isEmpty() ? "" : "(" + o.key() + ")"))
                .collect(Collectors.joining(", ", "\n  已记录:", ""));
    }
}
