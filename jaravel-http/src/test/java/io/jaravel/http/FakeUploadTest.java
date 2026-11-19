package io.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.support.io.ContentSniffer;

/**
 * {@code UploadedFile.fake()}(docs/14 §4)。
 *
 * <p>这些断言的重点只有一个:**假文件的内容必须是真的**。
 * 本框架的扩展名与 MIME 一律按内容嗅探,所以一个嗅探不出来的
 * 假文件会让 {@code image} / {@code mimes:png} 在测试里与生产里
 * 行为不同 —— 那是一个假实现最坏的形态。
 */
class FakeUploadTest {

    @Test
    @DisplayName("★ 假图片是真能解码的图片,宽高就是要的那个")
    void imageIsARealDecodableImage() throws Exception {
        UploadedFile file = UploadedFile.fake().image("avatar.png", 120, 80);

        assertThat(file.extension()).isEqualTo("png");
        assertThat(file.mimeType()).contains("image/png");
        assertThat(file.isImage()).isTrue();

        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(file.content()));
        assertThat(decoded).as("ImageIO 读不出来的「图片」是假的").isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(120);
        assertThat(decoded.getHeight()).isEqualTo(80);
    }

    @Test
    @DisplayName("格式跟着文件名的扩展名走")
    void formatFollowsTheFileName() {
        assertThat(UploadedFile.fake().image("a.jpg").extension()).isEqualTo("jpg");
        assertThat(UploadedFile.fake().image("a.gif").extension()).isEqualTo("gif");
        assertThat(UploadedFile.fake().image("a.bmp").extension()).isEqualTo("bmp");
        // 认不出的扩展名回到 png —— 但**嗅探结果**仍然如实是 png,
        // 不会假装自己是 .foo
        assertThat(UploadedFile.fake().image("a.foo").sniffedExtension()).contains("png");
    }

    @Test
    @DisplayName("★ create 的大小按 KB,与校验规则的单位一致")
    void createSizeIsInKilobytes() {
        UploadedFile file = UploadedFile.fake().create("report.pdf", 512);

        assertThat(file.size()).isEqualTo(512 * 1024);
        assertThat(file.extension())
                .as("魔数必须让它被嗅探成 pdf,否则 mimes:pdf 在测试里过不去")
                .isEqualTo("pdf");
        assertThat(file.mimeType()).contains("application/pdf");
    }

    @Test
    @DisplayName("0 KB 时至少保留魔数 —— 否则它连类型都没有")
    void zeroKilobytesStillKeepsTheMagic() {
        UploadedFile file = UploadedFile.fake().create("a.png", 0);

        assertThat(file.extension()).isEqualTo("png");
        assertThat(file.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("★ 没有魔数的类型如实嗅探不出来 —— 不伪造")
    void typesWithoutMagicAreHonest() {
        UploadedFile file = UploadedFile.fake().create("notes.txt", 1);

        assertThat(file.sniffedExtension())
                .as("txt 没有魔数,生产里也嗅探不出来 —— 这里伪造一个会让 mimes:txt 的"
                  + "行为在测试与生产之间分叉")
                .isEmpty();
        assertThat(file.extension())
                .as("嗅探不出来才退回客户端给的扩展名")
                .isEqualTo("txt");
        assertThat(file.mimeType()).isEmpty();
    }

    @Test
    @DisplayName("withContent 用来构造坏输入:截断的 PNG 不是图片")
    void withContentBuildsBadInput() {
        byte[] truncated = {(byte) 0x89, 'P'};
        UploadedFile file = UploadedFile.fake().withContent("avatar.png", truncated);

        assertThat(file.isImage()).isFalse();
        assertThat(file.sniffedExtension()).isEmpty();
        assertThat(file.clientExtension())
                .as("客户端声称的扩展名仍然是 png —— 这正是不可信的那个")
                .isEqualTo("png");
    }

    @Test
    @DisplayName("空文件是一次成功的上传,由校验规则决定要不要拒")
    void emptyFileIsAllowed() {
        UploadedFile file = UploadedFile.fake().withContent("empty.txt", new byte[0]);

        assertThat(file.isEmpty()).isTrue();
        assertThat(file.size()).isZero();
    }

    @Test
    @DisplayName("字段名可指定 —— request.file(\"avatar\") 里的那个 key")
    void fieldNameCanBeGiven() {
        assertThat(UploadedFile.fake().image("avatar", "a.png", 4, 4).fieldName())
                .isEqualTo("avatar");
        assertThat(UploadedFile.fake().create("avatar", "a.pdf", 1).fieldName())
                .isEqualTo("avatar");
        assertThat(UploadedFile.fake()
                        .withContent("avatar", "a.txt", "x".getBytes(StandardCharsets.UTF_8))
                        .fieldName())
                .isEqualTo("avatar");
    }

    @Test
    @DisplayName("非法尺寸立刻报错,而不是生成一个 0×0 的东西")
    void illegalSizesFailFast() {
        assertThatThrownBy(() -> UploadedFile.fake().image("a.png", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少 1 像素");
        assertThatThrownBy(() -> UploadedFile.fake().create("a.png", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能是负数");
    }

    @Test
    @DisplayName("★ 魔数表与 ContentSniffer 对得上 —— 对不上这个 fake 就在骗人")
    void magicNumbersAgreeWithTheSniffer() {
        // 那边加一个格式、这边忘了加时,这条会红
        for (String extension : java.util.List.of("png", "jpg", "gif", "bmp",
                                                  "pdf", "zip", "gz", "mp3", "ogg")) {
            UploadedFile file = UploadedFile.fake().create("a." + extension, 1);

            assertThat(ContentSniffer.extensionOf(file.content()))
                    .as("fake 生成的 .%s 必须被嗅探成 %s", extension, extension)
                    .contains(extension);
        }
    }
}
