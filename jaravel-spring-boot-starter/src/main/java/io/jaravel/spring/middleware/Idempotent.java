package io.jaravel.spring.middleware;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import io.jaravel.cache.Repository;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 幂等性中间件(docs/12 §6.5)。
 *
 * <pre>{@code
 * r.post("/payments", PaymentController::store).middleware("idempotent");
 * // 客户端:Idempotency-Key: 9c1a…
 * }</pre>
 *
 * <p>同一个 key 的重复请求返回**第一次的响应**,不重复执行。这是支付类
 * 接口的硬需求 —— 网络超时后客户端重试,而服务端其实已经扣过款。
 *
 * <h2>并发:add 是抢占,不是「先查再执行」</h2>
 * <p>两个请求同时带着同一个 key 到达时,先到的 {@code cache.add} 成功并
 * 执行;后到的发现 key 已存在,如果拿到的是「处理中」标记就返回
 * **409 + Retry-After**,而不是也执行一遍。先查再执行会让两个请求都
 * 进去,那正是幂等要防的事。
 *
 * <h2>哪些响应会缓存</h2>
 * <p>2xx/3xx/4xx 都缓存(客户端用同一个 key 重试同一个失败请求,应当拿到
 * 同样的结果);**5xx 不缓存** —— 那不是一次确定的结果,应允许重试。
 * 处理中途抛异常也会删掉占位,不让 key 卡住。
 *
 * <h2>⚠ 必须是共享缓存</h2>
 * <p>用 caffeine/array 时,幂等只在**单进程内**成立;两个实例会各执行
 * 一遍。多实例部署请把 {@code cache.default} 配成 redis / database。
 * 构造时若发现默认 store 非共享,会打一次警告。
 *
 * <h2>响应快照的限制</h2>
 * <p>中间件在响应写出**之前**拿到 {@link Response},所以缓存的是
 * body 的结构(Text / Json 的值 / ViewRef / Bytes / Negotiated),不是
 * 最终字节。JSON 的值必须是 JSON 可表示的(Map / record / 基本类型);
 * {@code JsonResource} 这类「由 writer 调 toArray」的对象存进去的会是
 * 它的字段,重放结果可能与首次不同。支付类接口返回普通 DTO 即可。
 * 视图会重新渲染;Set-Cookie 不重放。
 */
public class Idempotent implements Middleware.Parameterized {

    private static final String IN_FLIGHT = "__jaravel_idempotent_in_flight__";
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Repository cache;

    private Duration ttl;
    private Duration inFlightTtl;
    private String header;
    private boolean warned;

    public Idempotent(Repository cache, ConfigRepository config) {
        this.cache = cache;
        this.ttl = config.duration("api.idempotency.ttl", Duration.ofHours(24));
        this.inFlightTtl = config.duration("api.idempotency.in-flight-ttl",
                Duration.ofSeconds(60));
        this.header = config.string("api.idempotency.header", "Idempotency-Key");
    }

    /**
     * {@code idempotent:秒数} 覆盖 TTL;第二个参数覆盖请求头名。
     *
     * <p>参数只影响这一条路由;不写就用 {@code api.idempotency.*}。
     */
    @Override
    public void configure(String... params) {
        if (params.length > 0) ttl = Duration.ofSeconds(parse(params[0], "TTL"));
        if (params.length > 1) header = params[1];
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        String method = request.method().toUpperCase(Locale.ROOT);
        if (!MUTATING.contains(method)) return next.apply(request);

        Optional<String> raw = request.header(header);
        if (raw.isEmpty() || raw.get().isBlank()) {
            // 没带 key = 不要求幂等 —— 这是客户端自己的选择。
            // 强制要求会打断所有不关心这件事的调用方。
            return next.apply(request);
        }

        String idempotencyKey = raw.get().trim();
        if (idempotencyKey.length() > 255 || hasControlChars(idempotencyKey)) {
            return Response.json(Map.of("message",
                    "Idempotency-Key 不能超过 255 个字符,也不能含控制字符"))
                    .status(400);
        }

        warnIfNotShared();

        String key = cacheKey(method, request.path(), idempotencyKey);

        Optional<Object> prior = cache.get(key);
        if (prior.isPresent()) return replay(request, prior.get());

        if (!cache.add(key, IN_FLIGHT, inFlightTtl)) {
            // 抢占失败:可能是别的请求刚刚写完了响应,也可能还在处理中
            var after = cache.get(key);
            if (after.isPresent()) return replay(request, after.get());
            return inFlight();
        }

        Response response;
        try {
            response = next.apply(request);
        } catch (RuntimeException | Error e) {
            cache.forget(key);      // 失败不占位 —— 允许用同一个 key 重试
            throw e;
        }

        if (response.status() >= 500) {
            cache.forget(key);      // 5xx 不是确定结果
            return response;
        }

        cache.put(key, snapshot(request, response), ttl);
        return response;
    }

    // ================= 快照 =================

    private Map<String, Object> snapshot(Request request, Response response) {
        var headers = new LinkedHashMap<String, List<String>>();
        response.headers().forEach((name, values) -> {
            // 由 writer 按实际字节重算,缓存它们只会写错
            if (name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Transfer-Encoding")) return;
            headers.put(name, values);
        });

        var snap = new LinkedHashMap<String, Object>();
        snap.put("status", response.status());
        snap.put("contentType", response.contentType());
        snap.put("headers", headers);
        snap.put("body", encodeBody(response.body()));
        snap.put("fingerprint", fingerprint(request));
        return snap;
    }

    @SuppressWarnings("unchecked")
    private Response replay(Request request, Object stored) {
        if (IN_FLIGHT.equals(stored)) return inFlight();
        if (!(stored instanceof Map<?, ?> raw)) {
            return Response.json(Map.of("message",
                    "幂等记录格式不认识(可能是旧版本写的),请换一个 Idempotency-Key"))
                    .status(500);
        }

        Map<String, Object> snap = (Map<String, Object>) raw;

        // 同一个 key 换了一个请求体 → 422。直接返回第一次的响应会让
        // 调用方以为第二笔请求成功了,而它根本没被执行。
        Object storedFingerprint = snap.get("fingerprint");
        if (storedFingerprint != null
                && !storedFingerprint.equals(fingerprint(request))) {
            return Response.json(Map.of("message",
                    "同一个 Idempotency-Key 被用于了不同的请求"))
                    .status(422);
        }

        int status = ((Number) snap.get("status")).intValue();
        Response response = Response.of(decodeBody((Map<String, Object>) snap.get("body")), status);

        Object contentType = snap.get("contentType");
        if (contentType != null) response.contentType(String.valueOf(contentType));

        Object headers = snap.get("headers");
        if (headers instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getValue() instanceof List<?> values)) continue;
                for (Object value : values) {
                    response.header(String.valueOf(entry.getKey()), String.valueOf(value));
                }
            }
        }
        return response;
    }

    private static Map<String, Object> encodeBody(Response.Body body) {
        var out = new LinkedHashMap<String, Object>();
        switch (body) {
            case Response.Body.Text text -> {
                out.put("kind", "text");
                out.put("content", text.content());
            }
            case Response.Body.Json json -> {
                out.put("kind", "json");
                out.put("value", json.value());
            }
            case Response.Body.ViewRef view -> {
                out.put("kind", "view");
                out.put("name", view.name());
                out.put("data", view.data());
            }
            case Response.Body.Bytes bytes -> {
                out.put("kind", "bytes");
                out.put("content", Base64.getEncoder().encodeToString(bytes.content()));
            }
            case Response.Body.None none -> out.put("kind", "none");
            case Response.Body.Negotiated negotiated -> {
                out.put("kind", "negotiated");
                out.put("value", negotiated.value());
                out.put("formats", negotiated.formats().stream().map(Enum::name).toList());
                out.put("csvHeaders", negotiated.csvHeaders());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Response.Body decodeBody(Map<String, Object> body) {
        String kind = String.valueOf(body.get("kind"));
        return switch (kind) {
            case "text" -> new Response.Body.Text(String.valueOf(body.get("content")));
            case "json" -> new Response.Body.Json(body.get("value"));
            case "view" -> new Response.Body.ViewRef(String.valueOf(body.get("name")),
                    (Map<String, Object>) body.getOrDefault("data", Map.of()));
            case "bytes" -> new Response.Body.Bytes(
                    Base64.getDecoder().decode(String.valueOf(body.get("content"))));
            case "negotiated" -> {
                var formats = ((List<?>) body.getOrDefault("formats", List.of())).stream()
                        .map(name -> Response.Format.valueOf(String.valueOf(name)))
                        .collect(java.util.stream.Collectors.toSet());
                yield new Response.Body.Negotiated(
                        body.get("value"), formats,
                        (List<String>) body.getOrDefault("csvHeaders", List.of()));
            }
            default -> new Response.Body.None();
        };
    }

    // ================= 工具 =================

    private Response inFlight() {
        return Response.json(Map.of("message",
                "同一个 Idempotency-Key 的请求正在处理中,请稍后重试"))
                .status(409)
                .header("Retry-After", "1");
    }

    /** 非共享缓存 → 多实例下同一 key 会各执行一遍。只在第一次命中时警告 */
    private void warnIfNotShared() {
        if (warned) return;
        warned = true;
        if (cache.store().isShared()) return;

        System.err.printf("""
                [jaravel] 警告:idempotent 用的缓存驱动 "%s" 不跨实例共享。
                          多副本部署时,同一个 Idempotency-Key 会在每个实例各执行一遍。
                          生产请把 cache.default 配成 redis 或 database。%n""",
                cache.store().name());
    }

    private static String cacheKey(String method, String path, String key) {
        return "idem:" + sha256(method + "\n" + path + "\n" + key);
    }

    /**
     * 请求指纹:方法 + 路径 + 原始 body。
     *
     * <p>同一个 key 配不同 body 是调用方的 bug(或 key 复用了)——
     * 返回第一次的响应会让第二笔请求看起来成功,而它没有被执行。
     */
    private static String fingerprint(Request request) {
        String body = request.rawBody() == null ? "" : request.rawBody();
        return sha256(request.method().toUpperCase(Locale.ROOT) + "\n"
                + request.path() + "\n" + body);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) return true;
        }
        return false;
    }

    private static long parse(String raw, String what) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "idempotent 的" + what + "必须是正整数秒,当前是 \"" + raw + "\"");
        }
    }
}
