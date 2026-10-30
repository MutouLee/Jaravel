package io.jaravel.support.io;

/**
 * 一个「待存储的文件」—— 内容 + 客户端声称的名字。
 *
 * <h2>为什么这个接口在 jaravel-support</h2>
 * <p>它是 HTTP 层({@code UploadedFile})与存储层
 * ({@code Storage.putFile})之间的契约。
 *
 * <p>放在任何一侧都会造成一次不该有的依赖:放在存储里的话 HTTP 层
 * 要依赖存储;放在 HTTP 里的话存储要依赖 HTTP(更糟 —— 一个
 * 「从 zip 里解出来的文件」也该能存,而它与 HTTP 无关)。
 *
 * <p>所以它在 support 里:一个两行的接口,零依赖。
 */
public interface Upload {

    /** 文件内容 */
    byte[] content();

    /**
     * 客户端给的原始文件名。
     *
     * <p>⚠ **完全不可信** —— 它可能是 {@code ../../etc/passwd}、
     * 可能带空字节、可能是一个伪装的扩展名
     * (一个 PHP 脚本命名为 {@code avatar.png})。
     *
     * <p>存储层只用它做「内容嗅探失败时的退路」,而且那个退路
     * 也过清理。任何**直接**用它拼路径的代码都是一个路径穿越漏洞。
     */
    String originalName();

    /** 字节数 */
    default long size() {
        byte[] bytes = content();
        return bytes == null ? 0 : bytes.length;
    }

    /**
     * 按**内容**嗅探出的扩展名。
     *
     * <p>这是 docs/11 §3.3 那条安全约束的落点:
     * 扩展名从内容来,不从客户端给的文件名来。
     *
     * <p>嗅探不出来时返回 empty —— 调用方决定是拒绝还是退回
     * (清理后的)原始扩展名。
     */
    default java.util.Optional<String> sniffedExtension() {
        return ContentSniffer.extensionOf(content());
    }

    /** 用一对值快速构造 —— 测试与非 HTTP 来源(比如解压出来的文件)用 */
    static Upload of(byte[] content, String originalName) {
        return new Upload() {
            @Override public byte[] content() { return content; }

            @Override public String originalName() { return originalName; }
        };
    }
}
