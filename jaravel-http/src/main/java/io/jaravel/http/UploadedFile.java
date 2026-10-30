package io.jaravel.http;

import java.util.Optional;

import io.jaravel.support.io.ContentSniffer;
import io.jaravel.support.io.Upload;

/**
 * 一个 multipart 请求里上传上来的文件(docs/03 §的 {@code request.file(...)})。
 *
 * <pre>{@code
 * request.file("avatar").ifPresent(file -> {
 *     String path = Storage.putFile("avatars", file);   // 随机名 + 内容嗅探
 *     user.avatar = path;
 * });
 * }</pre>
 *
 * <h2>为什么没有 {@code file.store("avatars")}</h2>
 * <p>docs/11 §3.3 写的是 {@code file.store("avatars", "s3")}。实现它需要
 * 这个类调到存储层 —— 于是 {@code jaravel-http} 要依赖
 * {@code jaravel-storage},而一个只做 JSON API 的应用不该为此拉进存储。
 *
 * <p>所以方向反过来:存储那一侧提供
 * {@code Storage.putFile(directory, upload)},两边通过
 * {@code jaravel-support} 的 {@link Upload} 接口对接。
 * 写法是 {@code Storage.putFile("avatars", file)} —— 读起来一样清楚。
 *
 * <h2>内容在内存里</h2>
 * <p>{@link #content()} 返回的是一个已经读完的 {@code byte[]} ——
 * 不是一个流。
 *
 * <p>理由:上传的内容几乎总要被**读两次**(一次嗅探类型、
 * 一次写进存储),而一个只能读一次的流会让「先校验再存」
 * 这个唯一正确的顺序写不出来 —— 流式实现里那段代码会变成
 * 「先存下来,再检查,不对再删」,而中间那一刻文件已经在磁盘上了。
 *
 * <p>代价是大文件占内存,所以 **multipart 的解析有一个框架级的
 * 大小上限**(见 starter 里的 {@code jaravel.uploads.max-file-size})。
 * 超限在解析阶段就被拒,不会先吃满内存再报错。
 */
public final class UploadedFile implements Upload {

    private final String fieldName;
    private final String originalName;
    private final String clientMimeType;
    private final byte[] content;

    public UploadedFile(String fieldName, String originalName,
                        String clientMimeType, byte[] content) {
        this.fieldName = fieldName;
        this.originalName = originalName == null ? "" : originalName;
        this.clientMimeType = clientMimeType;
        this.content = content == null ? new byte[0] : content;
    }

    /** 表单字段名 —— 也就是 {@code request.file("avatar")} 里的那个 key */
    public String fieldName() {
        return fieldName;
    }

    @Override
    public byte[] content() {
        // 返回副本:调用方改掉返回的数组不该影响同一个请求里的
        // 另一次 file("avatar") —— 它们拿到的是同一个 UploadedFile
        return content.clone();
    }

    @Override
    public String originalName() {
        return originalName;
    }

    @Override
    public long size() {
        return content.length;
    }

    /**
     * 客户端在 multipart 的 {@code Content-Type} 里声称的类型。
     *
     * <p>⚠ **不可信** —— 它由客户端随便填。一个 PHP 脚本可以声称
     * 自己是 {@code image/png}。
     *
     * <p>要判断真实类型用 {@link #mimeType()}(按内容嗅探)。
     * 这个方法存在只是为了日志与排查 —— 「客户端说它是什么」
     * 在定位一个上传失败时有用。
     */
    public Optional<String> clientMimeType() {
        return Optional.ofNullable(clientMimeType).filter(s -> !s.isBlank());
    }

    /**
     * 按**内容**嗅探出的 MIME 类型。嗅探不出来返回 empty。
     *
     * <p>empty 的含义是「不是一个已知的二进制格式」——
     * 纯文本、CSV、JSON 都落在这里,它们**不是**非法文件。
     * 把 empty 当成「拒绝」会让上传一个 .txt 失败。
     */
    public Optional<String> mimeType() {
        return ContentSniffer.detect(content);
    }

    /**
     * 扩展名:**先按内容嗅探**,嗅探不出来才退回客户端给的名字。
     *
     * <p>⚠ 退回的那一支**没有清理**(它只是切了最后一个点后面的部分)。
     * 直接拿它拼路径仍然是一个漏洞 —— 清理与随机命名在
     * {@code Storage.putFile} 那一侧做,因为那才是路径真正被构造的地方。
     *
     * <p>这个方法的用途是展示与校验(比如 {@code mimes:jpg,png} 规则),
     * 不是构造存储路径。
     */
    public String extension() {
        return sniffedExtension().orElseGet(() -> {
            int dot = originalName.lastIndexOf('.');
            if (dot < 0 || dot == originalName.length() - 1) return "";
            return originalName.substring(dot + 1).toLowerCase();
        });
    }

    /** 客户端给的扩展名 —— **只**用于「客户端声称是什么」的排查 */
    public String clientExtension() {
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) return "";
        return originalName.substring(dot + 1).toLowerCase();
    }

    /** 内容是否被识别为一张图片 */
    public boolean isImage() {
        return ContentSniffer.isImage(content);
    }

    /**
     * 有没有内容。
     *
     * <p>一个空文件是一次**成功**的上传(浏览器在用户选了个 0 字节的
     * 文件时就会这样),所以它不在解析阶段被拒 —— 要不要拒由
     * 校验规则决定。
     */
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public String toString() {
        return "UploadedFile[%s=%s, %d bytes, sniffed=%s]"
                .formatted(fieldName, originalName, content.length,
                        mimeType().orElse("未识别"));
    }
}
