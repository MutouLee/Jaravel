package io.jaravel.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 响应。**不含任何 Servlet 类型** —— starter 负责写出到 {@code HttpServletResponse}。
 *
 * <p>是个可变的值对象:控制器构造它,中间件的回程可以继续加头/Cookie。
 */
public class Response {

    /** 响应体的三种形态 */
    public sealed interface Body {
        /** 已就绪的字符串(JSON / HTML / 文本) */
        record Text(String content) implements Body {}
        /** 待渲染的视图,由视图层处理 */
        record ViewRef(String name, Map<String, Object> data) implements Body {}
        /** 待序列化的对象,由 starter 的 JSON 层处理 */
        record Json(Object value) implements Body {}
        /**
         * 已就绪的字节 —— 下载、图片、PDF。
         *
         * <p>它与 {@link Text} 分开而不是「把字节当 UTF-8 字符串装进 Text」:
         * 后者会让任何不是合法 UTF-8 的字节(也就是所有二进制文件)
         * 在往返中被替换成 U+FFFD,于是下载下来的 PNG 坏掉。
         */
        record Bytes(byte[] content) implements Body {}
        /** 空 */
        record None() implements Body {}
    }

    private int status = 200;
    private String contentType;
    private Body body = new Body.None();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();

    protected Response() {}

    // ---------- 工厂 ----------

    public static Response json(Object value) {
        var r = new Response();
        r.body = new Body.Json(value);
        r.contentType = "application/json;charset=UTF-8";
        return r;
    }

    public static Response text(String content) {
        var r = new Response();
        r.body = new Body.Text(content == null ? "" : content);
        r.contentType = "text/plain;charset=UTF-8";
        return r;
    }

    public static Response html(String content) {
        var r = new Response();
        r.body = new Body.Text(content == null ? "" : content);
        r.contentType = "text/html;charset=UTF-8";
        return r;
    }

    public static Response noContent() {
        var r = new Response();
        r.status = 204;
        return r;
    }

    // ---------- 二进制 / 下载 ----------

    /** 裸字节,调用方自己设 Content-Type */
    public static Response bytes(byte[] content) {
        var r = new Response();
        r.body = new Body.Bytes(content == null ? new byte[0] : content);
        r.contentType = "application/octet-stream";
        return r;
    }

    /**
     * 作为**下载**返回(docs/03 的 {@code Response.download})。
     *
     * <pre>{@code
     * byte[] pdf = Storage.get("invoices/1.pdf").orElseThrow();
     * return Response.download(pdf, "发票.pdf");
     * }</pre>
     *
     * <p>⚠ 为什么不是 {@code Storage.download("invoices/1.pdf", "发票.pdf")}
     * (docs/11 §3.1 里写的那个):那需要存储模块依赖 HTTP 模块,
     * 而一个只往 S3 写文件的后台任务不该为此拉进 HTTP。
     * 与 {@code UploadedFile} 没有 {@code store(...)} 是同一条约束的两面。
     */
    public static Response download(byte[] content, String fileName) {
        return download(content, fileName, "application/octet-stream");
    }

    public static Response download(byte[] content, String fileName,
                                    String contentType) {
        return bytes(content)
                .contentType(contentType)
                .header("Content-Disposition", disposition("attachment", fileName));
    }

    /**
     * 作为**内联内容**返回 —— 浏览器直接显示而不是弹下载框。
     *
     * <p>典型用途:在页面里显示一张私有图片。
     */
    public static Response inline(byte[] content, String fileName,
                                  String contentType) {
        return bytes(content)
                .contentType(contentType)
                .header("Content-Disposition", disposition("inline", fileName));
    }

    /**
     * 构造 {@code Content-Disposition} 头。
     *
     * <p>★ **这是一道安全边界**,做的事按顺序是:
     *
     * <ol>
     *   <li>只取路径的最后一段 —— 一个 {@code ../../etc/passwd} 的文件名
     *       本身不会读到别的文件(内容已经在手上了),但它会出现在
     *       用户的下载目录里;</li>
     *   <li>去掉 CR / LF / NUL —— 文件名常常来自用户(上传时的原始名、
     *       或一个带用户名的导出文件)。一个带 {@code \r\n} 的名字
     *       能**注入任意响应头**,包括 {@code Set-Cookie};</li>
     *   <li>去掉引号与反斜杠 —— 它们能提前关闭 {@code filename="..."}
     *       从而注入 disposition 的其它参数;</li>
     *   <li>ASCII 的那份做 {@code filename=},再附一份
     *       RFC 5987 的 {@code filename*=UTF-8''...}。</li>
     * </ol>
     *
     * <p>为什么要两份:{@code filename=} 只能可靠地带 ASCII,
     * 而 {@code filename*=} 老浏览器不认。只给前者的话中文文件名
     * 会变成乱码或被截断;只给后者的话老客户端拿到的是
     * 一个没有名字的下载。
     */
    private static String disposition(String type, String fileName) {
        String safe = sanitizeFileName(fileName);

        // 非 ASCII 字符换成 _ 作为兜底的那一份。
        //
        // 不是删掉:一个全中文的文件名会变成空串,而
        // filename="" 在部分客户端上会让下载失败
        var ascii = new StringBuilder();
        for (char ch : safe.toCharArray()) {
            ascii.append(ch < 0x80 ? ch : '_');
        }

        String encoded = java.net.URLEncoder.encode(safe,
                        java.nio.charset.StandardCharsets.UTF_8)
                // URLEncoder 是给表单用的:空格编成 +,而 RFC 5987
                // 要求 %20。不换的话一个带空格的文件名会出现一堆加号
                .replace("+", "%20");

        return "%s; filename=\"%s\"; filename*=UTF-8''%s"
                .formatted(type, ascii, encoded);
    }

    /**
     * 文件名消毒 —— 见 {@link #disposition} 的说明。
     *
     * <p>与 {@code PathGuard.sanitizeFileName} 不同:那一个是为**存储路径**
     * 准备的(还要管 Windows 保留名、255 字节截断),这一个只管
     * 「放进一个响应头里安全吗」。两处的威胁模型不一样,
     * 合成一个会让其中一方带上不需要的限制 —— 而且
     * {@code jaravel-http} 不依赖存储模块。
     */
    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "download";

        // 只取最后一段:/ 与 \ 都切
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);

        var out = new StringBuilder();
        for (char ch : base.toCharArray()) {
            // 控制字符(含 CR/LF/NUL)、引号、反斜杠一律丢弃
            if (ch < 0x20 || ch == 0x7F || ch == '"' || ch == '\\') continue;
            out.append(ch);
        }

        String cleaned = out.toString().trim();
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    public static Response of(Body body, int status) {
        var r = new Response();
        r.body = body;
        r.status = status;
        return r;
    }

    /** 立即中止请求并返回指定状态码。抛异常,由 ExceptionHandler 渲染。 */
    public static void abort(int status) {
        throw new HttpException(status);
    }

    public static void abort(int status, String message) {
        throw new HttpException(status, message);
    }

    public static void abortIf(boolean condition, int status, String message) {
        if (condition) throw new HttpException(status, message);
    }

    public static void abortUnless(boolean condition, int status, String message) {
        if (!condition) throw new HttpException(status, message);
    }

    // ---------- 链式配置 ----------

    public Response status(int s) {
        this.status = s;
        return this;
    }

    public Response contentType(String ct) {
        this.contentType = ct;
        return this;
    }

    public Response header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public Response headers(Map<String, String> h) {
        h.forEach(this::header);
        return this;
    }

    public Response cookie(Cookie c) {
        cookies.add(c);
        return this;
    }

    public Response cookie(String name, String value) {
        return cookie(Cookie.make(name, value));
    }

    // ---------- 读取 ----------

    public int status()                        { return status; }
    public String contentType()                { return contentType; }
    public Body body()                         { return body; }
    public Map<String, List<String>> headers()  { return Map.copyOf(headers); }
    public List<Cookie> cookies()               { return List.copyOf(cookies); }

    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** 已就绪的文本内容。视图与 JSON 需先经渲染/序列化,此处返回空。 */
    public String textContent() {
        return body instanceof Body.Text t ? t.content() : "";
    }

    /** 替换响应体。视图渲染与 JSON 序列化后由 starter 调用。 */
    public Response withRenderedBody(String content) {
        this.body = new Body.Text(content);
        return this;
    }

    @Override public String toString() {
        return "Response(" + status + ", " + body.getClass().getSimpleName() + ")";
    }
}
