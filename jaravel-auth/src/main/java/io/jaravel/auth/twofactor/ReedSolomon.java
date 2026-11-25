package io.jaravel.auth.twofactor;

/**
 * 二维码用的 Reed–Solomon 纠错码(GF(256),本原多项式 0x11D)。
 *
 * <p>只做编码 —— 解码是扫描器的事。几十行,因为它就是「按生成多项式做一次
 * 多项式除法,取余数」。
 *
 * <p>之所以不引库:这段代码的正确性由 {@code QrCodeTest} 里的解码比对
 * 覆盖(zxing 解不出来就说明这里错了),而引一个库会为十几行逻辑
 * 增加一条运行时依赖。
 */
final class ReedSolomon {

    private ReedSolomon() {}

    /** GF(256) 的指数表与对数表 —— 乘法靠查表,比每次做多项式乘法快得多 */
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            // 0x11D = x^8 + x^4 + x^3 + x^2 + 1,QR 规范指定的本原多项式
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int multiply(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    /**
     * @param data        数据码字
     * @param ecCodewords 要几个纠错码字
     * @return 纠错码字(长度 = ecCodewords)
     */
    static byte[] encode(byte[] data, int ecCodewords) {
        int[] generator = generator(ecCodewords);
        var remainder = new int[ecCodewords];

        for (byte datum : data) {
            int factor = (datum & 0xFF) ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, ecCodewords - 1);
            remainder[ecCodewords - 1] = 0;

            for (int i = 0; i < ecCodewords; i++) {
                remainder[i] ^= multiply(generator[i], factor);
            }
        }

        var out = new byte[ecCodewords];
        for (int i = 0; i < ecCodewords; i++) out[i] = (byte) remainder[i];
        return out;
    }

    /** 生成多项式 {@code (x - α^0)(x - α^1)…},返回不含最高次项的系数 */
    private static int[] generator(int degree) {
        var coefficients = new int[degree];
        coefficients[degree - 1] = 1;

        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                coefficients[j] = multiply(coefficients[j], root);
                if (j + 1 < degree) coefficients[j] ^= coefficients[j + 1];
            }
            root = multiply(root, 2);
        }
        return coefficients;
    }
}
