package io.jaravel.storage.disk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.jaravel.storage.Filesystem;
import io.jaravel.storage.PathGuard;

/**
 * 本地文件系统驱动(docs/11 §3.2)。
 *
 * <pre>{@code
 * filesystems:
 *   disks:
 *     local:
 *       driver: local
 *       root: storage/app
 *       visibility: private
 *     public:
 *       driver: local
 *       root: storage/app/public
 *       url: ${APP_URL}/storage
 *       visibility: public
 * }</pre>
 *
 * <h2>{@code public} 不是一个独立的驱动</h2>
 * <p>docs/11 §3.2 的表里把 {@code public} 列成一个驱动。
 * 实现上它就是 {@code local} + 一个 {@code url} + 一个
 * 「对外可访问」的标记 —— 做成两个类会让它们的 99% 代码重复。
 *
 * <p>那个标记有一个真实的后果:它打开
 * {@link PathGuard#requireSafeForPublic} 的检查。
 *
 * <h2>每个写操作都过两道路径检查</h2>
 * <ol>
 *   <li>{@link PathGuard#normalize} —— 拒绝 {@code ..}、空字节、控制字符;</li>
 *   <li>{@link PathGuard#requireWithin} —— 解析后的真实路径必须在 root 内。</li>
 * </ol>
 *
 * <p>第二道不是多余的:第一道是字符串层面的,而**符号链接**能绕过它 ——
 * {@code storage/app/link} 指向 {@code /etc} 的话,
 * {@code link/passwd} 在字符串上完全合法。
 */
public class LocalFilesystem implements Filesystem {

    private final Path root;
    private final String urlPrefix;
    private final boolean publiclyServed;
    private final Set<String> allowedExtras;
    private final String defaultVisibility;

    /** 临时 URL 用的签名器。null = 不支持临时 URL */
    private final io.jaravel.core.crypt.UrlSigner signer;
    private final String temporaryUrlPrefix;

    public LocalFilesystem(Path root) {
        this(root, null, false, Set.of(), "private", null, null);
    }

    public LocalFilesystem(Path root, String urlPrefix, boolean publiclyServed,
                           Set<String> allowedExtras, String defaultVisibility,
                           io.jaravel.core.crypt.UrlSigner signer,
                           String temporaryUrlPrefix) {
        this.root = root.toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix;
        this.publiclyServed = publiclyServed;
        this.allowedExtras = allowedExtras == null ? Set.of() : Set.copyOf(allowedExtras);
        this.defaultVisibility = defaultVisibility;
        this.signer = signer;
        this.temporaryUrlPrefix = temporaryUrlPrefix;

        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("""
                    建不了磁盘的 root 目录 %s:%s
                      检查父目录的权限。"""
                    .formatted(this.root, e.getMessage()), e);
        }
    }

    @Override
    public String name() {
        return publiclyServed ? "local(public)" : "local";
    }

    /**
     * 存储路径 → 文件系统路径,带全部安全检查。
     *
     * @param forWrite 写操作额外过 public 磁盘的扩展名检查
     */
    private Path resolve(String path, boolean forWrite) {
        String normalized = PathGuard.normalize(path);

        if (forWrite && publiclyServed) {
            PathGuard.requireSafeForPublic(normalized, allowedExtras);
        }

        Path target = root.resolve(normalized).normalize();
        PathGuard.requireWithin(root, target);
        return target;
    }

    // ================= 读 =================

    @Override
    public Optional<byte[]> get(String path) {
        Path target = resolve(path, false);
        if (!Files.isRegularFile(target)) return Optional.empty();

        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new StorageException("读不了 " + path + ":" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<InputStream> readStream(String path) {
        Path target = resolve(path, false);
        if (!Files.isRegularFile(target)) return Optional.empty();

        try {
            return Optional.of(Files.newInputStream(target));
        } catch (IOException e) {
            throw new StorageException("打不开 " + path + ":" + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(resolve(path, false));
    }

    @Override
    public long size(String path) {
        Path target = resolve(path, false);
        requireExists(target, path);

        try {
            return Files.size(target);
        } catch (IOException e) {
            throw new StorageException("取不到大小 " + path, e);
        }
    }

    @Override
    public Instant lastModified(String path) {
        Path target = resolve(path, false);
        requireExists(target, path);

        try {
            return Files.getLastModifiedTime(target).toInstant();
        } catch (IOException e) {
            throw new StorageException("取不到修改时间 " + path, e);
        }
    }

    @Override
    public String mimeType(String path) {
        return MimeTypes.forPath(path);
    }

    // ================= 写 =================

    @Override
    public void put(String path, byte[] contents) {
        Path target = resolve(path, true);
        createParent(target);

        try {
            // CREATE + TRUNCATE_EXISTING:覆盖语义。
            //
            // **不用临时文件 + ATOMIC_MOVE**,与会话存储那里不同:
            // 会话文件会被并发读写(一个请求写、另一个读),
            // 而上传的文件通常是「写一次,之后只读」。
            // 原子写要一次额外的拷贝,对一个 500 MB 的视频是实打实的开销。
            //
            // 需要原子性的场景(覆盖一个正在被读的文件)应当换一个
            // 新路径然后更新引用 —— 那也是对象存储上唯一可行的做法。
            Files.write(target, contents, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(describeWriteFailure(path, e), e);
        }
    }

    @Override
    public void writeStream(String path, InputStream contents) {
        Path target = resolve(path, true);
        createParent(target);

        try {
            // 不关闭传入的流 —— 见 Filesystem.writeStream 的注释
            Files.copy(contents, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(describeWriteFailure(path, e), e);
        }
    }

    @Override
    public void append(String path, String contents) {
        Path target = resolve(path, true);
        createParent(target);

        try {
            Files.writeString(target, contents, java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException(describeWriteFailure(path, e), e);
        }
    }

    @Override
    public void prepend(String path, String contents) {
        // 要把整个文件读进内存 —— 见 Filesystem.prepend 的注释
        String existing = getString(path).orElse("");
        putString(path, contents + existing);
    }

    /**
     * 写失败的诊断。
     *
     * <p>{@code AccessDeniedException} 与「磁盘满」是最常见的两种,
     * 而它们的处理方式完全不同 —— 所以分开说。
     */
    private String describeWriteFailure(String path, IOException e) {
        if (e instanceof java.nio.file.AccessDeniedException) {
            return """
                    写不了 %s:权限不足。
                      检查 %s 的属主与权限 —— 容器里跑的进程 uid
                      常常与宿主机上建目录的那个不同。"""
                    .formatted(path, root);
        }
        if (e.getMessage() != null && e.getMessage().contains("No space")) {
            return "写不了 %s:磁盘满了(%s)。".formatted(path, root);
        }
        return "写不了 %s:%s".formatted(path, e.getMessage());
    }

    // ================= 删 / 移动 / 复制 =================

    @Override
    public void delete(String path) {
        try {
            // deleteIfExists —— 删除是幂等的,见 Filesystem.delete 的注释
            Files.deleteIfExists(resolve(path, false));
        } catch (IOException e) {
            throw new StorageException("删不了 " + path + ":" + e.getMessage(), e);
        }
    }

    @Override
    public void copy(String from, String to) {
        Path source = resolve(from, false);
        Path target = resolve(to, true);
        requireExists(source, from);
        createParent(target);

        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(
                    "复制失败 %s → %s:%s".formatted(from, to, e.getMessage()), e);
        }
    }

    @Override
    public void move(String from, String to) {
        Path source = resolve(from, false);
        Path target = resolve(to, true);
        requireExists(source, from);
        createParent(target);

        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            throw new StorageException(
                    "移动失败 %s → %s(跨文件系统?):%s".formatted(from, to, e.getMessage()), e);
        } catch (IOException e) {
            throw new StorageException(
                    "移动失败 %s → %s:%s".formatted(from, to, e.getMessage()), e);
        }
    }

    // ================= 目录 =================

    @Override
    public List<String> files(String directory) {
        return list(directory, false, true);
    }

    @Override
    public List<String> allFiles(String directory) {
        return list(directory, true, true);
    }

    @Override
    public List<String> directories(String directory) {
        return list(directory, false, false);
    }

    /**
     * 列目录。
     *
     * <p>目录不存在时返回**空列表**而不是抛 —— 「这个目录下有哪些文件」
     * 对一个还没建的目录的自然答案是「没有」。
     *
     * <p>返回的是**存储路径**(相对 root、用 {@code /}),
     * 不是文件系统路径 —— 后者在 Windows 上带反斜杠,
     * 而那些路径会被直接传回 {@code Storage.get(...)}。
     */
    private List<String> list(String directory, boolean recursive, boolean wantFiles) {
        Path dir = directory == null || directory.isBlank() || directory.equals("/")
                ? root
                : resolve(directory, false);

        if (!Files.isDirectory(dir)) return List.of();

        var out = new ArrayList<String>();
        try (var stream = recursive
                ? Files.walk(dir)
                : Files.list(dir)) {

            stream.forEach(entry -> {
                if (entry.equals(dir)) return;
                boolean isFile = Files.isRegularFile(entry);
                if (isFile != wantFiles) return;

                out.add(toStoragePath(entry));
            });
        } catch (IOException e) {
            throw new StorageException("列不了目录 " + directory + ":" + e.getMessage(), e);
        }

        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    private String toStoragePath(Path absolute) {
        // 分隔符一律 / —— 见 Filesystem 的类注释
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    @Override
    public void makeDirectory(String directory) {
        try {
            Files.createDirectories(resolve(directory, false));
        } catch (IOException e) {
            throw new StorageException(
                    "建不了目录 " + directory + ":" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteDirectory(String directory) {
        Path dir = resolve(directory, false);
        if (!Files.isDirectory(dir)) return;

        try (var stream = Files.walk(dir)) {
            // 倒序:先删文件再删目录。正序会在删一个非空目录时失败
            stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new StorageException(
                            "删不了 " + entry + ":" + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new StorageException(
                    "删不了目录 " + directory + ":" + e.getMessage(), e);
        }
    }

    // ================= URL =================

    @Override
    public String url(String path) {
        if (urlPrefix == null || urlPrefix.isBlank()) {
            throw new StorageException("""
                    磁盘 %s 没有配 url,拿不到公开 URL。
                      在 config/filesystems.yml 里配:
                        disks:
                          public:
                            driver: local
                            root: storage/app/public
                            url: ${APP_URL}/storage

                      这里报错而不是拼一个假 URL —— 后者会让
                      「图片显示不出来」变成一个前端去查的问题。"""
                    .formatted(name()), null);
        }
        return urlPrefix.replaceAll("/+$", "") + "/" + PathGuard.normalize(path);
    }

    /**
     * 临时签名 URL。
     *
     * <p>⚠ **local 驱动的实现与 S3 不同**,这个区别必须说清。
     *
     * <p>S3 的预签名 URL 直接指向对象存储 —— 应用完全不参与那次下载。
     *
     * <p>local 没有这种东西,所以这里签的是**一个应用自己的路由**:
     * {@code /storage/temporary?path=…&expires=…&signature=…}。
     * 那个路由由 starter 注册,它校验签名然后把文件流出去。
     *
     * <p>后果上的差异:
     * <ul>
     *   <li>下载**流过应用** —— 带宽与线程都算在应用头上;</li>
     *   <li>应用挂了链接就失效(S3 的不会);</li>
     *   <li>但它**真的可用** —— 而 Laravel 的 local 驱动在这里直接抛。</li>
     * </ul>
     */
    @Override
    public String temporaryUrl(String path, Duration ttl) {
        requireSigner("temporaryUrl");

        String normalized = PathGuard.normalize(path);
        String base = temporaryUrlPrefix.replaceAll("/+$", "")
                + "?path=" + urlEncode(normalized);

        return signer.sign(base, ttl);
    }

    /**
     * 临时上传 URL。
     *
     * <p>**local 驱动不支持**,而且这里刻意报错而不是返回一个
     * 应用自己的上传端点。
     *
     * <p>理由:这个 API 的**价值**在于「大文件不流过应用」,
     * 而一个指向应用的上传端点完全没有这个性质 ——
     * 返回它等于让调用方以为自己绕过了应用,而实际没有。
     * 那个误解会在一个 500 MB 的上传打爆内存时才被发现。
     */
    @Override
    public String temporaryUploadUrl(String path, Duration ttl) {
        throw new StorageException("""
                local 驱动不支持 temporaryUploadUrl。
                  这个 API 的价值在于「大文件直接传到存储,不流过应用」,
                  而 local 驱动没有这种东西。

                  返回一个指向应用的上传端点是**误导**:调用方会以为自己
                  绕过了应用,而实际没有 —— 那个误解会在一个 500 MB 的
                  上传打爆内存时才被发现。

                  要这个能力就用对象存储(s3 驱动,尚未实现 —— 见
                  FilesystemManager 里的说明)。""", null);
    }

    private void requireSigner(String method) {
        if (signer != null && temporaryUrlPrefix != null) return;

        throw new StorageException("""
                磁盘 %s 的 %s 不可用:没有装 URL 签名器。
                  正常情况下 starter 会装上它(它需要 app.key)。
                  单元测试里给 LocalFilesystem 的构造器传一个 UrlSigner
                  与一个 URL 前缀。"""
                .formatted(name(), method), null);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ================= 可见性 =================

    /**
     * 可见性。
     *
     * <p>⚠ **local 驱动的可见性是**磁盘级**的,不是文件级的。**
     *
     * <p>对象存储上每个对象有自己的 ACL;本地文件系统上「对外可见」
     * 取决于那个目录有没有被 web 服务器服务 —— 那是部署配置,
     * 不是文件属性。
     *
     * <p>所以这里返回磁盘的默认可见性,而 {@link #setVisibility} 报错。
     *
     * <p>不假装支持它:一个「成功」的
     * {@code setVisibility("a.png", "private")} 会让人以为那个文件
     * 不再对外可访问了 —— 而它仍然在 {@code public/storage} 下,
     * 任何人都能下载。那是一个真实的信息泄露。
     */
    @Override
    public String getVisibility(String path) {
        return defaultVisibility;
    }

    @Override
    public void setVisibility(String path, String visibility) {
        throw new StorageException("""
                local 驱动不支持按文件设可见性。
                  本地文件系统上「对外可见」取决于那个目录有没有被 web
                  服务器服务 —— 那是部署配置,不是文件属性。

                  这里报错而不是静默成功 —— 一个「成功」的
                  setVisibility("%s", "%s") 会让人以为那个文件不再对外
                  可访问了,而它仍然在 public 目录下,任何人都能下载。
                  那是一个真实的信息泄露。

                  要按文件控制就把私有文件放到一个 **private 磁盘**
                  (root 不在 web 根下),用 temporaryUrl 给出限时访问。"""
                .formatted(path, visibility), null);
    }

    // ================= 工具 =================

    private void requireExists(Path target, String path) {
        if (Files.exists(target)) return;
        throw new FileNotFoundException(
                "磁盘 %s 上没有 %s".formatted(name(), path));
    }

    private void createParent(Path target) {
        Path parent = target.getParent();
        if (parent == null) return;

        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new StorageException(
                    "建不了父目录 " + parent + ":" + e.getMessage(), e);
        }
    }

    /** root 的绝对路径 —— 测试与诊断用 */
    public Path rootPath() {
        return root;
    }
}
