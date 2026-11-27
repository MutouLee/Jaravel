package net.dobeen.jaravel.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * 手写二维码编码器的验收(docs/08 §6.4)。
 *
 * <h2>★ 这组测试的做法是「解回来比对」</h2>
 * <p>断言「生成了一个 25×25 的矩阵」证明不了任何事:一个掩码罚分算错、
 * 交织顺序差一个码字的编码器,照样产出形状完美的图 —— 而用户扫不出来。
 * 那种缺陷不会有人报告,只会被当成「这个二维码不好扫」。
 *
 * <p>所以这里用 zxing(**test 作用域**,见 pom 里那段注释)把生成的
 * 矩阵解码,比对原文。编码器任何一步错了,解码就会失败或得到别的字符串。
 *
 * <p>覆盖的长度跨了多个版本与块结构:版本 1(1 块)、版本 4(2 块)、
 * 版本 6(4 块)、版本 7+(带版本信息)—— 那几处正是容易写错的地方。
 */
class QrCodeTest {

    /** 把我们的矩阵喂给 zxing 解码 */
    private String decode(boolean[][] modules) throws Exception {
        int size = modules.length;
        var bits = new BitMatrix(size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) bits.set(x, y);
            }
        }
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BitMatrixSource(bits)));
        return new QRCodeReader()
                .decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE))
                .getText();
    }

    @Test
    @DisplayName("★ 真实的 otpauth URI 解回来与原文一致")
    void realOtpauthUriRoundTrips() throws Exception {
        String secret = Totp.generateSecret();
        String uri = Totp.otpauthUri("Jaravel", "tom@example.com", secret);

        assertThat(decode(QrCode.matrix(uri)))
                .as("这条就是这个编码器存在的全部理由:验证器要扫得出来")
                .isEqualTo(uri);
    }

    @Test
    @DisplayName("短内容(版本 1,单块)")
    void version1() throws Exception {
        assertThat(decode(QrCode.matrix("HELLO"))).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("★ 跨版本与块结构:1 块 / 2 块 / 4 块 / 带版本信息")
    void acrossVersionsAndBlockLayouts() throws Exception {
        // 长度挑在各版本容量附近 —— 分块与交织的写法就在这些边界上出错
        for (int length : new int[] {1, 14, 26, 60, 100, 140, 180, 213}) {
            String payload = "A".repeat(length);

            assertThat(decode(QrCode.matrix(payload)))
                    .as("长度 %d 的内容解不回来 —— 分块/交织/版本信息里有一处错", length)
                    .isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("UTF-8 内容也行(账号名可能是中文)")
    void utf8Content() throws Exception {
        String uri = Totp.otpauthUri("记账本", "张三@example.com", Totp.generateSecret());

        assertThat(decode(QrCode.matrix(uri))).isEqualTo(uri);
    }

    @Test
    @DisplayName("★ 超出范围时报错,并说清这个编码器的定位")
    void tooLongFailsLoudly() {
        assertThatThrownBy(() -> QrCode.matrix("A".repeat(400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容太长")
                .hasMessageContaining("otpauth");
    }

    @Test
    @DisplayName("SVG:尺寸自洽,静默区留够 4 个模块")
    void svgGeometry() {
        String uri = Totp.otpauthUri("Jaravel", "tom@example.com", Totp.generateSecret());
        int size = QrCode.matrix(uri).length;

        String svg = QrCode.svg(uri);        // 默认 4px / 4 模块静默区
        int expected = (size + 8) * 4;

        assertThat(svg)
                .contains("width=\"" + expected + "\"")
                .contains("viewBox=\"0 0 " + expected + " " + expected + "\"")
                .as("一个模块一个 <rect> 会让内联进 HTML 的 SVG 大十倍")
                .doesNotContain("<rect x=")
                .contains("<path fill=\"#000\"");
    }

    @Test
    @DisplayName("静默区可调,但它是规范要求的 —— 少了有的扫描器找不到定位图案")
    void quietZoneIsConfigurable() {
        String svg = QrCode.svg("HELLO", 2, 0);
        int size = QrCode.matrix("HELLO").length;

        assertThat(svg).contains("width=\"" + size * 2 + "\"");
    }

    /** zxing 的 LuminanceSource 适配:纯矩阵,不经过图片 */
    private static final class BitMatrixSource extends com.google.zxing.LuminanceSource {

        private final BitMatrix bits;

        BitMatrixSource(BitMatrix bits) {
            super(bits.getWidth(), bits.getHeight());
            this.bits = bits;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            for (int x = 0; x < getWidth(); x++) {
                row[x] = (byte) (bits.get(x, y) ? 0 : 0xFF);
            }
            return row;
        }

        @Override
        public byte[] getMatrix() {
            var matrix = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                for (int x = 0; x < getWidth(); x++) {
                    matrix[y * getWidth() + x] = (byte) (bits.get(x, y) ? 0 : 0xFF);
                }
            }
            return matrix;
        }
    }

    /** 解码失败时 zxing 抛的是 NotFoundException —— 让它原样冒出来,消息比包装后清楚 */
    @Test
    @DisplayName("哨兵:一个全白矩阵解不出来(证明上面的解码不是假通过)")
    void decodingBlankFails() {
        assertThatThrownBy(() -> decode(new boolean[25][25]))
                .as("如果这条也『通过』,说明 decode 根本没在校验内容")
                .isInstanceOfAny(NotFoundException.class, com.google.zxing.FormatException.class,
                        com.google.zxing.ChecksumException.class);
    }
}
