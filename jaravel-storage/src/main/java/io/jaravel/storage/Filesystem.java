package io.jaravel.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 一个「磁盘」(docs/11 §3.1)。
 *
 * <p>驱动实现它:{@code local} / {@code memory},
 * 以及(后续){@code s3} / {@code sftp}。
 *
 * <h2>读的方法返回 Optional,写的方法抛异常</h2>
 * <p>这个不对称是刻意的。
 *
 * <p>「文件不存在」对**读**是一个正常的、可预期的结果 ——
 * 一个用户没上传头像时读他的头像就该是空。
 * 为此抛异常会让每个读取点都要包一层 try/catch。
 *
 * <p>而**写**失败不是正常结果:磁盘满了、权限不对、路径非法 ——
 * 这些都需要被知道。返回一个 boolean 的话调用方会忽略它
 * (Java 不强制检查返回值),于是「文件没存上」变成一个
 * 几天后才发现的问题。
 *
 * <h2>path 一律是「存储路径」而不是文件系统路径</h2>
 * <p>{@code "avatars/1.png"},不是 {@code "/var/app/storage/avatars/1.png"}。
 * 分隔符一律 {@code /},**即便在 Windows 上** ——
 * 存储路径会进数据库、进 URL,而一个在 Windows 上存成
 * {@code avatars\1.png} 的路径在 Linux 的生产环境里读不出来。
 */
public interface Filesystem {

    /** 驱动名,用于日志与错误消息 */
    String name();

    // ================= 读 =================

    /** 文件内容。不存在时 empty */
    Optional<byte[]> get(String path);

    /** 文件内容(UTF-8)。不存在时 empty */
    default Optional<String> getString(String path) {
        return get(path).map(bytes ->
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 流式读。
     *
     * <p>**调用方负责关闭它。** 不用 try-with-resources 的后果是
     * 文件句柄泄漏,而那个症状(「Too many open files」)
     * 出现在几小时后、而且指向一个完全无关的地方。
     *
     * <p>不存在时 empty —— 而不是返回一个会在第一次读时抛的流。
     */
    Optional<InputStream> readStream(String path);

    boolean exists(String path);

    default boolean missing(String path) {
        return !exists(path);
    }

    /** 字节数。不存在时抛 —— 对一个不存在的文件问大小是调用方的 bug */
    long size(String path);

    Instant lastModified(String path);

    /**
     * MIME 类型。
     *
     * <p>⚠ **按扩展名猜**,不嗅探内容。
     *
     * <p>docs/11 §3.3 要求上传时的扩展名由**内容嗅探**得出
     * (那是一条安全约束),而那发生在**上传**那一层 ——
     * 一旦文件已经以某个名字存下来了,它的扩展名就是可信的
     * (是框架自己定的)。
     *
     * <p>这个区别要说清:{@code Storage.mimeType(...)} 不是一道
     * 安全检查,它只是「这个扩展名通常是什么类型」。
     */
    String mimeType(String path);

    // ================= 写 =================

    void put(String path, byte[] contents);

    default void putString(String path, String contents) {
        put(path, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 流式写。
     *
     * <p>**不关闭传入的流** —— 它是调用方打开的,所以由调用方关。
     * 在这里关掉会让「写两个文件复用同一个流」这种(合理的)
     * 用法悄悄失败。
     */
    void writeStream(String path, InputStream contents);

    /** 追加。文件不存在时等同于 put */
    void append(String path, String contents);

    /**
     * 前置。
     *
     * <p>⚠ **它要把整个文件读进内存**。文件系统不支持「在开头插入」,
     * 所以实现是「读出来 + 拼接 + 写回去」。
     *
     * <p>对一个 1 GB 的日志文件调它会吃掉 2 GB 内存。
     * 那是这个 API 的固有性质(Laravel 也一样),
     * 但它值得写在这里 —— 「prepend 到一个日志文件」听起来很无害。
     */
    void prepend(String path, String contents);

    // ================= 删 / 移动 / 复制 =================

    /** 删除。**不存在时不报错** —— 删除是幂等的 */
    void delete(String path);

    default void delete(List<String> paths) {
        paths.forEach(this::delete);
    }

    void copy(String from, String to);

    void move(String from, String to);

    // ================= 目录 =================

    /** 直接子文件(不递归) */
    List<String> files(String directory);

    /** 所有子文件(递归) */
    List<String> allFiles(String directory);

    /** 直接子目录(不递归) */
    List<String> directories(String directory);

    void makeDirectory(String directory);

    /** 递归删除一个目录 */
    void deleteDirectory(String directory);

    // ================= URL =================

    /**
     * 公开 URL。
     *
     * <p>**只有配了 {@code url} 的磁盘才有**。没配时抛 ——
     * 返回一个拼出来的假 URL 会让「图片显示不出来」
     * 变成一个前端去查的问题。
     */
    String url(String path);

    /**
     * 临时签名 URL(docs/11 §3.1)。
     *
     * <p>用途:私有文件的限时访问 —— 一份对账单给用户 15 分钟去下载,
     * 而不必把那个文件变成公开的。
     */
    String temporaryUrl(String path, Duration ttl);

    /**
     * 临时上传 URL —— 让客户端**直接上传到存储**,不经过应用。
     *
     * <p>价值:一个 500 MB 的视频不必流过应用的内存与带宽。
     *
     * <p>⚠ 只有对象存储(S3 这类)才真正支持它。{@code local}
     * 驱动需要应用自己提供一个接收端点 —— 见各驱动的实现。
     */
    String temporaryUploadUrl(String path, Duration ttl);

    // ================= 可见性 =================

    /** {@code "public"} 或 {@code "private"} */
    String getVisibility(String path);

    void setVisibility(String path, String visibility);

    /** 操作失败 */
    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 文件不存在,而这个操作要求它存在 */
    class FileNotFoundException extends StorageException {
        public FileNotFoundException(String message) {
            super(message, null);
        }
    }
}
