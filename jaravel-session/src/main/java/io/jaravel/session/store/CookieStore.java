package io.jaravel.session.store;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.core.crypt.Encrypter;
import io.jaravel.session.SessionStore;

/**
 * 把整个会话加密后存进客户端 cookie —— 服务端零状态。
 *
 * <p>水平扩展最简单的方案:没有共享存储、没有粘性会话、实例随便加减。
 *
 * <h2>这个驱动的特殊之处</h2>
 * <p>其它驱动按 id 存取服务端数据,而它**数据就在 id 的位置上**。
 * {@link SessionStore} 的 {@code read(id)} 语义在这里变成「解密这段密文」。
 * 为了不把这个差异泄露到中间件里,这里用一个请求级的暗箱:
 * {@code StartSession} 把 cookie 原文通过 {@link #stage} 交进来,
 * 再用它作为「id」去 read。
 *
 * <h2>硬限制:4KB</h2>
 * <p>浏览器对单个 cookie 的上限是 4096 字节(含名字与属性),
 * 而加密 + base64 会让数据膨胀约 1.4 倍。所以实际可用 ≈ 2.5KB。
 * 超了会被浏览器**静默丢弃** —— 症状是「会话莫名丢失,且只在某些用户身上」
 * (购物车多几件就超了)。因此这里**超限直接报错**,不让它变成线上之谜。
 */
public class CookieStore implements SessionStore {

    /** 浏览器的 cookie 上限;留出名字与属性的余量 */
    private static final int MAX_PAYLOAD_BYTES = 3500;

    private final Encrypter encrypter;

    /**
     * 本次请求的待写出载荷。
     *
     * <p>{@code write} 拿不到 HTTP 响应,所以把密文暂存在这里,
     * 由 {@code StartSession} 取走写进 Set-Cookie。
     * 按线程存放 —— 每个请求一个虚拟线程,互不干扰。
     */
    private static final ThreadLocal<String> PENDING = new ThreadLocal<>();

    /** 解密失败的次数,用于诊断「换了 APP_KEY 后全员掉线」 */
    private final Map<String, Integer> diagnostics = new ConcurrentHashMap<>();

    public CookieStore(Encrypter encrypter) {
        this.encrypter = encrypter;
    }

    @Override public String name() { return "cookie"; }

    /** 数据跟着请求走,天然「共享」—— 任何实例都能读 */
    @Override public boolean isShared() { return true; }

    /**
     * @param payload cookie 里的密文(而不是服务端的 session id)
     */
    @Override
    public Optional<Map<String, Object>> read(String payload) {
        if (payload == null || payload.isBlank()) return Optional.empty();

        var plain = encrypter.tryDecrypt(payload);
        if (plain.isEmpty()) {
            // 解不开当成「没有会话」。换过 APP_KEY、cookie 被改、
            // 或 cookie 来自别的应用都会走到这里,都不该让用户看到 500。
            diagnostics.merge("decrypt-failed", 1, Integer::sum);
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = Json.mapper().readValue(
                    plain.get(), Map.class);

            // 校验过期:cookie 的 Max-Age 由浏览器执行,但攻击者可以保留
            // 一个过期 cookie 继续发。服务端必须自己再判一次。
            Object expiresAt = data.get("_expires_at");
            if (expiresAt instanceof Number n && System.currentTimeMillis() >= n.longValue()) {
                return Optional.empty();
            }
            return Optional.of(new LinkedHashMap<>(data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            diagnostics.merge("json-failed", 1, Integer::sum);
            return Optional.empty();
        }
    }

    @Override
    public void write(String id, Map<String, Object> data, Duration lifetime) {
        var payload = new LinkedHashMap<>(data);
        payload.put("_expires_at", lifetime == null
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + lifetime.toMillis());

        String json;
        try {
            json = Json.mapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("""
                    会话里有无法序列化成 JSON 的值,cookie 驱动写不出去:%s
                      cookie 驱动要求会话内容可 JSON 化。
                      放不进去的对象(持有连接、流、Lambda 的)请只存它的 id,
                      或改用 redis / database 会话驱动。"""
                    .formatted(e.getOriginalMessage()), e);
        }

        String encrypted = encrypter.encrypt(json);
        int bytes = encrypted.getBytes(StandardCharsets.UTF_8).length;

        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new SessionTooLargeException(bytes, MAX_PAYLOAD_BYTES, data.keySet());
        }
        PENDING.set(encrypted);
    }

    @Override
    public void destroy(String id) {
        PENDING.set("");       // 空载荷 → StartSession 会写一个过期的 cookie
    }

    // ================= 与中间件的交接 =================

    /** 取走本次请求要写出的载荷;没有改动时返回空 */
    public static Optional<String> takePending() {
        String pending = PENDING.get();
        PENDING.remove();
        return Optional.ofNullable(pending);
    }

    /** 清掉遗留状态 —— 线程复用时必须做,否则会把上个请求的会话写给这个请求 */
    public static void clearPending() {
        PENDING.remove();
    }

    public Map<String, Integer> diagnostics() {
        return Map.copyOf(diagnostics);
    }

    /**
     * 会话超过 cookie 容量。
     *
     * <p>**报错而不是静默截断**:浏览器丢弃超大 cookie 是静默的,
     * 症状是「部分用户随机掉登录」,且只在数据多到一定程度才出现 ——
     * 这类问题可以查几天。
     */
    public static class SessionTooLargeException extends RuntimeException {
        public SessionTooLargeException(int actual, int limit, java.util.Set<String> keys) {
            super("""
                    会话加密后 %d 字节,超过 cookie 驱动的上限 %d。
                      浏览器会**静默丢弃**超大 cookie —— 症状是「部分用户随机掉登录」,
                      所以这里直接报错而不是让它悄悄发生。
                      当前会话里的键:%s
                      处理方式:
                        · 只在会话里放 id,数据放缓存或数据库;
                        · 或把 session.driver 换成 redis / database(服务端存储无此限制)。"""
                    .formatted(actual, limit, keys));
        }
    }

    /** cookie 驱动自己的 JSON 门面 */
    private static final class Json {
        private static final com.fasterxml.jackson.databind.ObjectMapper M = create();

        static com.fasterxml.jackson.databind.ObjectMapper mapper() { return M; }

        private static com.fasterxml.jackson.databind.ObjectMapper create() {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            m.findAndRegisterModules();
            m.configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);
            return m;
        }
    }
}
