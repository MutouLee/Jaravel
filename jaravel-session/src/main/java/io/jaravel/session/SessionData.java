package io.jaravel.session;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.jaravel.support.Cast;

/**
 * 一个请求期间的会话数据。
 *
 * <p>**不是线程安全的** —— 它属于单个请求,而请求在 Jaravel 里绑定到一个
 * 虚拟线程。跨请求共享一个实例是使用错误(会话数据会互相覆盖),
 * 所以这里不加锁:加了锁反而会掩盖这个错误。
 *
 * <h2>flash 的生命周期</h2>
 * <p>「只在下一个请求可用」靠两个集合实现:
 * <ul>
 *   <li>{@code newFlash} —— 本次请求写入的,下个请求可读;</li>
 *   <li>{@code oldFlash} —— 上个请求写入的,本次可读,请求结束时删除。</li>
 * </ul>
 * <p>请求结束时的 {@code ageFlash()} 做一次轮转。没有这个两段式结构,
 * 「本次 flash 的值在本次就被删掉」或「flash 永远不过期」二者必犯一个。
 */
public final class SessionData {

    private static final String FLASH_NEW = "_flash.new";
    private static final String FLASH_OLD = "_flash.old";
    private static final String TOKEN = "_token";

    private String id;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private boolean dirty;

    public SessionData(String id) {
        this.id = id;
    }

    public SessionData(String id, Map<String, Object> attributes) {
        this.id = id;
        if (attributes != null) this.attributes.putAll(attributes);
    }

    public static String generateId() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String id() {
        return id;
    }

    /** 是否被改过。没改过就不必写回存储 —— 省掉绝大多数请求的一次写。 */
    public boolean isDirty() {
        return dirty;
    }

    public Map<String, Object> all() {
        return Map.copyOf(attributes);
    }

    /** 给存储层用的原始数据(含 flash 簿记键) */
    public Map<String, Object> raw() {
        return new LinkedHashMap<>(attributes);
    }

    // ================= 读 =================

    public Optional<Object> get(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) return Optional.empty();
        if (type.isInstance(value)) return Optional.of(type.cast(value));
        try {
            return Optional.ofNullable(Cast.to(value, type));
        } catch (RuntimeException e) {
            return Optional.ofNullable(Json.convert(value, type));
        }
    }

    public <T> T get(String key, Class<T> type, T fallback) {
        return get(key, type).orElse(fallback);
    }

    /** 存在且非 null */
    public boolean has(String key) {
        return attributes.get(key) != null;
    }

    /** 存在(可以是 null)—— 与 {@link #has} 的区别对「显式存了 null」有意义 */
    public boolean exists(String key) {
        return attributes.containsKey(key);
    }

    // ================= 写 =================

    public void put(String key, Object value) {
        requireNotReserved(key, "put");
        attributes.put(key, value);
        dirty = true;
    }

    public void putAll(Map<String, ?> values) {
        values.forEach(this::put);
    }

    public <T> Optional<T> pull(String key, Class<T> type) {
        var value = get(key, type);
        if (value.isPresent()) forget(key);
        return value;
    }

    public void forget(String... keys) {
        for (String key : keys) {
            if (attributes.remove(key) != null) dirty = true;
        }
    }

    /** 清空数据但**保留 session id** */
    public void flush() {
        attributes.clear();
        dirty = true;
    }

    /** 往数组型键里追加 —— {@code push("recent.items", id)} */
    @SuppressWarnings("unchecked")
    public void push(String key, Object value) {
        requireNotReserved(key, "push");
        Object existing = attributes.get(key);

        List<Object> list;
        if (existing instanceof List<?> l) {
            list = new ArrayList<>(l);
        } else if (existing == null) {
            list = new ArrayList<>();
        } else {
            throw new IllegalStateException("""
                    会话键 "%s" 里存的是 %s,不是数组,不能 push。
                      修复:换个键名,或先 forget("%s")。"""
                    .formatted(key, existing.getClass().getSimpleName(), key));
        }
        list.add(value);
        attributes.put(key, list);
        dirty = true;
    }

    public long increment(String key) {
        return increment(key, 1);
    }

    public long increment(String key, long delta) {
        long base = get(key, Long.class, 0L);
        long updated = base + delta;
        put(key, updated);
        return updated;
    }

    // ================= flash =================

    /**
     * 写一个只在**下一个**请求可读的值。
     *
     * <p>注意:本次请求里立刻 {@code get} 也能读到 —— 这与 Laravel 一致,
     * 因为「设置后在同一请求里渲染视图」是常见用法(控制器 flash,视图读)。
     */
    public void flash(String key, Object value) {
        attributes.put(key, value);
        newFlashKeys().add(key);
        dirty = true;
    }

    /** 把整个输入闪存起来,供校验失败后回填表单 */
    public void flashInput(Map<String, ?> input) {
        flash("_old_input", new LinkedHashMap<String, Object>(input));
    }

    public Optional<Object> oldInput(String key) {
        Object old = attributes.get("_old_input");
        if (old instanceof Map<?, ?> map) return Optional.ofNullable(map.get(key));
        return Optional.empty();
    }

    /** 把本次能读到的 flash 再保留一个请求 */
    public void reflash() {
        var keys = newFlashKeys();
        keys.addAll(oldFlashKeys());
        dirty = true;
    }

    /** 保留指定的 flash 键 */
    public void keep(String... keys) {
        newFlashKeys().addAll(List.of(keys));
        dirty = true;
    }

    /**
     * 请求结束时轮转 flash。
     *
     * <p>由 {@code StartSession} 中间件在写回存储**之前**调用。
     * 顺序很重要:先轮转再写回,否则 newFlash 不会被持久化,
     * 下个请求读不到任何 flash。
     */
    public void ageFlash() {
        var fresh = new LinkedHashSet<>(newFlashKeys());

        // 删掉上一轮的 flash,但**跳过本轮又被 keep/reflash 续上的** ——
        // 不做这个差集的话,keep("status") 刚续上的键会在同一次轮转里被删掉,
        // 于是 keep 与 reflash 完全失效(测试 keepExtendsOneMore 抓到的就是这个)。
        for (String key : oldFlashKeys()) {
            if (!fresh.contains(key)) attributes.remove(key);
        }

        // 本轮的降级为「上一轮」
        attributes.put(FLASH_OLD, new ArrayList<>(fresh));
        attributes.put(FLASH_NEW, new ArrayList<>());
        dirty = true;
    }

    @SuppressWarnings("unchecked")
    private Set<String> newFlashKeys() {
        Object raw = attributes.get(FLASH_NEW);
        if (raw instanceof Set) return (Set<String>) raw;

        var set = new LinkedHashSet<String>();
        if (raw instanceof List<?> list) {
            list.forEach(k -> set.add(String.valueOf(k)));
        }
        attributes.put(FLASH_NEW, set);
        return set;
    }

    @SuppressWarnings("unchecked")
    private Set<String> oldFlashKeys() {
        Object raw = attributes.get(FLASH_OLD);
        if (raw instanceof Set) return (Set<String>) raw;

        var set = new LinkedHashSet<String>();
        if (raw instanceof List<?> list) {
            list.forEach(k -> set.add(String.valueOf(k)));
        }
        attributes.put(FLASH_OLD, set);
        return set;
    }

    // ================= 安全 =================

    /**
     * CSRF 令牌。不存在就生成一个。
     *
     * <p>每个会话一个长期令牌,而不是每个表单一个一次性令牌。后者更严格,
     * 但会让「同时开两个标签页」的第二个标签提交失败 —— 这是 Laravel
     * 当年也做过的权衡。
     */
    public String token() {
        Object existing = attributes.get(TOKEN);
        if (existing instanceof String s && !s.isBlank()) return s;
        return regenerateToken();
    }

    public String regenerateToken() {
        String token = generateId();
        attributes.put(TOKEN, token);
        dirty = true;
        return token;
    }

    /**
     * 换一个新的 session id,**保留数据**。
     *
     * <p>登录成功后必须调用 —— 否则攻击者可以在登录前把自己的 session id
     * 塞给受害者(会话固定攻击),受害者登录后那个 id 就携带了已认证状态。
     * 这是 docs/08 §7.2 安全清单里的一项。
     */
    public void regenerate() {
        this.id = generateId();
        regenerateToken();
        dirty = true;
    }

    /** 登出:清数据 + 换 id */
    public void invalidate() {
        flush();
        regenerate();
    }

    /**
     * 保留给框架的键,用户不能写。
     *
     * <p>不拦的话,用户写一个 {@code put("_token", "x")} 就等于关掉了 CSRF 防护,
     * 而且没有任何征兆。
     */
    private static void requireNotReserved(String key, String operation) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("会话键不能为空");
        }
        if (key.equals(TOKEN) || key.equals(FLASH_NEW) || key.equals(FLASH_OLD)) {
            throw new IllegalArgumentException("""
                    "%s" 是 Jaravel 保留的会话键,不能用 %s() 写入。
                      · _token    → 用 regenerateToken();直接改它等于关掉 CSRF 防护
                      · _flash.*  → 用 flash() / keep() / reflash()
                      请换一个键名。"""
                    .formatted(key, operation));
        }
    }

    /** 存储层回填时用,绕过保留键检查 */
    /**
     * 整体替换属性。
     *
     * <p>public 是为了让 {@code jaravel-testing} 能预置会话 ——
     * 它要往存储里写一份**真正的**登录态,好让 StartSession 按正常路径
     * 读出来。绕过 load/persist 去直接塞 SessionData 会让那两段
     * (最容易出问题的)代码在测试里完全不被执行。
     *
     * <p>业务代码不该调它:它会跳过 {@link #put} 的保留键检查。
     */
    public void replaceAll(Map<String, Object> loaded) {
        attributes.clear();
        if (loaded != null) attributes.putAll(loaded);
        dirty = false;
    }

    void markClean() {
        dirty = false;
    }

    @Override public String toString() {
        return "SessionData(" + id + ", " + attributes.size() + " 项"
             + (dirty ? ", 已修改" : "") + ")";
    }
}
