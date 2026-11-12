package io.jaravel.auth.twofactor;

/**
 * RFC 4648 Base32 —— TOTP 密钥与恢复码的文本形式。
 *
 * <p>为什么不直接用 Base64:TOTP 的 {@code otpauth://} URI、Google
 * Authenticator 与其他客户端都按 Base32 读密钥。Base64 里的 {@code +}
 * 和 {@code /} 还会在 URL/二维码里制造转义问题。
 *
 * <p>编码**不带 {@code =} 填充**。解码接受填充、空白与大小写 ——
 * 用户手动输入密钥时这几种都常见。
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";

        var out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;

        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;

            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) throw new IllegalArgumentException("Base32 文本不能为 null");

        var out = new java.io.ByteArrayOutputStream(text.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;

        for (char raw : text.toCharArray()) {
            if (raw == '=' || Character.isWhitespace(raw)) continue;

            char c = Character.toUpperCase(raw);
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符:" + raw);
            }

            buffer = (buffer << 5) | value;
            bits += 5;

            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
