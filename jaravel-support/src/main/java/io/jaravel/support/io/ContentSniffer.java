package io.jaravel.support.io;

import java.util.Optional;

/**
 * 按**内容**判断文件类型(docs/11 §3.3 的那条安全约束)。
 *
 * <p>设计文档的原话:
 *
 * <blockquote>
 * 扩展名从**内容嗅探**(Apache Tika)而非 {@code Content-Disposition}
 * 的 filename。
 * </blockquote>
 *
 * <h2>为什么这件事是安全相关的</h2>
 * <p>客户端给的文件名完全不可信。一个攻击者上传一个 PHP 脚本
 * 并把它命名为 {@code avatar.png} —— 如果框架信了那个名字,
 * 它就以 {@code .png} 存了下来。
 *
 * <p>那本身还不致命({@code .png} 不会被 PHP 解释器执行),
 * 但两件事会让它变致命:
 * <ul>
 *   <li>某些服务器配置会按**内容**而不是扩展名决定如何处理;</li>
 *   <li>一个后续的「重命名/转换」流程可能恢复出真实的扩展名。</li>
 * </ul>
 *
 * <p>更现实的危害是**存储型 XSS**:上传一个带 {@code <script>} 的
 * HTML 文件,命名为 {@code photo.jpg}。浏览器在某些情况下
 * (老 IE 的 MIME 嗅探、或者服务器返回了错的 Content-Type)
 * 会把它当 HTML 渲染。
 *
 * <h2>★ 不引 Apache Tika,手写常见类型的魔数</h2>
 * <p>这是一处与设计文档的偏差,理由:
 *
 * <p>Tika 是一个**很重**的依赖(核心包约 3 MB,加上它的解析器
 * 依赖树会到几十 MB),而它的能力(从 PDF 里抽文本、
 * 识别一千种格式)这里完全用不到 —— 这里只需要
 * 「这个字节流是不是它声称的那个类型」。
 *
 * <p>所以手写了常见上传类型的魔数检查:图片、PDF、zip 系
 * (含 Office 的 docx/xlsx)、常见音视频容器。约 40 行。
 *
 * <p>**代价必须说清**:
 * <ul>
 *   <li>覆盖不全 —— 一个不在列表里的类型会返回 empty,
 *       那时调用方要么拒绝、要么退回扩展名(由它决定);</li>
 *   <li>不做深度校验 —— 一个 PNG 的魔数对但内容损坏的文件
 *       这里会认为它是 PNG。那不是这个检查要解决的问题
 *       (那是图片库的事)。</li>
 * </ul>
 *
 * <p>需要完整覆盖的项目可以自己引 Tika:这个类的方法是纯函数,
 * 应用在校验层调自己的嗅探器即可 —— {@code Storage.putFile}
 * 用这里的结果只是为了决定**存储路径的扩展名**,
 * 而那一步对「认不出来」已经有退路。
 *
 * <h2>为什么在 jaravel-support 而不是存储模块里</h2>
 * <p>它被**两层**用到:上传那一层({@code UploadedFile.extension()})
 * 与存储那一层({@code Storage.putFile})。
 *
 * <p>原本放在 {@code jaravel-storage} 里,而那会让 HTTP 层为了算一个
 * 扩展名而依赖存储模块 —— 一个纯函数不该造成这种依赖。
 * 它只操作 {@code byte[]},没有任何依赖,所以放在 support 里最合适。
 */
public final class ContentSniffer {

    private ContentSniffer() {
    }

    /**
     * 按内容判断扩展名。
     *
     * @return 小写、不带点的扩展名;认不出时 empty
     */
    public static Optional<String> extensionOf(byte[] content) {
        if (content == null || content.length < 4) return Optional.empty();

        // ---- 图片 ----
        if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("png");
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("jpg");
        }
        if (startsWith(content, 'G', 'I', 'F', '8')) {
            return Optional.of("gif");
        }
        if (content.length >= 12 && startsWith(content, 'R', 'I', 'F', 'F')
                && matchesAt(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("webp");
        }
        if (startsWith(content, 'B', 'M')) {
            return Optional.of("bmp");
        }

        // ---- PDF ----
        if (startsWith(content, '%', 'P', 'D', 'F', '-')) {
            return Optional.of("pdf");
        }

        // ---- zip 系 ----
        //
        // docx / xlsx / pptx / jar / apk 都是 zip。魔数分不开它们 ——
        // 要分开得解压看里面的 [Content_Types].xml。
        //
        // 这里返回 "zip" 并把这件事说清:调用方要区分的话,
        // 可以在 zip 这个结果上**允许**扩展名是 docx/xlsx/pptx
        // (它们确实是 zip),而拒绝 .png 声称自己是 zip。
        if (startsWith(content, 'P', 'K', 0x03, 0x04)
                || startsWith(content, 'P', 'K', 0x05, 0x06)
                || startsWith(content, 'P', 'K', 0x07, 0x08)) {
            return Optional.of("zip");
        }

        // ---- 其它压缩 ----
        if (startsWith(content, 0x1F, 0x8B)) {
            return Optional.of("gz");
        }
        if (startsWith(content, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) {
            return Optional.of("7z");
        }
        if (startsWith(content, 'R', 'a', 'r', '!')) {
            return Optional.of("rar");
        }

        // ---- 音视频 ----
        if (content.length >= 12 && matchesAt(content, 4, 'f', 't', 'y', 'p')) {
            // mp4 / mov / m4a 共用 ftyp。看后面的 brand
            if (matchesAt(content, 8, 'q', 't', ' ', ' ')) return Optional.of("mov");
            return Optional.of("mp4");
        }
        if (startsWith(content, 'I', 'D', '3') || startsWith(content, 0xFF, 0xFB)) {
            return Optional.of("mp3");
        }
        if (startsWith(content, 'O', 'g', 'g', 'S')) {
            return Optional.of("ogg");
        }

        // ---- 字体 ----
        if (startsWith(content, 'w', 'O', 'F', 'F')) {
            return Optional.of("woff");
        }
        if (startsWith(content, 'w', 'O', 'F', '2')) {
            return Optional.of("woff2");
        }

        return Optional.empty();
    }

    /**
     * 按内容判断 MIME 类型。
     *
     * <p>它是 {@link #extensionOf} 的结果再查一张表 —— 两者的覆盖范围
     * 因此**完全一致**:嗅探不出扩展名就嗅探不出 MIME。
     *
     * <p>不单独写一套 MIME 的魔数判断是刻意的:两套表会慢慢分叉,
     * 而「扩展名说是 png、MIME 说认不出」这种不一致的结果
     * 会让调用方没法写出正确的校验。
     *
     * @return 形如 {@code image/png};认不出时 empty
     */
    public static Optional<String> detect(byte[] content) {
        return extensionOf(content).map(ContentSniffer::mimeForExtension);
    }

    /**
     * 内容是不是一张图片。
     *
     * <p>⚠ **SVG 不算**。它是一张图片,但它也是一个可以带
     * {@code <script>} 的 XML 文档 —— 把它算进「是图片」会让
     * 一个 {@code image} 校验规则放过存储型 XSS。
     *
     * <p>需要支持 SVG 上传的应用要自己决定怎么消毒(去掉 script、
     * on* 属性、外部引用),而那不是一个嗅探器该做的事。
     */
    public static boolean isImage(byte[] content) {
        return extensionOf(content)
                .map(ext -> java.util.Set.of("png", "jpg", "gif", "webp", "bmp")
                        .contains(ext))
                .orElse(false);
    }

    private static String mimeForExtension(String extension) {
        return switch (extension) {
            case "png"   -> "image/png";
            case "jpg"   -> "image/jpeg";
            case "gif"   -> "image/gif";
            case "webp"  -> "image/webp";
            case "bmp"   -> "image/bmp";
            case "pdf"   -> "application/pdf";
            case "zip"   -> "application/zip";
            case "gz"    -> "application/gzip";
            case "7z"    -> "application/x-7z-compressed";
            case "rar"   -> "application/vnd.rar";
            case "mp4"   -> "video/mp4";
            case "mov"   -> "video/quicktime";
            case "mp3"   -> "audio/mpeg";
            case "ogg"   -> "audio/ogg";
            case "woff"  -> "font/woff";
            case "woff2" -> "font/woff2";

            // extensionOf 返回了一个这里没列的扩展名 —— 那是这两个
            // 方法分叉了。响亮地报出来,而不是回一个
            // application/octet-stream 让它看起来正常
            default -> throw new IllegalStateException("""
                    ContentSniffer 的扩展名表与 MIME 表不一致:
                      extensionOf 返回了 "%s",但 mimeForExtension 没有它。
                      在 extensionOf 里加一个类型时要同时加到这张表里。"""
                    .formatted(extension));
        };
    }

    /**
     * 内容看起来像 HTML 或 SVG 吗。
     *
     * <p>这个检查**单独存在**,因为它是存储型 XSS 的核心:
     * 一个带 {@code <script>} 的文件被命名为 {@code .jpg} 上传,
     * 而浏览器在某些情况下会把它当 HTML 渲染。
     *
     * <p>HTML 没有魔数(它就是文本),所以只能看开头的内容。
     * 这个检查会**误报**(一个真的以 {@code <} 开头的 XML 数据文件),
     * 所以它是一个单独的方法而不是混进
     * {@link #extensionOf} —— 调用方决定怎么处理。
     *
     * <p>跳过开头的空白与 BOM:{@code "\n\n  <script>"} 一样危险,
     * 而一个只看第一个字节的检查会漏掉它。
     */
    public static boolean looksLikeMarkup(byte[] content) {
        if (content == null) return false;

        int start = 0;
        // UTF-8 BOM
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < content.length && Character.isWhitespace(content[start])) {
            start++;
        }

        // 只看前 1 KB —— 一个 HTML 文件的标记一定在开头
        int limit = Math.min(content.length, start + 1024);
        String head = new String(content, start, limit - start,
                java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase();

        return head.startsWith("<!doctype html")
                || head.startsWith("<html")
                || head.startsWith("<?xml")
                || head.startsWith("<svg")
                || head.startsWith("<script")
                || head.contains("<script");
    }

    /**
     * 声称的扩展名与内容一致吗。
     *
     * @return empty = 一致或无法判断;非空 = 不一致,内容实际是这个类型
     */
    public static Optional<String> mismatch(String claimedExtension, byte[] content) {
        var actual = extensionOf(content);
        if (actual.isEmpty()) return Optional.empty();

        String claimed = claimedExtension == null ? ""
                : claimedExtension.toLowerCase().replaceFirst("^\\.", "");

        if (equivalent(claimed, actual.get())) return Optional.empty();
        return actual;
    }

    /**
     * 两个扩展名算同一类吗。
     *
     * <p>要处理三组别名:
     * <ul>
     *   <li>{@code jpg} / {@code jpeg};</li>
     *   <li>zip 系 —— docx/xlsx/pptx/jar/apk **确实是** zip,
     *       所以内容嗅探出 zip 时它们都算一致;</li>
     *   <li>{@code mp4} / {@code m4a} / {@code m4v}。</li>
     * </ul>
     *
     * <p>不处理这些别名的后果:一个正常的 .docx 上传会被判成
     * 「内容与扩展名不符」而被拒 —— 那是一个会立刻被用户投诉的误报。
     */
    private static boolean equivalent(String claimed, String actual) {
        if (claimed.equals(actual)) return true;

        if (actual.equals("jpg")) return claimed.equals("jpeg");
        if (actual.equals("mp4")) {
            return claimed.equals("m4a") || claimed.equals("m4v")
                    || claimed.equals("mov");
        }
        if (actual.equals("zip")) {
            return java.util.Set.of("docx", "xlsx", "pptx", "odt", "ods",
                    "jar", "war", "apk", "epub").contains(claimed);
        }
        return false;
    }

    /**
     * 比较开头的字节。
     *
     * <p>参数用 {@code int} 而不是 {@code byte}:字面量 {@code 0x89}
     * 作为 byte 是负数,写 {@code (byte) 0x89} 会让这些检查
     * 难读得多。
     */
    private static boolean startsWith(byte[] content, int... expected) {
        return matchesAt(content, 0, expected);
    }

    private static boolean matchesAt(byte[] content, int offset, int... expected) {
        if (content.length < offset + expected.length) return false;

        for (int i = 0; i < expected.length; i++) {
            if ((content[offset + i] & 0xFF) != (expected[i] & 0xFF)) return false;
        }
        return true;
    }
}
