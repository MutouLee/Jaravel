package io.jaravel.storage;

import java.util.Locale;
import java.util.Set;

/**
 * 路径与文件名的安全检查(docs/11 §3.3 的「安全约束」)。
 *
 * <p>设计文档把这些列为「**必须实现,不是可选**」:
 * <ul>
 *   <li>默认用随机文件名,防止路径穿越与覆盖;</li>
 *   <li>原始文件名若被使用,必须经过 sanitize(去除 {@code ../}、
 *       空字节、控制字符、Windows 保留名);</li>
 *   <li>{@code local} 驱动的 root 做路径规范化检查,拒绝逃出 root 的路径。</li>
 * </ul>
 *
 * <h2>为什么这些检查集中在一个类里</h2>
 * <p>它们会被**每个驱动**用到。分散在各驱动里的后果是:
 * 新加一个驱动时漏掉其中一条,而那一条就是漏洞所在。
 *
 * <p>而且它们可以被单独、密集地测 —— 一个安全检查的测试
 * 应该能枚举几十种恶意输入,而那在驱动的测试里会被淹掉。
 */
public final class PathGuard {

    /**
     * Windows 的保留设备名。
     *
     * <p>在 Windows 上创建一个叫 {@code CON} 或 {@code NUL} 的文件会
     * **失败或者写到设备里**(而不是文件系统)。
     *
     * <p>⚠ 带扩展名也算({@code CON.txt} 同样保留),所以检查的是
     * 第一个点之前的部分。
     *
     * <p>这个检查在 Linux 上也做 —— 一个在 Linux 上存下来的
     * {@code CON.png} 会在 Windows 的开发机上同步/解压时出问题,
     * 而那个问题的现场离上传那一刻很远。
     */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /**
     * {@code public} 磁盘上默认禁止的扩展名。
     *
     * <p>docs/11 §3.3:「{@code public} 磁盘默认不允许上传可执行类型」。
     *
     * <p>这些类型放在一个对外可访问的目录里意味着**远程代码执行** ——
     * 如果那个目录恰好被一个会执行它们的服务器(Apache + mod_php、
     * Tomcat)服务。
     *
     * <p>{@code html} / {@code svg} 在列表里是因为**存储型 XSS**:
     * 它们能带 {@code <script>},而浏览器会在你的域名下执行它 ——
     * 于是攻击者拿到了同源的 cookie 访问权。
     */
    private static final Set<String> DANGEROUS_ON_PUBLIC = Set.of(
            "php", "php3", "php4", "php5", "phtml", "phar",
            "jsp", "jspx", "jsw", "jsv", "jspf",
            "asp", "aspx", "ashx", "asmx",
            "cgi", "pl", "py", "rb", "sh", "bash",
            "exe", "dll", "so", "bat", "cmd", "com", "msi",
            "html", "htm", "xhtml", "shtml",
            "svg",
            "jar", "war", "ear", "class");

    private PathGuard() {
    }

    /**
     * 规范化一个存储路径(不是文件系统路径)。
     *
     * <p>做的事:统一分隔符为 {@code /}、去掉首尾的 {@code /}、
     * 压掉连续的 {@code /}、**解析并拒绝 {@code ..}**。
     *
     * <p>⚠ {@code ..} 是**拒绝**而不是解析掉。
     *
     * <p>解析掉(把 {@code a/../b} 变成 {@code b})看起来更宽容,
     * 但它让一个有害的输入变成一个合法的输出 —— 于是
     * 「有人在尝试路径穿越」这件事被静默吞掉了。
     * 而那通常是攻击的第一步,它值得被知道。
     *
     * @throws UnsafePathException 路径含 {@code ..}、空字节或控制字符
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new UnsafePathException("存储路径不能为空。");
        }

        requireNoNullByte(path);
        requireNoControlChars(path);

        String unified = path.replace('\\', '/');

        var segments = new java.util.ArrayList<String>();
        for (String segment : unified.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;

            if (segment.equals("..")) {
                throw new UnsafePathException("""
                        存储路径里不允许 ".." :%s
                          这里**拒绝**而不是解析掉它。
                          解析掉(把 a/../b 变成 b)看起来更宽容,但它让一个
                          有害的输入变成一个合法的输出 —— 于是「有人在尝试
                          路径穿越」这件事被静默吞掉了,而那通常是攻击的第一步。"""
                        .formatted(path));
            }
            segments.add(segment);
        }

        if (segments.isEmpty()) {
            throw new UnsafePathException(
                    "存储路径 \"%s\" 规范化之后是空的。".formatted(path));
        }
        return String.join("/", segments);
    }

    /**
     * 检查一个解析后的文件系统路径有没有逃出 root。
     *
     * <p>这是 {@code local} 驱动的**最后一道闸**:即便
     * {@link #normalize} 漏掉了什么,这里也会拦下。
     *
     * <p>用 {@code toRealPath} / {@code toAbsolutePath().normalize()}
     * 而不是字符串前缀比较 —— **符号链接**能绕过字符串比较:
     * {@code storage/app/link} 指向 {@code /etc} 的话,
     * {@code storage/app/link/passwd} 的字符串前缀是对的,
     * 而它真实指向 {@code /etc/passwd}。
     *
     * @throws UnsafePathException 逃出了 root
     */
    public static void requireWithin(java.nio.file.Path root,
                                     java.nio.file.Path candidate) {
        java.nio.file.Path realRoot = resolveReal(root);
        java.nio.file.Path realCandidate = resolveReal(candidate);

        if (realCandidate.startsWith(realRoot)) return;

        throw new UnsafePathException("""
                路径逃出了磁盘的 root:
                  root:   %s
                  目标:   %s(解析后 %s)
                  这道检查用的是**解析后的真实路径**而不是字符串前缀比较 ——
                  符号链接能绕过字符串比较:storage/app/link 指向 /etc 的话,
                  storage/app/link/passwd 的字符串前缀是对的,
                  而它真实指向 /etc/passwd。"""
                .formatted(realRoot, candidate, realCandidate));
    }

    /**
     * 解析成真实路径。
     *
     * <p>文件还不存在时 {@code toRealPath} 会抛,所以退回
     * 「绝对化 + 规范化」—— 那时没有符号链接可解,
     * 但父目录的符号链接**已经**被 root 那一侧的解析处理掉了。
     */
    private static java.nio.file.Path resolveReal(java.nio.file.Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException notYetExisting) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * 清理一个用户给的文件名。
     *
     * <p>**只是文件名,不含目录** —— 任何路径分隔符都会被去掉。
     *
     * <p>做的事:
     * <ol>
     *   <li>只取最后一段(去掉 {@code ../} 与任何目录);</li>
     *   <li>去掉空字节与控制字符;</li>
     *   <li>把不安全的字符换成 {@code _};</li>
     *   <li>Windows 保留名前加 {@code _};</li>
     *   <li>截断到 255 字节(多数文件系统的上限);</li>
     *   <li>结果为空时给一个随机名。</li>
     * </ol>
     *
     * <p>⚠ **即便如此,默认仍然应该用 {@link #hashName}。**
     * sanitize 是「用户坚持要保留原名」时的退路,而它保不住两件事:
     * <ul>
     *   <li>**覆盖** —— 两个用户上传同名文件,后者覆盖前者;</li>
     *   <li>**信息泄露** —— 原始文件名常含人名、项目代号、日期。</li>
     * </ul>
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return hashName(null);

        // 先取最后一段 —— 这一步同时处理掉 ../ 与 C:\path\ 这类
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        // 去掉空字节与控制字符(含 \r\n —— 它们能注入 HTTP 头)
        base = base.replaceAll("[\\x00-\\x1f\\x7f]", "");

        // Windows 不允许的字符 + 一些在 URL 里会出问题的
        base = base.replaceAll("[<>:\"/\\\\|?*]", "_");

        // 开头的点会让文件变成隐藏文件(Unix),而那不是用户的意图
        base = base.replaceAll("^\\.+", "");

        base = base.trim();
        if (base.isEmpty()) return hashName(null);

        // Windows 保留名 —— 带扩展名也算,所以看第一个点之前
        String stem = base.contains(".")
                ? base.substring(0, base.indexOf('.'))
                : base;
        if (WINDOWS_RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
            base = "_" + base;
        }

        // 255 字节(不是字符)—— 中文文件名一个字 3 字节,
        // 按字符截会超过文件系统的限制
        return truncateToBytes(base, 255);
    }

    private static String truncateToBytes(String value, int maxBytes) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;

        // 不能直接按字节切 —— 会把一个多字节字符切成两半
        var buffer = java.nio.ByteBuffer.wrap(bytes, 0, maxBytes);
        var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE);
        try {
            return decoder.decode(buffer).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return value.substring(0, Math.min(value.length(), maxBytes / 4));
        }
    }

    /**
     * 随机文件名,保留扩展名。
     *
     * <p>**这是默认行为**(docs/11 §3.3)。它一次解决三件事:
     * 路径穿越、覆盖、原始文件名里的信息泄露。
     *
     * @param extension 扩展名,可带点也可不带。null = 不要扩展名
     */
    public static String hashName(String extension) {
        String random = java.util.UUID.randomUUID().toString().replace("-", "");

        if (extension == null || extension.isBlank()) return random;

        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        // 扩展名本身也要清理 —— 它来自用户
        ext = ext.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);

        return ext.isEmpty() ? random : random + "." + ext;
    }

    /**
     * 这个扩展名放在一个对外可访问的目录里安全吗。
     *
     * @throws UnsafePathException 不安全
     */
    public static void requireSafeForPublic(String path, Set<String> allowedExtras) {
        String ext = extensionOf(path);
        if (ext.isEmpty()) return;

        if (allowedExtras != null && allowedExtras.contains(ext)) return;
        if (!DANGEROUS_ON_PUBLIC.contains(ext)) return;

        throw new UnsafePathException("""
                不允许往 public 磁盘写 .%s 文件:%s

                  理由分两类:
                    · php / jsp / cgi / exe 这些放在对外可访问的目录里
                      意味着**远程代码执行** —— 如果那个目录恰好被一个
                      会执行它们的服务器服务;
                    · html / svg 能带 <script>,浏览器会在**你的域名下**
                      执行它 —— 于是攻击者拿到了同源的 cookie 访问权
                      (存储型 XSS)。

                  确实需要的话在 config/filesystems.yml 里显式允许:
                    disks:
                      public:
                        allow-extensions: [ html, svg ]

                  更好的做法是把它们放到一个**不同的域名**下
                  (user-content.example.com),那样 XSS 拿不到主站的 cookie。"""
                .formatted(ext, path));
    }

    /** 路径的扩展名,小写、不带点。没有则空串 */
    public static String extensionOf(String path) {
        if (path == null) return "";

        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;

        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";

        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void requireNoNullByte(String path) {
        if (path.indexOf('\0') < 0) return;

        throw new UnsafePathException("""
                存储路径里有空字节。
                  这几乎总是一次攻击尝试:一些底层 API 用 C 字符串,
                  "a.png\\0.php" 在 Java 侧看是 .png 结尾,
                  传到 C 层会被截断成 "a.png" —— 于是扩展名检查被绕过。""");
    }

    private static void requireNoControlChars(String path) {
        for (char c : path.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                throw new UnsafePathException("""
                        存储路径里有控制字符(0x%02x)。
                          \\r 与 \\n 尤其危险:它们能注入 HTTP 响应头
                          (比如在一个 Content-Disposition 的文件名里)。"""
                        .formatted((int) c));
            }
        }
    }

    /** 路径不安全。**不可恢复** —— 它要么是 bug 要么是攻击 */
    public static class UnsafePathException extends SecurityException {
        public UnsafePathException(String message) {
            super(message);
        }
    }
}
