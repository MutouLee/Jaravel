package io.jaravel.storage.disk;

import java.util.Map;

import io.jaravel.storage.PathGuard;

/**
 * 扩展名 → MIME 类型。
 *
 * <h2>这**不是**一道安全检查</h2>
 * <p>它按扩展名猜,不看内容。
 *
 * <p>docs/11 §3.3 要求上传时的扩展名由**内容嗅探**得出 ——
 * 那是一条安全约束,而它发生在**上传**那一层(见
 * {@link ContentSniffer})。一旦文件已经以某个名字存下来了,
 * 它的扩展名就是可信的 —— 是框架自己定的。
 *
 * <p>这个区别要说清,否则有人会拿 {@code Storage.mimeType(...)}
 * 去做「这个上传是不是图片」的判断,而那是能被绕过的。
 *
 * <h2>为什么不用 {@code Files.probeContentType}</h2>
 * <p>JDK 自带那个方法,但它**依赖平台**:Linux 上读
 * {@code /etc/mime.types}、Windows 上读注册表、容器里那两个
 * 常常都不存在 —— 于是它返回 null。
 *
 * <p>一个在开发机上返回 {@code image/png}、在容器里返回 null 的
 * 函数是最糟的那种:它的 bug 只在生产出现。
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            // 图片
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("ico", "image/x-icon"),
            // svg 是 XML,而且能带脚本 —— 见 PathGuard 里关于 public 磁盘的说明
            Map.entry("svg", "image/svg+xml"),

            // 文档
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument"
                    + ".presentationml.presentation"),

            // 压缩
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("rar", "application/vnd.rar"),

            // 音视频
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),

            // 字体
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"));

    private MimeTypes() {
    }

    /**
     * 按扩展名猜。
     *
     * <p>猜不出时返回 {@code application/octet-stream} ——
     * 它的含义是「不知道是什么,当二进制下载」。
     *
     * <p>⚠ **不要**在猜不出时返回 {@code text/plain}:
     * 浏览器对 {@code text/*} 会尝试嗅探并可能当成 HTML 渲染,
     * 于是一个伪装成 .dat 的 HTML 文件变成了存储型 XSS。
     */
    public static String forPath(String path) {
        String ext = PathGuard.extensionOf(path);
        return BY_EXTENSION.getOrDefault(ext, "application/octet-stream");
    }

    /** 这个 MIME 是图片吗 —— 上传校验用 */
    public static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }
}
