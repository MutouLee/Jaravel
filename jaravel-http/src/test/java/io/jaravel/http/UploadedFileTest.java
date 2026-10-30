package io.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** request.file(...) 与 UploadedFile 的语义 */
class UploadedFileTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private static UploadedFile png(String field, String name) {
        return new UploadedFile(field, name, "image/png", PNG);
    }

    @Nested
    @DisplayName("Request 上的三个入口")
    class RequestAccessors {

        @Test
        @DisplayName("file / files / hasFile")
        void accessors() {
            var request = BasicRequest.of("POST", "/avatar")
                    .file(png("avatar", "me.png"));

            assertThat(request.hasFile("avatar")).isTrue();
            assertThat(request.file("avatar")).isPresent();
            assertThat(request.files("avatar")).hasSize(1);
        }

        @Test
        @DisplayName("★ 没有文件时一律是空,不是 null")
        void missingFileIsEmpty() {
            var request = BasicRequest.of("GET", "/");

            assertThat(request.allFiles())
                    .as("一个不是 multipart 的请求的自然答案是「没有文件」")
                    .isEmpty();
            assertThat(request.file("avatar")).isEmpty();
            assertThat(request.files("avatar")).isEmpty();
            assertThat(request.hasFile("avatar")).isFalse();
        }

        @Test
        @DisplayName("同名字段的多个文件都在 files(...) 里,file(...) 给第一个")
        void multipleFiles() {
            var request = BasicRequest.of("POST", "/photos")
                    .file(png("photos", "a.png"))
                    .file(png("photos", "b.png"));

            assertThat(request.files("photos")).hasSize(2);
            assertThat(request.file("photos")).get()
                    .extracting(UploadedFile::originalName).isEqualTo("a.png");
        }

        @Test
        @DisplayName("★ 0 字节的文件算「有」")
        void emptyFileStillCounts() {
            var request = BasicRequest.of("POST", "/avatar")
                    .file("avatar", "empty.png", new byte[0]);

            assertThat(request.hasFile("avatar"))
                    .as("浏览器在用户选了个空文件时就会发来这样的 part —— "
                            + "hasFile 如实回答「请求里有这个 part」,"
                            + "「空文件算不算有效」交给校验")
                    .isTrue();
            assertThat(request.file("avatar").orElseThrow().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("★ 文件也出现在 all() 里 —— 否则文件校验永远失败")
        void filesAreInAll() {
            var request = BasicRequest.of("POST", "/avatar")
                    .bodyParam("name", "小明")
                    .file(png("avatar", "me.png"));

            assertThat(request.all())
                    .as("校验的输入就是 all() —— 文件不在里面的话 "
                            + "\"avatar\", \"required|image\" 拿到的是 null")
                    .containsKey("avatar")
                    .containsEntry("name", "小明");
            assertThat(request.all().get("avatar")).isInstanceOf(UploadedFile.class);
        }

        @Test
        @DisplayName("同名的多个文件在 all() 里是一个列表")
        void multipleFilesInAllAreAList() {
            var request = BasicRequest.of("POST", "/photos")
                    .file(png("photos", "a.png"))
                    .file(png("photos", "b.png"));

            assertThat(request.all().get("photos")).isInstanceOf(java.util.List.class);
        }
    }

    @Nested
    @DisplayName("类型判断按内容而不按文件名")
    class TypeDetection {

        @Test
        @DisplayName("★ 伪装成 .png 的 PHP 脚本:extension() 不会说它是 png")
        void disguisedScript() {
            var file = new UploadedFile("avatar", "avatar.png", "image/png",
                    "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8));

            assertThat(file.clientExtension())
                    .as("客户端声称的那个 —— 它是 png")
                    .isEqualTo("png");
            assertThat(file.extension())
                    .as("按内容:PHP 源码嗅探不出来,于是退回客户端给的名字。"
                            + "⚠ 所以 extension() **不是**一道安全边界 —— "
                            + "拦住它的是 image / mimes 规则")
                    .isEqualTo("png");
            assertThat(file.isImage())
                    .as("这才是那道边界:内容不是图片")
                    .isFalse();
            assertThat(file.mimeType()).isEmpty();
        }

        @Test
        @DisplayName("真的 PNG:嗅探出 png 与 image/png")
        void realPng() {
            var file = png("avatar", "whatever.bin");

            assertThat(file.extension())
                    .as("名字说 .bin,内容说 png —— 内容赢")
                    .isEqualTo("png");
            assertThat(file.mimeType()).contains("image/png");
            assertThat(file.isImage()).isTrue();
        }

        @Test
        @DisplayName("客户端声称的 MIME 只是信息,不参与判断")
        void clientMimeIsJustInformational() {
            var file = new UploadedFile("f", "x.png", "image/png",
                    "not an image".getBytes(StandardCharsets.UTF_8));

            assertThat(file.clientMimeType()).contains("image/png");
            assertThat(file.mimeType()).isEmpty();
        }

        @Test
        @DisplayName("没有扩展名的文件名")
        void noExtension() {
            var file = new UploadedFile("f", "README", null,
                    "text".getBytes(StandardCharsets.UTF_8));

            assertThat(file.extension()).isEmpty();
            assertThat(file.clientExtension()).isEmpty();
        }
    }

    @Nested
    @DisplayName("细节")
    class Details {

        @Test
        @DisplayName("★ content() 返回副本 —— 改它不影响同一次请求里的另一次读取")
        void contentIsCopied() {
            var file = png("avatar", "me.png");

            byte[] first = file.content();
            first[0] = 0;

            assertThat(file.content()[0])
                    .as("两个取文件的地方拿到的是同一个 UploadedFile，"
                            + "一个改了数组不该让另一个嗅探失败")
                    .isEqualTo((byte) 0x89);
        }

        @Test
        @DisplayName("size() 是字节数")
        void size() {
            assertThat(png("avatar", "me.png").size()).isEqualTo(PNG.length);
        }

        @Test
        @DisplayName("null 的内容与名字不会变成 NPE")
        void nullsAreTolerated() {
            var file = new UploadedFile("f", null, null, null);

            assertThat(file.originalName()).isEmpty();
            assertThat(file.size()).isZero();
            assertThat(file.isEmpty()).isTrue();
            assertThat(file.clientMimeType()).isEmpty();
        }
    }
}
