package net.dobeen.jaravel.http;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * 造一个假的上传文件(docs/14 §4)。
 *
 * <pre>{@code
 * var avatar = UploadedFile.fake().image("avatar.png");
 * var big    = UploadedFile.fake().image("avatar.jpg", 1200, 800);
 * var pdf    = UploadedFile.fake().create("report.pdf", 512);   // 512 KB
 * var raw    = UploadedFile.fake().withContent("notes.txt", "hello".getBytes(UTF_8));
 * }</pre>
 *
 * <h2>★ 内容必须是真的,否则这个 fake 在骗人</h2>
 * <p>本框架的扩展名与 MIME 一律**按内容嗅探**(docs/11 §3.3:
 * 客户端给的文件名完全不可信)。所以一个「名字叫 avatar.png、
 * 内容是随机字节」的假文件会让 {@code image} 与 {@code mimes:png}
 * 这两条规则**在测试里失败、在生产里通过**,或者反过来 ——
 * 一个假实现最坏的形态就是让测试与生产在同一个输入上行为不同。
 *
 * <p>所以:
 * <ul>
 *   <li>{@link #image} 用 {@code ImageIO} 编出一张**真的**图片
 *       (真的宽高、真的魔数),于是嗅探、{@code image} 规则、
 *       以及应用自己的缩略图代码都按真实路径走;</li>
 *   <li>{@link #create} 按文件名的扩展名写上**对应格式的魔数**,
 *       再填充到指定 KB;</li>
 *   <li>认不出来的扩展名(txt / csv / json 这类没有魔数的)
 *       如实生成普通字节 —— 它们的嗅探结果就是「认不出」,
 *       而那在生产里同样是「认不出」。{@code mimes:txt} 对它**不通过**,
 *       这与 {@code Rules.mimes} 的注释里写的代价是同一件事。</li>
 * </ul>
 *
 * <h2>为什么用 ImageIO</h2>
 * <p>手写 PNG 需要自己算 zlib 与 CRC —— 那是一段可能写错的二进制代码,
 * 而写错的后果是「假文件嗅探不出来」,也就是上面那个最坏形态。
 * ImageIO 在 JDK 里({@code java.desktop}),不引入任何依赖。
 *
 * <p>代价:它在 GraalVM native 下需要额外配置。这不影响任何人 ——
 * Web 应用的 native 本来就不做(ADR-019),而这个类只在测试里被调。
 */
public final class FakeUpload {

    /** 默认尺寸 —— 够小(几百字节)又能被所有图片库正常解码 */
    private static final int DEFAULT_SIZE = 10;

    FakeUpload() {
    }

    // ================= 图片 =================

    /** 一张 10×10 的图片,格式按文件名的扩展名(png / jpg / gif / bmp) */
    public UploadedFile image(String name) {
        return image(name, DEFAULT_SIZE, DEFAULT_SIZE);
    }

    /**
     * 指定宽高的真图片。
     *
     * @param name   文件名,扩展名决定编码格式(认不出时用 png)
     * @param width  像素宽
     * @param height 像素高
     */
    public UploadedFile image(String name, int width, int height) {
        return image("file", name, width, height);
    }

    /** 指定表单字段名的版本 —— {@code request.file("avatar")} 里的那个 key */
    public UploadedFile image(String fieldName, String name, int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException(
                    "图片的宽高至少 1 像素,当前 %d×%d".formatted(width, height));
        }

        String format = imageFormatOf(name);
        // ★ jpg 与 bmp 的编码器**不支持 alpha**:给它们一个 ARGB 的
        // BufferedImage,jpg 会得到颜色错乱的图,bmp 直接写不出来
        // (ImageIO.write 返回 false)。而那两种失败看起来都像
        // 「ImageIO 坏了」,查起来与图片内容毫无关系。
        boolean alpha = !"jpg".equals(format) && !"bmp".equals(format);
        var image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        // 不是纯黑:一张全 0 的图在某些编码器下会被压到极小,
        // 而「几十字节的图片」会让按 KB 的大小断言很难写
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 37 + y * 17) & 0xFFFFFF));
            }
        }

        var out = new ByteArrayOutputStream();
        try {
            if (!javax.imageio.ImageIO.write(image, format, out)) {
                throw new IllegalStateException("""
                        这个 JDK 的 ImageIO 不支持写 %s。
                          换 png / jpg,或者用 withContent(...) 自己给字节。"""
                        .formatted(format));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] content = out.toByteArray();
        // clientMimeType 是「客户端声称的类型」。这里填**嗅探出来的**那个:
        // 一个正常浏览器声称的就是真实类型,而要测「声称与内容不符」
        // 的那种输入,用 withContent(...) 自己构造更清楚
        return new UploadedFile(fieldName, name,
                net.dobeen.jaravel.support.io.ContentSniffer.detect(content).orElse(null),
                content);
    }

    // ================= 任意类型 =================

    /**
     * 指定大小的文件,内容是「对应格式的魔数 + 填充」。
     *
     * @param name      文件名,扩展名决定魔数
     * @param kilobytes 大小,**KB** —— 与校验规则的单位一致
     *                  ({@code max:2048} 是 2 MB)
     */
    public UploadedFile create(String name, long kilobytes) {
        return create("file", name, kilobytes);
    }

    public UploadedFile create(String fieldName, String name, long kilobytes) {
        if (kilobytes < 0) {
            throw new IllegalArgumentException("大小不能是负数:" + kilobytes);
        }

        byte[] magic = magicFor(extensionOf(name));
        int total = (int) Math.max(kilobytes * 1024, magic.length);
        byte[] content = new byte[total];
        System.arraycopy(magic, 0, content, 0, magic.length);
        // 填充不是 0:全 0 的「zip」在某些工具里会被判成损坏,
        // 而我们要的是「能被嗅探认出、且大小确定」
        Arrays.fill(content, magic.length, total, (byte) 'J');

        return new UploadedFile(fieldName, name, null, content);
    }

    /** 自己给字节 —— 要测一个具体的坏输入(截断的 PNG、空文件)时用 */
    public UploadedFile withContent(String name, byte[] content) {
        return new UploadedFile("file", name, null, content);
    }

    public UploadedFile withContent(String fieldName, String name, byte[] content) {
        return new UploadedFile(fieldName, name, null, content);
    }

    // ================= 内部 =================

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? ""
                : name.substring(dot + 1).toLowerCase();
    }

    /** ImageIO 的格式名。认不出的扩展名用 png —— 它最通用 */
    private static String imageFormatOf(String name) {
        return switch (extensionOf(name)) {
            case "jpg", "jpeg" -> "jpg";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            default -> "png";
        };
    }

    /**
     * 各格式的魔数。
     *
     * <p>与 {@code ContentSniffer} 认的那些**必须一致** ——
     * 不一致的话假文件嗅探不出来,而那正是这个类要避免的事。
     * 那边加一个格式时这边也要加(一条测试核对这件事)。
     */
    private static byte[] magicFor(String extension) {
        return switch (extension) {
            case "png" -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case "jpg", "jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
            case "gif" -> new byte[]{'G', 'I', 'F', '8'};
            case "bmp" -> new byte[]{'B', 'M'};
            case "pdf" -> new byte[]{'%', 'P', 'D', 'F', '-'};
            case "zip", "docx", "xlsx", "pptx" ->
                    new byte[]{'P', 'K', 0x03, 0x04};
            case "gz" -> new byte[]{0x1F, (byte) 0x8B};
            case "mp3" -> new byte[]{'I', 'D', '3'};
            case "ogg" -> new byte[]{'O', 'g', 'g', 'S'};
            // 没有魔数的类型(txt / csv / json …):如实给普通字节。
            // 伪造一个魔数会让 mimes:txt 在测试里通过而在生产里不通过
            default -> new byte[0];
        };
    }
}
