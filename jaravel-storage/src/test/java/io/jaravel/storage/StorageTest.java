package io.jaravel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.jaravel.support.io.ContentSniffer;
import io.jaravel.storage.disk.LocalFilesystem;
import io.jaravel.storage.disk.MemoryFilesystem;
import io.jaravel.storage.disk.MimeTypes;

/** Storage 门面、local / memory 驱动、上传安全、fake 断言 */
class StorageTest {

    @TempDir
    Path tempRoot;

    private LocalFilesystem local;
    private MemoryFilesystem memory;

    @BeforeEach
    void setUp() {
        local = new LocalFilesystem(tempRoot);
        memory = new MemoryFilesystem();

        Storage.use(new FilesystemManager()
                .register("local", local)
                .register("memory", memory)
                .defaultDisk("local"));
    }

    @AfterEach
    void tearDown() {
        Storage.reset();
    }

    // ================= 两个驱动的共同行为 =================

    /**
     * 同一组断言跑在两个驱动上。
     *
     * <p>这是这个测试类里最有价值的部分:一个只测 memory 驱动的测试
     * 会漏掉 local 的路径处理,而一个只测 local 的测试会让
     * {@code Storage.fake()} 的行为无人验证 ——
     * 而那两者的差异正是最容易出问题的地方。
     */
    @Nested
    @DisplayName("两个驱动的共同行为")
    class CommonBehaviour {

        private Filesystem diskOf(String name) {
            return name.equals("local") ? local : memory;
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("写了能读回来")
        void roundTrip(String driver) {
            var disk = diskOf(driver);

            disk.put("a/b.txt", "内容".getBytes(StandardCharsets.UTF_8));

            assertThat(disk.exists("a/b.txt")).isTrue();
            assertThat(disk.missing("a/b.txt")).isFalse();
            assertThat(disk.getString("a/b.txt")).contains("内容");
            assertThat(disk.size("a/b.txt")).isEqualTo(6);   // UTF-8 两个汉字
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("★ 读不存在的文件返回 empty,不抛")
        void readingMissingReturnsEmpty(String driver) {
            var disk = diskOf(driver);

            assertThat(disk.get("nope.txt"))
                    .as("「文件不存在」对读是一个正常结果 —— "
                            + "为此抛异常会让每个读取点都要包一层 try/catch")
                    .isEmpty();
            assertThat(disk.getString("nope.txt")).isEmpty();
            assertThat(disk.readStream("nope.txt")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("对不存在的文件问大小/时间会抛")
        void metadataOnMissingThrows(String driver) {
            var disk = diskOf(driver);

            assertThatThrownBy(() -> disk.size("nope.txt"))
                    .isInstanceOf(Filesystem.FileNotFoundException.class);
            assertThatThrownBy(() -> disk.lastModified("nope.txt"))
                    .isInstanceOf(Filesystem.FileNotFoundException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("★ 删除是幂等的 —— 删不存在的文件不报错")
        void deleteIsIdempotent(String driver) {
            var disk = diskOf(driver);

            assertThatCode(() -> disk.delete("nope.txt")).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("append / prepend")
        void appendAndPrepend(String driver) {
            var disk = diskOf(driver);

            disk.append("log.txt", "第二行\n");
            disk.prepend("log.txt", "第一行\n");

            assertThat(disk.getString("log.txt")).contains("第一行\n第二行\n");
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("copy / move")
        void copyAndMove(String driver) {
            var disk = diskOf(driver);
            disk.putString("a.txt", "x");

            disk.copy("a.txt", "dir/b.txt");
            assertThat(disk.exists("a.txt")).isTrue();
            assertThat(disk.getString("dir/b.txt")).contains("x");

            disk.move("a.txt", "dir/c.txt");
            assertThat(disk.exists("a.txt")).isFalse();
            assertThat(disk.getString("dir/c.txt")).contains("x");
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("复制/移动一个不存在的文件会抛")
        void copyMissingThrows(String driver) {
            var disk = diskOf(driver);

            assertThatThrownBy(() -> disk.copy("nope.txt", "x.txt"))
                    .isInstanceOf(Filesystem.FileNotFoundException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("files 不递归,allFiles 递归")
        void listing(String driver) {
            var disk = diskOf(driver);
            disk.putString("dir/a.txt", "1");
            disk.putString("dir/sub/b.txt", "2");

            assertThat(disk.files("dir")).containsExactly("dir/a.txt");
            assertThat(disk.allFiles("dir"))
                    .containsExactly("dir/a.txt", "dir/sub/b.txt");
            assertThat(disk.directories("dir")).containsExactly("dir/sub");
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("★ 列一个不存在的目录返回空列表,不抛")
        void listingMissingDirectory(String driver) {
            assertThat(diskOf(driver).files("nope"))
                    .as("「这个目录下有哪些文件」对一个还没建的目录"
                            + "的自然答案是「没有」")
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("★ 返回的路径一律用 / —— 即便在 Windows 上")
        void pathsUseForwardSlashes(String driver) {
            var disk = diskOf(driver);
            disk.putString("dir/sub/a.txt", "x");

            assertThat(disk.allFiles("dir"))
                    .as("存储路径会进数据库、进 URL —— 一个在 Windows 上"
                            + "存成 dir\\\\a.txt 的路径在 Linux 的生产环境里读不出来")
                    .allSatisfy(path -> assertThat(path).doesNotContain("\\"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("deleteDirectory 递归删")
        void deleteDirectoryRecurses(String driver) {
            var disk = diskOf(driver);
            disk.putString("dir/a.txt", "1");
            disk.putString("dir/sub/b.txt", "2");

            disk.deleteDirectory("dir");

            assertThat(disk.allFiles("dir")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("★ 两个驱动**都**过路径安全检查")
        void bothDriversGuardPaths(String driver) {
            var disk = diskOf(driver);

            assertThatThrownBy(() -> disk.put("../escape.txt", new byte[0]))
                    .as("一个不做检查的内存驱动会让「路径穿越的测试」"
                            + "在内存驱动上通过、在 local 上失败 —— "
                            + "于是那个安全检查的测试实际上什么都没测")
                    .isInstanceOf(PathGuard.UnsafePathException.class);
            assertThatThrownBy(() -> disk.get("a .txt"))
                    .isInstanceOf(PathGuard.UnsafePathException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"local", "memory"})
        @DisplayName("流式读写")
        void streams(String driver) throws Exception {
            var disk = diskOf(driver);

            disk.writeStream("big.bin", new ByteArrayInputStream(new byte[] {1, 2, 3}));

            try (var in = disk.readStream("big.bin").orElseThrow()) {
                assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
            }
        }
    }

    // ================= local 特有 =================

    @Nested
    @DisplayName("local 驱动")
    class LocalSpecific {

        @Test
        @DisplayName("★ 真的写到了 root 下的那个位置")
        void writesToTheRealPath() throws Exception {
            local.putString("a/b.txt", "内容");

            Path onDisk = tempRoot.resolve("a/b.txt");
            assertThat(Files.exists(onDisk)).isTrue();
            assertThat(Files.readString(onDisk)).isEqualTo("内容");
        }

        @Test
        @DisplayName("父目录自动创建")
        void createsParentDirectories() {
            assertThatCode(() -> local.putString("deep/nested/path/a.txt", "x"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 没配 url 时 url() 报错,不拼一个假的")
        void urlRequiresConfiguration() {
            assertThatThrownBy(() -> local.url("a.png"))
                    .hasMessageContaining("没有配 url")
                    .hasMessageContaining("前端去查的问题");
        }

        @Test
        @DisplayName("配了 url 之后拼出公开 URL")
        void urlWithPrefix() {
            var publicDisk = new LocalFilesystem(tempRoot,
                    "https://example.com/storage/", false, Set.of(), "public",
                    null, null);

            assertThat(publicDisk.url("a/b.png"))
                    .as("前缀末尾的斜杠要去掉,否则会出现 //")
                    .isEqualTo("https://example.com/storage/a/b.png");
        }

        @Test
        @DisplayName("★ public 磁盘拒绝可执行类型,private 磁盘不拦")
        void publicDiskRejectsExecutables() {
            var publicDisk = new LocalFilesystem(tempRoot, "https://x/", true,
                    Set.of(), "public", null, null);

            assertThatThrownBy(() -> publicDisk.putString("evil.php", "<?php ?>"))
                    .hasMessageContaining("远程代码执行");

            assertThatCode(() -> local.putString("evil.php", "<?php ?>"))
                    .as("private 磁盘不在 web 根下,放一个 .php 在那里无害")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 读不拦可执行类型 —— 只有写拦")
        void readingIsNotBlocked() {
            local.putString("legacy.php", "x");

            var publicDisk = new LocalFilesystem(tempRoot, "https://x/", true,
                    Set.of(), "public", null, null);

            assertThat(publicDisk.getString("legacy.php"))
                    .as("已经在那里的文件要能被读到(比如为了删掉它)——"
                            + "读也拦的话连清理都做不了")
                    .contains("x");
        }

        @Test
        @DisplayName("★ 逃出 root 的路径被拦")
        void cannotEscapeRoot() {
            assertThatThrownBy(() -> local.putString("../outside.txt", "x"))
                    .isInstanceOf(PathGuard.UnsafePathException.class);
        }

        @Test
        @DisplayName("★ setVisibility 报错,不静默成功")
        void visibilityIsNotPerFile() {
            local.putString("a.png", "x");

            assertThatThrownBy(() -> local.setVisibility("a.png", "private"))
                    .as("一个「成功」的 setVisibility 会让人以为那个文件"
                            + "不再对外可访问了,而它仍然在 public 目录下 —— "
                            + "那是一个真实的信息泄露")
                    .hasMessageContaining("真实的信息泄露");

            assertThat(local.getVisibility("a.png")).isEqualTo("private");
        }

        @Test
        @DisplayName("★ temporaryUploadUrl 报错,不返回一个应用端点")
        void temporaryUploadUrlIsNotFaked() {
            assertThatThrownBy(() -> local.temporaryUploadUrl("a.zip",
                    Duration.ofMinutes(10)))
                    .hasMessageContaining("不流过应用")
                    .hasMessageContaining("打爆内存");
        }

        @Test
        @DisplayName("★ temporaryUrl 真的签出一个可校验的 URL")
        void temporaryUrlIsSigned() {
            var signer = io.jaravel.core.crypt.UrlSigner.fromBase64(
                    "base64:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
            var disk = new LocalFilesystem(tempRoot, null, false, Set.of(),
                    "private", signer, "https://example.com/storage/temporary");

            String url = disk.temporaryUrl("private/report.pdf", Duration.ofMinutes(15));

            assertThat(url)
                    .as("local 的临时 URL 指向一个应用自己的路由 —— "
                            + "而 Laravel 的 local 驱动在这里直接抛")
                    .contains("path=private%2Freport.pdf")
                    .contains("signature=");
            assertThat(signer.isValid(url)).isTrue();

            assertThat(signer.isValid(url.replace("report.pdf", "secret.pdf")))
                    .as("改了路径签名就不对了 —— 否则这个 URL 等于"
                            + "一个任意文件读取漏洞")
                    .isFalse();
        }

        @Test
        @DisplayName("没装签名器时 temporaryUrl 报错")
        void temporaryUrlNeedsSigner() {
            assertThatThrownBy(() -> local.temporaryUrl("a.pdf", Duration.ofMinutes(1)))
                    .hasMessageContaining("没有装 URL 签名器");
        }
    }

    // ================= memory 特有 =================

    @Nested
    @DisplayName("memory 驱动")
    class MemorySpecific {

        @Test
        @DisplayName("★ get 返回副本 —— 改掉它不影响存储")
        void getReturnsACopy() {
            memory.put("a.bin", new byte[] {1, 2, 3});

            byte[] first = memory.get("a.bin").orElseThrow();
            first[0] = 99;

            assertThat(memory.get("a.bin").orElseThrow())
                    .as("一个会被意外共享的数组会让测试出现诡异的串台")
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("★ 支持按文件设可见性 —— 它模拟的是对象存储")
        void supportsPerFileVisibility() {
            memory.putString("a.png", "x");

            memory.setVisibility("a.png", "public");

            assertThat(memory.getVisibility("a.png"))
                    .as("Storage.fake() 的典型用法是「测一段本该跑在 S3 上的代码」,"
                            + "而 S3 的每个对象有自己的 ACL")
                    .isEqualTo("public");
        }

        @Test
        @DisplayName("★ makeDirectory 是空操作,建出来的目录不出现在 directories 里")
        void emptyDirectoriesDoNotExist() {
            memory.makeDirectory("empty/dir");

            assertThat(memory.directories("empty"))
                    .as("这与 local 不同,而它与**对象存储**一致 —— "
                            + "S3 也没有真正的目录")
                    .isEmpty();
        }

        @Test
        @DisplayName("url() 返回一个假前缀而不是抛")
        void urlDoesNotThrow() {
            assertThat(memory.url("a.png"))
                    .as("抛异常会让一个用到 url() 的控制器没法在 fake 下测")
                    .isEqualTo("memory://a.png");
        }
    }

    // ================= 上传 =================

    @Nested
    @DisplayName("上传(docs/11 §3.3 的安全约束)")
    class Uploads {

        private static final byte[] PNG = {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        @Test
        @DisplayName("★ putFile 用随机名,且扩展名来自**内容嗅探**")
        void putFileSniffsContent() {
            // 客户端声称这是一个 .php,内容其实是 PNG
            var upload = Storage.upload(PNG, "evil.php");

            String path = Storage.putFile("avatars", upload);

            assertThat(path)
                    .as("扩展名由内容决定,不信客户端给的文件名")
                    .endsWith(".png")
                    .doesNotContain("evil")
                    .doesNotContain("php");
            assertThat(Storage.exists(path)).isTrue();
        }

        @Test
        @DisplayName("★ 嗅探不出来时退回**清理后的**原始扩展名")
        void fallsBackToSanitizedExtension() {
            var upload = Storage.upload("随便的内容".getBytes(StandardCharsets.UTF_8),
                    "notes.txt");

            String path = Storage.putFile("notes", upload);

            assertThat(path).endsWith(".txt");
        }

        @Test
        @DisplayName("★ 随机名防覆盖 —— 同名上传两次得到两个文件")
        void randomNamesPreventOverwrite() {
            var upload = Storage.upload(PNG, "avatar.png");

            String first = Storage.putFile("avatars", upload);
            String second = Storage.putFile("avatars", upload);

            assertThat(first).isNotEqualTo(second);
            assertThat(Storage.files("avatars"))
                    .as("两个用户上传同名文件时,后者不该覆盖前者")
                    .hasSize(2);
        }

        @Test
        @DisplayName("★ putFileAs 的名字会被清理")
        void putFileAsSanitizes() {
            var upload = Storage.upload(PNG, "x.png");

            String path = Storage.putFileAs("avatars", upload, "../../etc/passwd");

            assertThat(path)
                    .as("sanitize 之后只剩最后一段")
                    .isEqualTo("avatars/passwd");
        }

        @Test
        @DisplayName("原始文件名里的路径穿越被去掉")
        void traversalInNameIsStripped() {
            var upload = Storage.upload(PNG, "../../../etc/passwd.png");

            String path = Storage.putFile("avatars", upload);

            assertThat(path).startsWith("avatars/").doesNotContain("..");
        }

        @Test
        @DisplayName("可以指定磁盘")
        void canTargetADisk() {
            var upload = Storage.upload(PNG, "a.png");

            String path = Storage.putFile("avatars", upload, "memory");

            assertThat(memory.exists(path)).isTrue();
            assertThat(local.exists(path)).isFalse();
        }
    }

    // ================= 内容嗅探 =================

    @Nested
    @DisplayName("内容嗅探")
    class Sniffing {

        @Test
        @DisplayName("常见类型的魔数")
        void knownMagicNumbers() {
            assertThat(ContentSniffer.extensionOf(new byte[] {
                    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                    .contains("png");
            assertThat(ContentSniffer.extensionOf(new byte[] {
                    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                    .contains("jpg");
            assertThat(ContentSniffer.extensionOf("GIF89a".getBytes()))
                    .contains("gif");
            assertThat(ContentSniffer.extensionOf("%PDF-1.7".getBytes()))
                    .contains("pdf");
            assertThat(ContentSniffer.extensionOf(new byte[] {'P', 'K', 3, 4, 0, 0}))
                    .contains("zip");
        }

        @Test
        @DisplayName("认不出时返回 empty")
        void unknownReturnsEmpty() {
            assertThat(ContentSniffer.extensionOf("随便一段文本".getBytes()))
                    .isEmpty();
            assertThat(ContentSniffer.extensionOf(new byte[] {1, 2})).isEmpty();
            assertThat(ContentSniffer.extensionOf(null)).isEmpty();
        }

        @Test
        @DisplayName("★ 声称的扩展名与内容不符时报出来")
        void detectsMismatch() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

            assertThat(ContentSniffer.mismatch("php", png)).contains("png");
            assertThat(ContentSniffer.mismatch("png", png)).isEmpty();
        }

        @Test
        @DisplayName("★ jpg/jpeg 与 docx/zip 这些别名算一致")
        void aliasesAreEquivalent() {
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            byte[] zip = {'P', 'K', 3, 4, 0, 0};

            assertThat(ContentSniffer.mismatch("jpeg", jpeg)).isEmpty();
            assertThat(ContentSniffer.mismatch("docx", zip))
                    .as("docx 确实是 zip —— 不处理这个别名的话一个正常的"
                            + " .docx 上传会被判成「内容与扩展名不符」而被拒")
                    .isEmpty();
            assertThat(ContentSniffer.mismatch("xlsx", zip)).isEmpty();
            assertThat(ContentSniffer.mismatch("png", zip)).contains("zip");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<html><body>x</body></html>",
                "<!DOCTYPE html>",
                "  \n <script>alert(1)</script>",
                "<svg onload=alert(1)>",
                "<?xml version=\"1.0\"?><svg/>"})
        @DisplayName("★ 看起来像标记的内容被识别出来 —— 那是存储型 XSS")
        void detectsMarkup(String content) {
            assertThat(ContentSniffer.looksLikeMarkup(
                    content.getBytes(StandardCharsets.UTF_8)))
                    .as("一个带 <script> 的文件被命名为 .jpg 上传,"
                            + "而浏览器在某些情况下会把它当 HTML 渲染")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 跳过 BOM 与开头的空白")
        void skipsBomAndWhitespace() {
            byte[] withBom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
                    '\n', ' ', '<', 'h', 't', 'm', 'l', '>'};

            assertThat(ContentSniffer.looksLikeMarkup(withBom))
                    .as("一个只看第一个字节的检查会漏掉它")
                    .isTrue();
        }

        @Test
        @DisplayName("普通内容不被误判成标记")
        void plainContentIsNotMarkup() {
            assertThat(ContentSniffer.looksLikeMarkup("普通文本".getBytes())).isFalse();
            assertThat(ContentSniffer.looksLikeMarkup(new byte[] {
                    (byte) 0x89, 'P', 'N', 'G'})).isFalse();
        }
    }

    // ================= MIME =================

    @Nested
    @DisplayName("MIME 类型")
    class Mime {

        @Test
        @DisplayName("按扩展名猜")
        void byExtension() {
            assertThat(MimeTypes.forPath("a.png")).isEqualTo("image/png");
            assertThat(MimeTypes.forPath("a.PDF")).isEqualTo("application/pdf");
            assertThat(MimeTypes.forPath("dir/a.docx")).contains("wordprocessingml");
        }

        @Test
        @DisplayName("★ 猜不出时用 octet-stream,**不是** text/plain")
        void unknownIsOctetStream() {
            assertThat(MimeTypes.forPath("a.unknown"))
                    .as("浏览器对 text/* 会尝试嗅探并可能当 HTML 渲染 —— "
                            + "于是一个伪装成 .dat 的 HTML 文件变成了存储型 XSS")
                    .isEqualTo("application/octet-stream");
            assertThat(MimeTypes.forPath("noext")).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("isImage")
        void imageCheck() {
            assertThat(MimeTypes.isImage("image/png")).isTrue();
            assertThat(MimeTypes.isImage("application/pdf")).isFalse();
            assertThat(MimeTypes.isImage(null)).isFalse();
        }
    }

    // ================= 磁盘管理 =================

    @Nested
    @DisplayName("磁盘管理")
    class Disks {

        @Test
        @DisplayName("按名字切换")
        void switching() {
            Storage.disk("memory").putString("a.txt", "在内存里");

            assertThat(memory.exists("a.txt")).isTrue();
            assertThat(local.exists("a.txt")).isFalse();
        }

        @Test
        @DisplayName("名字写错时给出相近的候选")
        void unknownDiskSuggests() {
            assertThatThrownBy(() -> Storage.disk("memroy"))
                    .hasMessageContaining("是否想写 \"memory\"");
        }

        @Test
        @DisplayName("★ 未实现的驱动报错,不退回 local")
        void unsupportedDriverFails() {
            assertThatThrownBy(() ->
                    FilesystemManager.unsupportedDriver("s3", "uploads"))
                    .hasMessageContaining("还没实现")
                    .hasMessageContaining("容器一重启就没了");
        }

        @Test
        @DisplayName("没设默认磁盘时报错")
        void noDefaultDisk() {
            Storage.use(new FilesystemManager().register("x", memory));

            assertThatThrownBy(Storage::disk)
                    .hasMessageContaining("没有设默认磁盘");
        }
    }

    // ================= fake =================

    @Nested
    @DisplayName("Storage.fake()")
    class Faking {

        @Test
        @DisplayName("★ fake 之后写到内存里,真实磁盘不动")
        void writesGoToMemory() {
            var disk = Storage.fake("local");

            Storage.putString("a.txt", "x");

            disk.assertExists("a.txt");
            assertThat(local.exists("a.txt"))
                    .as("真实磁盘不该被动")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 能 fake 一个**没注册**的磁盘")
        void canFakeUnregisteredDisk() {
            var disk = Storage.fake("s3");

            Storage.disk("s3").putString("backups/db.sql", "dump");

            disk.assertExists("backups/db.sql");
        }

        @Test
        @DisplayName("docs/14 §4.3 的那三个断言")
        void documentedAssertions() {
            var disk = Storage.fake("s3");

            Storage.disk("s3").putString("avatars/1.png", "x");

            disk.assertExists("avatars/1.png")
                .assertMissing("avatars/old.png")
                .assertCount("avatars", 1);
        }

        @Test
        @DisplayName("断言失败时列出实际有的文件")
        void failureListsActualFiles() {
            var disk = Storage.fake("s3");
            Storage.disk("s3").putString("avatars/2.png", "x");

            assertThatThrownBy(() -> disk.assertExists("avatars/1.png"))
                    .hasMessageContaining("实际有的文件")
                    .hasMessageContaining("avatars/2.png");
        }

        @Test
        @DisplayName("★ assertSingleFileIn —— 测随机文件名的上传")
        void singleFileAssertion() {
            var disk = Storage.fake("local");

            Storage.putFile("avatars",
                    Storage.upload(new byte[] {(byte) 0x89, 'P', 'N', 'G',
                            0x0D, 0x0A, 0x1A, 0x0A}, "x.png"));

            String path = disk.assertSingleFileIn("avatars");

            assertThat(path)
                    .as("putFile 用随机名,所以测试没法预先知道路径 —— "
                            + "而那恰恰是最该被测的那条路径")
                    .endsWith(".png");
            disk.assertContent(path, new byte[] {(byte) 0x89, 'P', 'N', 'G',
                    0x0D, 0x0A, 0x1A, 0x0A});
        }

        @Test
        @DisplayName("内容断言")
        void contentAssertions() {
            var disk = Storage.fake("local");
            Storage.putString("a.txt", "内容");

            disk.assertContent("a.txt", "内容");

            assertThatThrownBy(() -> disk.assertContent("a.txt", "别的"))
                    .hasMessageContaining("内容不符");
        }

        @Test
        @DisplayName("assertEmpty")
        void emptyAssertion() {
            var disk = Storage.fake("local");
            disk.assertEmpty();

            Storage.putString("a.txt", "x");
            assertThatThrownBy(disk::assertEmpty)
                    .hasMessageContaining("应该是空的");
        }

        @Test
        @DisplayName("★ 没 fake 时查记录会报错,不返回真实磁盘")
        void queryingWithoutFakeFails() {
            assertThatThrownBy(() -> Storage.manager().fakedDisk("local"))
                    .as("返回真实磁盘会让断言去动真实存储,"
                            + "而在 CI 上那可能是生产桶")
                    .hasMessageContaining("没有被 fake");
        }

        @Test
        @DisplayName("stopFaking 之后恢复真实磁盘")
        void stopFaking() {
            Storage.fake("local");
            Storage.stopFaking();

            Storage.putString("a.txt", "x");
            assertThat(local.exists("a.txt")).isTrue();
        }
    }

    // ================= 门面 =================

    @Test
    @DisplayName("未装配时报错并给出测试里的装配方式")
    void unconfiguredFacadeExplains() {
        Storage.reset();

        assertThatThrownBy(() -> Storage.exists("a.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未装配")
                .hasMessageContaining("MemoryFilesystem");
    }

    @Test
    @DisplayName("批量删除")
    void bulkDelete() {
        Storage.putString("a.txt", "1");
        Storage.putString("b.txt", "2");

        Storage.delete(List.of("a.txt", "b.txt", "nope.txt"));

        assertThat(Storage.exists("a.txt")).isFalse();
        assertThat(Storage.exists("b.txt")).isFalse();
    }
}
