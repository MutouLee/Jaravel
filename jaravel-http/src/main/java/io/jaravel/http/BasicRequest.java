package io.jaravel.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.jaravel.support.Cast;

/**
 * {@link Request} 的可变实现。
 *
 * <p>由 starter(从 Servlet 请求)或测试(直接构造)填充。
 * 路由参数在匹配后由路由层写入,因此必须可变。
 */
public class BasicRequest implements Request {

    private String method = "GET";
    private String path = "/";
    private String scheme = "http";
    private String host = "localhost";
    private int port = 80;
    private String ip = "127.0.0.1";
    private String rawBody = "";

    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private final Map<String, Object> bodyParams = new LinkedHashMap<>();
    /** 头名大小写不敏感 */
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, String> routeParams = new LinkedHashMap<>();
    private final Map<String, List<UploadedFile>> files = new LinkedHashMap<>();

    private String routeName;
    private Object raw;

    /**
     * 原始查询串 —— 签名校验要它,见 {@link Request#queryString()}。
     *
     * <p>不从 {@code queryParams} 重建:重建会丢掉原始的
     * 百分号编码与参数顺序,而且重建出来的东西
     * 看起来像原文 —— 那比没有更危险。
     */
    private String rawQueryString = "";

    // ---------- 构造 ----------

    public static BasicRequest of(String method, String path) {
        var r = new BasicRequest();
        r.method = method.toUpperCase();
        int q = path.indexOf('?');
        if (q >= 0) {
            r.path = path.substring(0, q);
            r.parseQueryString(path.substring(q + 1));
        } else {
            r.path = path;
        }
        return r;
    }

    public BasicRequest method(String m)        { this.method = m.toUpperCase(); return this; }
    public BasicRequest path(String p)          { this.path = p; return this; }
    public BasicRequest scheme(String s)        { this.scheme = s; return this; }
    public BasicRequest host(String h)          { this.host = h; return this; }
    public BasicRequest port(int p)             { this.port = p; return this; }
    public BasicRequest ip(String i)            { this.ip = i; return this; }
    public BasicRequest rawBody(String b)       { this.rawBody = b == null ? "" : b; return this; }
    public BasicRequest raw(Object o)           { this.raw = o; return this; }
    public BasicRequest routeName(String n)     { this.routeName = n; return this; }

    public BasicRequest header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public BasicRequest cookie(String name, String value) {
        cookies.put(name, value);
        return this;
    }

    public BasicRequest queryParam(String name, String value) {
        queryParams.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public BasicRequest bodyParam(String name, Object value) {
        bodyParams.put(name, value);
        return this;
    }

    public BasicRequest bodyParams(Map<String, ?> params) {
        if (params != null) bodyParams.putAll(params);
        return this;
    }

    /**
     * 加一个上传的文件 —— 由 starter 的 multipart 解析填入,
     * 测试里也可以直接造。
     *
     * <pre>{@code
     * var request = BasicRequest.of("POST", "/avatar")
     *         .file(new UploadedFile("avatar", "me.png", "image/png", png));
     * }</pre>
     */
    public BasicRequest file(UploadedFile uploaded) {
        files.computeIfAbsent(uploaded.fieldName(), k -> new ArrayList<>())
                .add(uploaded);
        return this;
    }

    /** 同上,直接给字节 */
    public BasicRequest file(String field, String originalName, byte[] content) {
        return file(new UploadedFile(field, originalName, null, content));
    }

    /** 由路由匹配阶段调用 */
    public BasicRequest routeParam(String name, String value) {
        routeParams.put(name, value);
        return this;
    }

    public void parseQueryString(String qs) {
        if (qs == null || qs.isBlank()) return;
        this.rawQueryString = qs;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            queryParams.computeIfAbsent(urlDecode(k), x -> new ArrayList<>()).add(urlDecode(v));
        }
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- Request 实现 ----------

    @Override public String method() { return method; }
    @Override public String path()   { return path; }
    @Override public String ip()     { return ip; }
    @Override public String rawBody(){ return rawBody; }

    @Override public String url() {
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    }

    @Override public String fullUrl() {
        if (queryParams.isEmpty()) return url();
        var sb = new StringBuilder(url()).append('?');
        boolean first = true;
        for (var e : queryParams.entrySet()) {
            for (String v : e.getValue()) {
                if (!first) sb.append('&');
                sb.append(e.getKey()).append('=').append(v);
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * 查询串 + 请求体 + **上传的文件**。
     *
     * <p>顺序:查询串在前,请求体覆盖同名,文件最后 —— 与 Laravel 一致。
     *
     * <h2>★ 为什么文件也在 {@code all()} 里</h2>
     * <p>因为校验的输入就是 {@code all()}
     * ({@code FormRequest.bind} / {@code Validator.make(request.all(), ...)})。
     * 文件不在里面的话 {@code "avatar", "required|image|max:2048"}
     * **永远失败** —— 规则拿到的是 null。
     *
     * <p>这是 Laravel 的行为,但更重要的是它是唯一能让文件校验
     * 工作的办法:另一条路是给 Validator 加一个「文件」的第二份输入,
     * 而那会让每一条跨字段规则({@code required_without:avatar})
     * 都要查两个地方。
     *
     * <h2>⚠ 两个后果</h2>
     * <ul>
     *   <li>{@code input("avatar")} 会返回 {@code UploadedFile.toString()}
     *       的结果 —— 一段形如 {@code UploadedFile[avatar=me.png, ...]}
     *       的描述。取文件要用 {@link #file(String)};</li>
     *   <li>{@code validated()} 里会出现 {@code UploadedFile} 对象。
     *       直接把它喂给模型的批量赋值会试图把一个对象写进一列 ——
     *       正确写法是 {@code Storage.putFile(...)} 拿到路径再赋值。</li>
     * </ul>
     */
    @Override
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        queryParams.forEach((k, v) -> out.put(k, v.size() == 1 ? v.get(0) : v));
        out.putAll(bodyParams);

        // 单个文件放对象本身,多个放列表 —— 与同名表单字段的处理一致。
        //
        // 不统一成列表:那会让 "avatar", "image" 这条最常见的规则
        // 拿到一个 List 而失败,于是每条文件规则都要先解包
        files.forEach((k, v) -> out.put(k, v.size() == 1 ? v.get(0) : List.copyOf(v)));
        return out;
    }

    @Override
    public Optional<String> input(String key) {
        Object v = all().get(key);
        if (v == null) return Optional.empty();
        if (v instanceof List<?> l) return l.isEmpty() ? Optional.empty() : Optional.of(String.valueOf(l.get(0)));
        return Optional.of(String.valueOf(v));
    }

    @Override
    public <T> Optional<T> input(String key, Class<T> type) {
        Object v = all().get(key);
        if (v == null) return Optional.empty();
        return Optional.ofNullable(Cast.to(v, type));
    }

    @Override
    public String queryString() {
        return rawQueryString;
    }

    @Override
    public Optional<String> query(String key) {
        List<String> v = queryParams.get(key);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
    }

    @Override
    public List<String> inputs(String key) {
        List<String> q = queryParams.get(key);
        if (q != null) return List.copyOf(q);
        Object b = bodyParams.get(key);
        if (b instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        if (b != null) return List.of(String.valueOf(b));
        return List.of();
    }

    @Override
    public Optional<String> routeParam(String name) {
        return Optional.ofNullable(routeParams.get(name));
    }

    @Override
    public <T> Optional<T> routeParam(String name, Class<T> type) {
        String v = routeParams.get(name);
        return v == null ? Optional.empty() : Optional.ofNullable(Cast.to(v, type));
    }

    @Override
    public Map<String, List<UploadedFile>> allFiles() {
        var out = new LinkedHashMap<String, List<UploadedFile>>();
        files.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Map.copyOf(out);
    }

    @Override public Map<String, String> routeParams() { return Map.copyOf(routeParams); }
    @Override public Optional<String> routeName()       { return Optional.ofNullable(routeName); }

    @Override
    public Optional<String> header(String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
    }

    @Override public Map<String, List<String>> headers() { return Map.copyOf(headers); }

    @Override public Optional<String> cookie(String name) { return Optional.ofNullable(cookies.get(name)); }

    @Override public Optional<Object> raw() { return Optional.ofNullable(raw); }

    @Override public String toString() { return describe(); }
}
