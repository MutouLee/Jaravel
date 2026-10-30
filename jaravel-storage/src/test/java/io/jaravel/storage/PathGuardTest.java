package io.jaravel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/11 §3.3 的那组「必须实现,不是可选」的安全约束。
 *
 * <p>这些检查单独、密集地测 —— 一个安全检查的测试应该能枚举
 * 几十种恶意输入,而那在驱动的测试里会被淹掉。
 */
class PathGuardTest {

    // ================= 路径规范化 =================

    @Nested
    @DisplayName("路径规范化")
    class Normalization {

        @ParameterizedTest
        @CsvSource({
                "avatars/1.png,avatars/1.png",
                "/avatars/1.png,avatars/1.png",
                "avatars//1.png,avatars/1.png",
                "avatars/./1.png,avatars/1.png",
                "avatars\\1.png,avatars/1.png",
                "  a/b  ,  a/b"})
        @DisplayName("统一分隔符、去掉多余的斜杠与单点")
        void normalizes(String input, String expected) {
            assertThat(PathGuard.normalize(input.trim())).isEqualTo(expected.trim());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "../etc/passwd",
                "avatars/../../etc/passwd",
                "a/b/../../../x",
                "..",
                "avatars/..",
                "..\\..\\windows\\system32"})
        @DisplayName("★ 含 .. 的路径被**拒绝**,而不是解析掉")
        void rejectsDotDot(String path) {
            assertThatThrownBy(() -> PathGuard.normalize(path))
                    .isInstanceOf(PathGuard.UnsafePathException.class)
                    .hasMessageContaining("不允许 \"..\"")
                    .hasMessageContaining("攻击的第一步");
        }

        @Test
        @DisplayName("★ 含空字节的路径被拒 —— 它能绕过扩展名检查")
        void rejectsNullByte() {
            assertThatThrownBy(() -> PathGuard.normalize("a.png\u0000.php"))
                    .hasMessageContaining("空字节")
                    .hasMessageContaining("C 字符串");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a\nb.png", "a\rb.png", "a\u0001b.png", "a\u007fb.png"})
        @DisplayName("★ 含控制字符的路径被拒 —— \\r\\n 能注入 HTTP 头")
        void rejectsControlChars(String path) {
            assertThatThrownBy(() -> PathGuard.normalize(path))
                    .hasMessageContaining("控制字符");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "/", "///", "./"})
        @DisplayName("空路径被拒")
        void rejectsEmpty(String path) {
            assertThatThrownBy(() -> PathGuard.normalize(path))
                    .isInstanceOf(PathGuard.UnsafePathException.class);
        }
    }

    // ================= root 边界 =================

    @Nested
    @DisplayName("root 边界")
    class RootBoundary {

        @Test
        @DisplayName("root 内的路径通过")
        void withinRootPasses(@TempDir Path root) {
            assertThatCode(() -> PathGuard.requireWithin(root, root.resolve("a/b.png")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("root 外的路径被拒")
        void outsideRootFails(@TempDir Path root) {
            assertThatThrownBy(() -> PathGuard.requireWithin(root,
                    root.getParent().resolve("outside.png")))
                    .isInstanceOf(PathGuard.UnsafePathException.class)
                    .hasMessageContaining("逃出了磁盘的 root");
        }

        @Test
        @DisplayName("★ 符号链接逃出 root 时被拒 —— 字符串前缀比较挡不住它")
        void symlinkEscapeIsCaught(@TempDir Path base) throws Exception {
            Path root = Files.createDirectories(base.resolve("root"));
            Path outside = Files.createDirectories(base.resolve("outside"));
            Files.writeString(outside.resolve("secret.txt"), "机密");

            Path link = root.resolve("link");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.io.IOException noPermission) {
                // Windows 上非管理员建不了符号链接 —— 跳过而不是失败。
                // 这个检查在 Linux 的 CI 上会跑到
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "这个环境建不了符号链接:" + noPermission.getMessage());
                return;
            }

            // 字符串前缀是对的(root/link/secret.txt),
            // 但真实路径在 root 之外
            assertThatThrownBy(() -> PathGuard.requireWithin(root,
                    link.resolve("secret.txt")))
                    .as("这正是用 toRealPath 而不是 startsWith(字符串) 的理由")
                    .isInstanceOf(PathGuard.UnsafePathException.class);
        }
    }

    // ================= 文件名清理 =================

    @Nested
    @DisplayName("文件名清理")
    class Sanitizing {

        @ParameterizedTest
        @CsvSource({
                "../../etc/passwd,passwd",
                "a/b/c.png,c.png",
                "C:\\Windows\\evil.exe,evil.exe",
                "normal.png,normal.png"})
        @DisplayName("★ 只保留最后一段 —— 目录与 ../ 全部去掉")
        void keepsOnlyTheFileName(String input, String expected) {
            assertThat(PathGuard.sanitizeFileName(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 去掉空字节与控制字符")
        void stripsControlChars() {
            assertThat(PathGuard.sanitizeFileName("a\u0000b\nc.png"))
                    .isEqualTo("abc.png");
        }

        @ParameterizedTest
        @ValueSource(strings = {"CON", "con.txt", "NUL.png", "aux", "COM1", "lpt9.dat"})
        @DisplayName("★ Windows 保留名前加下划线 —— 带扩展名也算")
        void prefixesWindowsReserved(String name) {
            assertThat(PathGuard.sanitizeFileName(name))
                    .as("在 Windows 上创建一个叫 CON 的文件会失败或者"
                            + "写到设备里。这个检查在 Linux 上也做 —— "
                            + "一个在 Linux 上存下来的 CON.png 会在 Windows 的"
                            + "开发机上同步/解压时出问题")
                    .startsWith("_");
        }

        @Test
        @DisplayName("Windows 不允许的字符被换成下划线")
        void replacesIllegalChars() {
            assertThat(PathGuard.sanitizeFileName("a<b>c:d\"e|f?g*h.png"))
                    .isEqualTo("a_b_c_d_e_f_g_h.png");
        }

        @Test
        @DisplayName("★ 开头的点被去掉 —— 那会让文件变成隐藏文件")
        void stripsLeadingDots() {
            assertThat(PathGuard.sanitizeFileName("...hidden.png"))
                    .isEqualTo("hidden.png");
            assertThat(PathGuard.sanitizeFileName(".env")).isEqualTo("env");
        }

        @Test
        @DisplayName("清理后为空时给一个随机名")
        void emptyResultGetsRandomName() {
            assertThat(PathGuard.sanitizeFileName("../../"))
                    .as("不给随机名的话会写出一个空文件名")
                    .isNotBlank();
            assertThat(PathGuard.sanitizeFileName("\u0000\u0001")).isNotBlank();
            assertThat(PathGuard.sanitizeFileName(null)).isNotBlank();
        }

        @Test
        @DisplayName("★ 按**字节**截断到 255,不按字符")
        void truncatesByBytes() {
            String longChinese = "中".repeat(200) + ".png";   // 600+ 字节

            String result = PathGuard.sanitizeFileName(longChinese);

            assertThat(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .as("中文一个字 3 字节 —— 按字符截会超过文件系统的限制")
                    .isLessThanOrEqualTo(255);
            assertThat(result)
                    .as("不能把一个多字节字符切成两半")
                    .doesNotContain("\ufffd");
        }
    }

    // ================= 随机名 =================

    @Nested
    @DisplayName("随机文件名")
    class HashNames {

        @Test
        @DisplayName("保留扩展名")
        void keepsExtension() {
            assertThat(PathGuard.hashName("png")).endsWith(".png");
            assertThat(PathGuard.hashName(".PNG")).endsWith(".png");
        }

        @Test
        @DisplayName("没有扩展名时只有随机部分")
        void withoutExtension() {
            assertThat(PathGuard.hashName(null)).doesNotContain(".");
            assertThat(PathGuard.hashName("")).doesNotContain(".");
        }

        @Test
        @DisplayName("★ 扩展名本身也被清理 —— 它来自用户")
        void extensionIsSanitized() {
            assertThat(PathGuard.hashName("png/../../etc"))
                    .as("扩展名是从客户端的文件名里切出来的,同样不可信")
                    .doesNotContain("/")
                    .doesNotContain("..");
            assertThat(PathGuard.hashName("p n g")).endsWith(".png");
        }

        @Test
        @DisplayName("每次都不同")
        void isRandom() {
            var names = new java.util.HashSet<String>();
            for (int i = 0; i < 100; i++) names.add(PathGuard.hashName("png"));

            assertThat(names)
                    .as("重复的名字意味着覆盖 —— 两个用户的头像会互相覆盖")
                    .hasSize(100);
        }
    }

    // ================= public 磁盘 =================

    @Nested
    @DisplayName("public 磁盘的可执行类型")
    class PublicSafety {

        @ParameterizedTest
        @ValueSource(strings = {"a.php", "a.phtml", "a.jsp", "a.asp", "a.cgi",
                "a.sh", "a.exe", "a.jar", "a.class", "dir/b.php5"})
        @DisplayName("★ 可执行类型被拒 —— 那意味着远程代码执行")
        void rejectsExecutables(String path) {
            assertThatThrownBy(() -> PathGuard.requireSafeForPublic(path, Set.of()))
                    .isInstanceOf(PathGuard.UnsafePathException.class)
                    .hasMessageContaining("远程代码执行");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a.html", "a.htm", "a.svg", "a.xhtml"})
        @DisplayName("★ html / svg 被拒 —— 那是存储型 XSS")
        void rejectsMarkup(String path) {
            assertThatThrownBy(() -> PathGuard.requireSafeForPublic(path, Set.of()))
                    .hasMessageContaining("存储型 XSS")
                    .hasMessageContaining("不同的域名");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a.png", "a.jpg", "a.pdf", "a.zip", "a.mp4", "a"})
        @DisplayName("无害的类型通过")
        void allowsSafeTypes(String path) {
            assertThatCode(() -> PathGuard.requireSafeForPublic(path, Set.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("显式允许之后通过")
        void explicitAllowance() {
            assertThatCode(() -> PathGuard.requireSafeForPublic("a.svg",
                    Set.of("svg")))
                    .doesNotThrowAnyException();
        }
    }

    // ================= 扩展名 =================

    @ParameterizedTest
    @CsvSource({
            "a.png,png",
            "a.PNG,png",
            "dir/a.tar.gz,gz",
            "a,''",
            "a.,''",
            "dir.with.dots/file,''",
            "'',''"})
    @DisplayName("扩展名提取")
    void extensionExtraction(String path, String expected) {
        assertThat(PathGuard.extensionOf(path)).isEqualTo(expected);
    }
}
