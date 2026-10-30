package io.jaravel.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.jaravel.storage.disk.ContentSniffer;
import io.jaravel.storage.disk.MemoryFilesystem;

/**
 * 文件存储的静态门面(docs/11 §3.1)。
 *
 * <pre>{@code
 * Storage.put("avatars/1.png", bytes);
 * Optional<byte[]> content = Storage.get("avatars/1.png");
 * Storage.disk("s3").put("backups/db.sql", bytes);
 * String url = Storage.temporaryUrl("private/report.pdf", Duration.ofMinutes(15));
 * }</pre>
 */
public final class Storage {

    private static volatile FilesystemManager manager;

    private Storage() {
    }

    public static void use(FilesystemManager configured) {
        manager = configured;
    }

    public static FilesystemManager manager() {
        FilesystemManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    存储尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Storage.use(new FilesystemManager()
                                .register("local", new MemoryFilesystem())
                                .defaultDisk("local"));""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    /** 指定磁盘 */
    public static Filesystem disk(String name) {
        return manager().disk(name);
    }

    /** 默认磁盘 */
    public static Filesystem disk() {
        return manager().disk();
    }

    // ================= 读 =================

    public static Optional<byte[]> get(String path) {
        return disk().get(path);
    }

    public static Optional<String> getString(String path) {
        return disk().getString(path);
    }

    public static Optional<InputStream> readStream(String path) {
        return disk().readStream(path);
    }

    public static boolean exists(String path) {
        return disk().exists(path);
    }

    public static boolean missing(String path) {
        return disk().missing(path);
    }

    public static long size(String path) {
        return disk().size(path);
    }

    public static Instant lastModified(String path) {
        return disk().lastModified(path);
    }

    public static String mimeType(String path) {
        return disk().mimeType(path);
    }

    // ================= 写 =================

    public static void put(String path, byte[] contents) {
        disk().put(path, contents);
    }

    public static void put(String path, InputStream contents) {
        disk().writeStream(path, contents);
    }

    public static void putString(String path, String contents) {
        disk().putString(path, contents);
    }

    public static void writeStream(String path, InputStream contents) {
        disk().writeStream(path, contents);
    }

    public static void append(String path, String contents) {
        disk().append(path, contents);
    }

    public static void prepend(String path, String contents) {
        disk().prepend(path, contents);
    }

    // ================= 上传 =================

    /**
     * 存一个上传的文件,**用随机文件名**(docs/11 §3.1 的 {@code putFile})。
     *
     * <p>随机名是默认行为(docs/11 §3.3 的那条安全约束)——
     * 它一次解决三件事:路径穿越、覆盖、原始文件名里的信息泄露。
     *
     * <p>扩展名由**内容嗅探**得出,不信客户端给的文件名。
     * 嗅探不出来时退回**清理后的**原始扩展名 —— 而不是直接信它。
     *
     * @return 存下来的存储路径
     */
    public static String putFile(String directory, Upload upload) {
        return putFile(directory, upload, null);
    }

    /** 同上,指定磁盘 */
    public static String putFile(String directory, Upload upload, String diskName) {
        byte[] content = upload.content();

        String extension = ContentSniffer.extensionOf(content)
                .orElseGet(() -> PathGuard.extensionOf(upload.originalName()));

        String name = PathGuard.hashName(extension);
        String path = PathGuard.normalize(directory) + "/" + name;

        (diskName == null ? disk() : disk(diskName)).put(path, content);
        return path;
    }

    /**
     * 存一个上传的文件,**用给定的名字**。
     *
     * <p>⚠ 名字会过 {@link PathGuard#sanitizeFileName} —— 它不是
     * 「原样使用」。即便如此,**默认仍然应该用 {@link #putFile}**:
     * sanitize 保不住「覆盖」与「信息泄露」两件事
     * (见 PathGuard 里的说明)。
     */
    public static String putFileAs(String directory, Upload upload, String name) {
        return putFileAs(directory, upload, name, null);
    }

    public static String putFileAs(String directory, Upload upload, String name,
                                   String diskName) {
        String safe = PathGuard.sanitizeFileName(name);
        String path = PathGuard.normalize(directory) + "/" + safe;

        (diskName == null ? disk() : disk(diskName)).put(path, upload.content());
        return path;
    }

    // ================= 删 / 移动 / 复制 =================

    public static void delete(String path) {
        disk().delete(path);
    }

    public static void delete(List<String> paths) {
        disk().delete(paths);
    }

    public static void copy(String from, String to) {
        disk().copy(from, to);
    }

    public static void move(String from, String to) {
        disk().move(from, to);
    }

    // ================= 目录 =================

    public static List<String> files(String directory) {
        return disk().files(directory);
    }

    public static List<String> allFiles(String directory) {
        return disk().allFiles(directory);
    }

    public static List<String> directories(String directory) {
        return disk().directories(directory);
    }

    public static void makeDirectory(String directory) {
        disk().makeDirectory(directory);
    }

    public static void deleteDirectory(String directory) {
        disk().deleteDirectory(directory);
    }

    // ================= URL =================

    public static String url(String path) {
        return disk().url(path);
    }

    public static String temporaryUrl(String path, Duration ttl) {
        return disk().temporaryUrl(path, ttl);
    }

    public static String temporaryUploadUrl(String path, Duration ttl) {
        return disk().temporaryUploadUrl(path, ttl);
    }

    // ================= 可见性 =================

    public static String getVisibility(String path) {
        return disk().getVisibility(path);
    }

    public static void setVisibility(String path, String visibility) {
        disk().setVisibility(path, visibility);
    }

    // ================= 测试 =================

    /**
     * 把一个磁盘换成内存实现(docs/14 §4.3)。
     *
     * <pre>{@code
     * var disk = Storage.fake("s3");
     * controller.store(requestWithFile);
     * disk.assertExists("avatars/1.png");
     * }</pre>
     *
     * @return 一个带断言方法的句柄
     */
    public static FakeDisk fake(String name) {
        return new FakeDisk(name, manager().fake(name));
    }

    public static FakeDisk fake() {
        String name = manager().defaultDiskName();
        return fake(name == null ? "local" : name);
    }

    public static void stopFaking() {
        manager().stopFaking();
    }

    /**
     * 一个被 fake 掉的磁盘 + 断言。
     *
     * <p>断言放在这个句柄上而不是 {@code Storage} 的静态方法上 ——
     * 因为可以同时 fake 多个磁盘({@code local} 与 {@code s3}),
     * 而一组静态断言分不清是在问哪一个。
     *
     * <p>这与 {@code Mail.assertSent(...)} / {@code Event.assertDispatched(...)}
     * 的形式不同,是刻意的:那两个只有一个全局状态。
     */
    public static final class FakeDisk {

        private final String name;
        private final MemoryFilesystem disk;

        FakeDisk(String name, MemoryFilesystem disk) {
            this.name = name;
            this.disk = disk;
        }

        /** 底层的内存磁盘 —— 复杂断言时直接用 */
        public MemoryFilesystem raw() {
            return disk;
        }

        public FakeDisk assertExists(String path) {
            if (!disk.exists(path)) {
                throw new AssertionError("""
                        磁盘 "%s" 上应该有 %s,但没有。
                          实际有的文件:%s"""
                        .formatted(name, path, disk.allPaths()));
            }
            return this;
        }

        public FakeDisk assertMissing(String path) {
            if (disk.exists(path)) {
                throw new AssertionError(
                        "磁盘 \"%s\" 上不该有 %s,但它在。".formatted(name, path));
            }
            return this;
        }

        /** 断言某个目录下有几个文件(**不递归**) */
        public FakeDisk assertCount(String directory, int expected) {
            var files = disk.files(directory);
            if (files.size() != expected) {
                throw new AssertionError("""
                        磁盘 "%s" 的目录 %s 下应该有 %d 个文件,实际 %d 个:%s
                          (这个断言**不递归** —— 子目录里的文件不算。
                           要递归就用 raw().allFiles(...)。)"""
                        .formatted(name, directory, expected, files.size(), files));
            }
            return this;
        }

        /** 断言文件内容 */
        public FakeDisk assertContent(String path, byte[] expected) {
            assertExists(path);

            byte[] actual = disk.get(path).orElseThrow();
            if (!java.util.Arrays.equals(actual, expected)) {
                throw new AssertionError("""
                        %s 的内容不符。
                          期望 %d 字节,实际 %d 字节。"""
                        .formatted(path, expected.length, actual.length));
            }
            return this;
        }

        public FakeDisk assertContent(String path, String expected) {
            assertExists(path);

            String actual = disk.getString(path).orElseThrow();
            if (!actual.equals(expected)) {
                throw new AssertionError("""
                        %s 的内容不符。
                          期望:%s
                          实际:%s"""
                        .formatted(path, expected, actual));
            }
            return this;
        }

        public FakeDisk assertEmpty() {
            if (disk.count() > 0) {
                throw new AssertionError(
                        "磁盘 \"%s\" 应该是空的,实际有:%s"
                                .formatted(name, disk.allPaths()));
            }
            return this;
        }

        /**
         * 断言某个目录下有**恰好一个**文件,并返回它的路径。
         *
         * <p>存在的理由:{@code putFile} 用的是**随机文件名**,
         * 所以测试没法预先知道路径 —— 而那恰恰是最该被测的那条路径
         * (随机名是默认行为)。
         *
         * <p>没有这个方法的话,测随机名的上传只能写
         * {@code assertCount("avatars", 1)},而那证明不了内容对。
         */
        public String assertSingleFileIn(String directory) {
            var files = disk.files(directory);
            if (files.size() != 1) {
                throw new AssertionError("""
                        磁盘 "%s" 的目录 %s 下应该恰好有一个文件,实际 %d 个:%s"""
                        .formatted(name, directory, files.size(), files));
            }
            return files.get(0);
        }
    }

    /**
     * 一个上传的文件 —— 最小接口。
     *
     * <h2>⚠ HTTP 层的 {@code UploadedFile} 还没有</h2>
     * <p>docs/11 §3.3 的 {@code req.file("avatar")} 需要 HTTP 层的
     * multipart 处理,而**那还没实现**。
     *
     * <p>所以这里定义一个最小接口,让存储这一侧先完整 ——
     * 上传那一层接上时让 {@code UploadedFile} 实现它即可。
     *
     * <p>做成接口而不是等上传层:
     * <ul>
     *   <li>{@code putFile} 的安全行为(内容嗅探 + 随机名)
     *       是这一节的核心,它现在就能被完整地测;</li>
     *   <li>反过来(先做上传层)意味着那些安全检查要等,
     *       而「先上线功能、安全检查后补」是这类事故的标准剧本。</li>
     * </ul>
     */
    public interface Upload {

        /** 文件内容 */
        byte[] content();

        /**
         * 客户端给的原始文件名。
         *
         * <p>⚠ **完全不可信** —— 它可能是 {@code ../../etc/passwd}、
         * 可能带空字节、可能是一个伪装的扩展名。
         * {@code putFile} 只用它做「嗅探失败时的退路」,
         * 而且那个退路也过清理。
         */
        String originalName();

        /** 用一对值快速构造一个 —— 测试与非 HTTP 来源(比如解压出来的文件)用 */
        static Upload of(byte[] content, String originalName) {
            return new Upload() {
                @Override public byte[] content() { return content; }

                @Override public String originalName() { return originalName; }
            };
        }
    }
}
