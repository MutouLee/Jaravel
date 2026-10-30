package io.jaravel.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 一个附件(docs/11 §1.1)。
 *
 * <pre>{@code
 * Attachment.fromPath("/var/invoices/42.pdf").as("发票.pdf")
 * Attachment.fromData(() -> qrBytes, "qr.png").withMime("image/png")
 * }</pre>
 *
 * <h2>两种形态,以及它们在入队时的区别</h2>
 * <p>这是整个邮件模块里最需要想清的一处。
 *
 * <p>{@code Mail.to(...).queue(mailable)} 会**在投递时渲染**
 * (见 {@link RenderedMessage} 的类注释)。那时附件怎么处理?
 *
 * <ul>
 *   <li>{@link #fromPath} —— 只把**路径**放进 payload,文件由 worker 读。
 *       payload 很小,但要求那个文件在 worker 跑的时候还在
 *       (同一台机器或共享存储);</li>
 *   <li>{@link #fromData} —— 字节在投递时就**materialize** 进 payload。
 *       自包含、不依赖文件系统,但一个 5 MB 的附件就是 5 MB 的
 *       队列记录(base64 之后约 6.7 MB)。</li>
 * </ul>
 *
 * <p>两者都合理,取舍不同,所以都保留并在这里说清。
 * 一个 5 MB 的 PDF 用 {@code fromData} 入队会让 jobs 表迅速膨胀,
 * 而那个症状(「数据库变慢了」)离原因很远 —— 所以
 * {@link #materialize()} 在超过 1 MB 时会警告一次。
 *
 * <h2>fromStorage 还没有</h2>
 * <p>docs/11 §1.1 里的 {@code Attachment.fromStorage(...)} 需要
 * 存储模块(§3),而那还没实现。调它会报错说明这一点,
 * 而不是静默当成本地路径 —— 后者会在「配了 S3 但附件读的是本地盘」
 * 时产生一个非常困惑的 FileNotFoundException。
 */
public final class Attachment {

    /** 超过这个大小的内联附件会警告一次 */
    private static final long WARN_BYTES = 1024L * 1024L;

    private final Path path;
    private final Supplier<byte[]> data;
    private String name;
    private String mime;

    private Attachment(Path path, Supplier<byte[]> data, String name) {
        this.path = path;
        this.data = data;
        this.name = name;
    }

    public static Attachment fromPath(String location) {
        Path p = Path.of(location);
        return new Attachment(p, null, p.getFileName().toString());
    }

    public static Attachment fromPath(Path location) {
        return new Attachment(location, null, location.getFileName().toString());
    }

    /**
     * 从内存里的字节。
     *
     * @param bytes 用 {@code Supplier} 而不是 {@code byte[]} ——
     *              附件常常是现算的(二维码、PDF),而一个
     *              「只是声明了一封邮件但还没发」的 Mailable
     *              不该先把它算出来。预览一封邮件时尤其明显:
     *              预览只要 HTML,不该顺带生成一遍 PDF
     */
    public static Attachment fromData(Supplier<byte[]> bytes, String name) {
        return new Attachment(null, bytes, name);
    }

    /** docs/11 §1.1 的形态 —— 需要存储模块,尚未实现 */
    public static Attachment fromStorage(String location) {
        throw new UnsupportedOperationException("""
                Attachment.fromStorage("%s") 需要存储模块(docs/11 §3),
                而它还没实现。
                  现在可以用 Attachment.fromPath(...) 读本地文件。
                  这里报错而不是当成本地路径 —— 后者会在「配了 S3
                  但附件读的是本地盘」时产生一个非常困惑的
                  FileNotFoundException。"""
                .formatted(location));
    }

    /** 改显示名 */
    public Attachment as(String displayName) {
        this.name = displayName;
        return this;
    }

    public Attachment withMime(String mimeType) {
        this.mime = mimeType;
        return this;
    }

    public String name() {
        return name;
    }

    /**
     * MIME 类型。
     *
     * <p>没显式给时按扩展名猜,猜不出用
     * {@code application/octet-stream} —— 那个值的含义是
     * 「不知道是什么,当二进制下载」,对一个未知类型的附件是对的。
     */
    public String mimeType() {
        if (mime != null) return mime;
        return guessMime(name);
    }

    public boolean isPathBased() {
        return path != null;
    }

    public Path path() {
        return path;
    }

    /**
     * 读出字节。
     *
     * <p>{@code fromPath} 的会在这里读磁盘;{@code fromData} 的
     * 调那个 supplier。
     */
    public byte[] materialize() {
        byte[] bytes;
        if (path != null) {
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new AttachmentException("""
                        读不到附件 %s:%s
                          路径形态的附件在**发送时**才读盘 —— 入队发送的话
                          那是在 worker 里,文件必须在那时还在
                          (同一台机器或共享存储)。
                          需要自包含的话用 Attachment.fromData(...)。"""
                        .formatted(path, e.getMessage()), e);
            }
        } else {
            bytes = data.get();
            if (bytes == null) {
                throw new AttachmentException(
                        "附件 %s 的数据 supplier 返回了 null。".formatted(name), null);
            }
        }

        if (bytes.length > WARN_BYTES) {
            System.err.printf("""
                    [jaravel] 警告:附件 %s 有 %.1f MB。
                              入队发送时它会被编码进队列记录(base64 后约 %.1f MB),
                              jobs 表会迅速膨胀 —— 而「数据库变慢了」离这个原因很远。
                              大附件建议用 Attachment.fromPath(...) 只传路径,
                              或者在邮件里放一个下载链接。
                    %n""", name, bytes.length / 1048576.0, bytes.length * 1.34 / 1048576.0);
        }
        return bytes;
    }

    private static String guessMime(String fileName) {
        if (fileName == null) return "application/octet-stream";

        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();

        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "csv" -> "text/csv";
            case "txt" -> "text/plain";
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "zip" -> "application/zip";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document";
            default -> "application/octet-stream";
        };
    }

    public static class AttachmentException extends RuntimeException {
        public AttachmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
