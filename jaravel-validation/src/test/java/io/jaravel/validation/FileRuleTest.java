package io.jaravel.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.support.io.Upload;

/** file / image / mimes / mimetypes / extensions,以及 min/max 对文件的 KB 语义 */
class FileRuleTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04, 1, 2, 3, 4};
    private static final byte[] PHP =
            "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8);

    private static Upload upload(byte[] content, String name) {
        return Upload.of(content, name);
    }

    private static Upload sized(int bytes, String name) {
        return Upload.of(new byte[bytes], name);
    }

    private static boolean passes(Object value, String rules) {
        return Validator.make(Map.of("f", value), Map.of("f", rules)).passes();
    }

    private static String messageFor(Object value, String rules) {
        var validator = Validator.make(Map.of("f", value), Map.of("f", rules));
        validator.passes();
        return validator.errors().first("f");
    }

    @Nested
    @DisplayName("file")
    class FileRule {

        @Test
        @DisplayName("是上传的文件才通过")
        void onlyUploads() {
            assertThat(passes(upload(PNG, "a.png"), "file")).isTrue();
            assertThat(passes("a.png", "file"))
                    .as("一个字符串不是文件 —— 这是防止「前端改成普通字段提交」")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 0 字节的文件也过 file —— 要拒就加 min:1")
        void emptyFilePassesFileRule() {
            assertThat(passes(sized(0, "empty.png"), "file"))
                    .as("空文件是一次成功的上传。file 只回答「是不是文件」")
                    .isTrue();
            assertThat(passes(sized(0, "empty.png"), "file|min:1")).isFalse();
        }
    }

    @Nested
    @DisplayName("image")
    class ImageRule {

        @Test
        @DisplayName("★ 伪装成 .png 的脚本过不了 image")
        void disguisedScriptRejected() {
            assertThat(passes(upload(PHP, "avatar.png"), "image"))
                    .as("这是这条规则存在的全部意义:名字是 .png,内容不是图片")
                    .isFalse();
        }

        @Test
        @DisplayName("真的 PNG 过,即便名字是 .bin")
        void realImagePasses() {
            assertThat(passes(upload(PNG, "whatever.bin"), "image")).isTrue();
        }

        @Test
        @DisplayName("★ SVG 不算图片")
        void svgIsNotAnImage() {
            byte[] svg = "<svg xmlns=\"...\"><script>alert(1)</script></svg>"
                    .getBytes(StandardCharsets.UTF_8);

            assertThat(passes(upload(svg, "logo.svg"), "image"))
                    .as("SVG 可以带 <script> —— 放过它等于一个存储型 XSS。"
                            + "Jaravel 不提供 allow_svg,因为它没有 SVG 消毒器")
                    .isFalse();
        }

        @Test
        @DisplayName("PDF 不是图片")
        void pdfIsNotAnImage() {
            assertThat(passes(upload(PDF, "doc.pdf"), "image")).isFalse();
            assertThat(passes(upload(PDF, "doc.pdf"), "file")).isTrue();
        }
    }

    @Nested
    @DisplayName("mimes / mimetypes")
    class MimeRules {

        @Test
        @DisplayName("按内容匹配")
        void matchesByContent() {
            assertThat(passes(upload(PNG, "a.png"), "mimes:png,jpg")).isTrue();
            assertThat(passes(upload(PNG, "a.pdf"), "mimes:png,jpg"))
                    .as("名字说 pdf,内容是 png —— 白名单看内容")
                    .isTrue();
            assertThat(passes(upload(PDF, "a.png"), "mimes:png,jpg"))
                    .as("反过来也成立:名字说 png,内容是 pdf → 拒")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 嗅探不出来的类型一律拒 —— 所以 mimes:txt 永不通过")
        void unsniffableIsRejected() {
            var txt = upload("hello".getBytes(StandardCharsets.UTF_8), "a.txt");

            assertThat(passes(txt, "mimes:txt"))
                    .as("纯文本没有魔数。一个「认不出就放过」的白名单等于没有白名单 —— "
                            + "代价是文本类文件要用 extensions 规则")
                    .isFalse();
            assertThat(passes(txt, "extensions:txt")).isTrue();
        }

        @Test
        @DisplayName("docx 是 zip —— 别名要生效,否则是一个会被投诉的误报")
        void zipAliases() {
            assertThat(passes(upload(ZIP, "report.docx"), "mimes:docx")).isTrue();
            assertThat(passes(upload(ZIP, "x.zip"), "mimes:zip")).isTrue();
            assertThat(passes(upload(PNG, "x.docx"), "mimes:docx"))
                    .as("内容是 png 的「docx」仍然要拒")
                    .isFalse();
        }

        @Test
        @DisplayName("jpg / jpeg 互通")
        void jpegAlias() {
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
            assertThat(passes(upload(jpeg, "a.jpeg"), "mimes:jpeg")).isTrue();
            assertThat(passes(upload(jpeg, "a.jpg"), "mimes:jpg")).isTrue();
        }

        @Test
        @DisplayName("mimetypes 按 MIME 写,支持 image/*")
        void mimetypes() {
            assertThat(passes(upload(PNG, "a.png"), "mimetypes:image/png")).isTrue();
            assertThat(passes(upload(PNG, "a.png"), "mimetypes:image/*")).isTrue();
            assertThat(passes(upload(PDF, "a.pdf"), "mimetypes:image/*")).isFalse();
            assertThat(passes(upload(PDF, "a.pdf"), "mimetypes:application/pdf")).isTrue();
        }

        @Test
        @DisplayName("空的 mimes 立刻报错,而不是拒掉所有文件")
        void emptyWhitelistIsAnError() {
            assertThatThrownBy(() -> passes(upload(PNG, "a.png"), "mimes:"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少一个扩展名");
        }

        @Test
        @DisplayName("前导点与大写都能写")
        void toleratesDotsAndCase() {
            assertThat(passes(upload(PNG, "a.png"), "mimes:.PNG")).isTrue();
        }
    }

    @Nested
    @DisplayName("extensions")
    class ExtensionsRule {

        @Test
        @DisplayName("按文件名 —— 并且**不是**安全检查")
        void byFileName() {
            assertThat(passes(upload(PHP, "evil.csv"), "extensions:csv"))
                    .as("内容是 PHP 但名字是 .csv —— 这条规则会放过它。"
                            + "它的用途是「只接受 .csv」这种需求,安全靠别的规则")
                    .isTrue();
            assertThat(passes(upload(PNG, "a.png"), "extensions:csv")).isFalse();
        }

        @Test
        @DisplayName("没有扩展名就不通过")
        void noExtension() {
            assertThat(passes(upload(PNG, "README"), "extensions:png")).isFalse();
        }
    }

    @Nested
    @DisplayName("★ min / max / between / size 对文件按 KB")
    class Kilobytes {

        @Test
        @DisplayName("max:2 = 不超过 2 KB")
        void maxIsKilobytes() {
            assertThat(passes(sized(2048, "a.png"), "max:2"))
                    .as("2048 字节 = 恰好 2 KB")
                    .isTrue();
            assertThat(passes(sized(2049, "a.png"), "max:2")).isFalse();
        }

        @Test
        @DisplayName("★ 单位是 KB 而不是字节 —— 弄错会静默放宽 1024 倍")
        void unitMatters() {
            assertThat(passes(sized(1500, "a.png"), "max:1024"))
                    .as("max:1024 的意思是 1 MB。如果按字节解释,"
                            + "1500 字节会被拒 —— 而真正的危险是反过来:"
                            + "一个 2 MB 的文件在 max:2048 下通过")
                    .isTrue();
        }

        @Test
        @DisplayName("1 字节的文件不会被当成 0 KB")
        void tinyFileIsNotZero() {
            assertThat(passes(sized(1, "a.png"), "min:1"))
                    .as("向下取整会让 1 字节变成 0 KB,于是 min:1 拒掉它")
                    .isFalse();
            assertThat(passes(sized(1, "a.png"), "max:1")).isTrue();
        }

        @Test
        @DisplayName("between / size")
        void betweenAndSize() {
            assertThat(passes(sized(1536, "a.png"), "between:1,2")).isTrue();
            assertThat(passes(sized(1024, "a.png"), "size:1")).isTrue();
            assertThat(passes(sized(1025, "a.png"), "size:1")).isFalse();
        }

        @Test
        @DisplayName("★ 消息用 .file 的措辞,不是「字符数」")
        void messageUsesFileWording() {
            String message = messageFor(sized(4096, "a.png"), "max:2");

            assertThat(message)
                    .as("落到 string 分支会说成「不能超过 2 个字符」—— "
                            + "那个消息会让人以为是文件名太长")
                    .contains("KB");
        }
    }

    @Nested
    @DisplayName("组合起来:一个真实的头像规则")
    class RealWorld {

        private static final String AVATAR = "required|image|mimes:jpg,png|max:2048";

        @Test
        @DisplayName("正常的 PNG 通过")
        void validAvatar() {
            assertThat(passes(upload(PNG, "me.png"), AVATAR)).isTrue();
        }

        @Test
        @DisplayName("伪装的脚本被拒")
        void disguisedRejected() {
            assertThat(passes(upload(PHP, "me.png"), AVATAR)).isFalse();
        }

        @Test
        @DisplayName("太大的被拒")
        void tooLargeRejected() {
            assertThat(passes(sized(3 * 1024 * 1024, "me.png"), AVATAR)).isFalse();
        }

        @Test
        @DisplayName("没传被拒,且消息是 required 的那条")
        void missingRejected() {
            var validator = Validator.make(Map.of(), Map.of("f", AVATAR));

            assertThat(validator.passes()).isFalse();
            assertThat(validator.errors().first("f"))
                    .as("required 应该短路 —— 不该同时报「必须是图片」")
                    .doesNotContain("图片");
        }
    }
}
