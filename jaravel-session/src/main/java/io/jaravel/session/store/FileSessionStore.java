package io.jaravel.session.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.jaravel.session.SessionStore;

/**
 * 落盘的会话存储。
 *
 * <h2>为什么它对**开发体验**是必需的</h2>
 * <p>docs/15 §3.5:L3 热重启会丢掉进程内状态。如果会话也跟着丢,
 * 那么**每改一行代码就要重新登录一次** —— 开发一个需要登录的页面时,
 * 这个摩擦大到足以让人放弃热重载直接用重启。
 *
 * <p>所以开发模式下会话必须活过重启。可选项:
 * <ul>
 *   <li>cookie 驱动 —— 天然保持,但 4KB 上限,放不下多少东西;</li>
 *   <li>redis —— 保持,但要求开发机装 Redis;</li>
 *   <li><b>文件</b> —— 零依赖,零配置。本类。</li>
 * </ul>
 *
 * <h2>原子写</h2>
 * <p>先写临时文件再 {@code ATOMIC_MOVE}。直接往目标文件写的问题:
 * 写一半时另一个请求读到截断的内容 → 解析失败 → 会话被当作不存在 →
 * **用户随机掉登录**。并发下这是真会发生的,而且极难复现。
 *
 * <h2>id 必须当作不可信输入</h2>
 * <p>会话 id 来自 cookie。直接拿它拼文件名,{@code ../../etc/passwd}
 * 这种值就成了路径穿越。{@link #fileFor} 因此做严格校验,
 * 而不是「清洗」—— 清洗后的 id 与原 id 不一致会导致更隐蔽的问题。
 */
public class FileSessionStore implements SessionStore {

    private static final String SUFFIX = ".session.json";

    private final Path directory;

    public FileSessionStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "无法创建会话目录 " + directory.toAbsolutePath()
                  + "。检查权限,或改用 session.driver: cache。", e);
        }
    }

    @Override
    public String name() {
        return "file";
    }

    /**
     * 文件会话**不**跨进程共享。
     *
     * <p>同一台机器上的多个实例能共享,但多台机器不能 —— 而
     * 「多实例」在生产里几乎总是跨机器。报 false 让启动检查能提醒,
     * 这比上线后遇到「刷新页面随机掉登录」要好。
     */
    @Override
    public boolean isShared() {
        return false;
    }

    // ================= 读写 =================

    @Override
    public Optional<Map<String, Object>> read(String id) {
        Path file = fileFor(id);
        if (!Files.isRegularFile(file)) return Optional.empty();

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            var payload = Payload.parse(json);

            if (payload.isExpired()) {
                // 顺手删掉 —— 不然过期文件会一直堆着
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            return Optional.of(payload.data());
        } catch (IOException | RuntimeException e) {
            // 文件损坏(写入过程中进程被杀、磁盘满)→ 当作没有这个会话。
            // 抛异常会让用户卡在一个 500 上且无法自行恢复,
            // 而「重新登录一次」是可接受的降级
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 删不掉也只能继续
            }
            return Optional.empty();
        }
    }

    @Override
    public void write(String id, Map<String, Object> data, Duration lifetime) {
        Path file = fileFor(id);
        long expiresAt = lifetime == null ? 0
                : System.currentTimeMillis() + lifetime.toMillis();

        try {
            Path temp = Files.createTempFile(directory, "tmp-", SUFFIX);
            Files.writeString(temp, Payload.render(data, expiresAt), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                // 某些文件系统(部分网络盘)不支持原子移动。退化为普通
                // 替换 —— 仍然比「直接写目标文件」好:窗口从
                // 「整个写入过程」缩到「一次 move」
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写会话文件失败:" + file, e);
        }
    }

    @Override
    public void destroy(String id) {
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException("删除会话文件失败:" + id, e);
        }
    }

    @Override
    public int purgeExpired(Duration lifetime) {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    if (Payload.parse(Files.readString(file, StandardCharsets.UTF_8))
                            .isExpired()) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException | RuntimeException corrupt) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描会话目录失败:" + directory, e);
        }
        return removed;
    }

    public Path directory() {
        return directory;
    }

    // ================= 内部 =================

    /**
     * id → 文件路径,**严格校验**。
     *
     * <p>只接受 {@code SessionData.generateId()} 产出的那种形状
     * (base64url 的字母数字加 {@code -_})。不符合的直接拒绝,
     * 而不是过滤掉非法字符 —— 过滤会让两个不同的 id 映射到同一个文件。
     */
    private Path fileFor(String id) {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException(
                    "非法的会话 id(长度 " + (id == null ? 0 : id.length()) + ")");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("""
                        会话 id 含非法字符 '%c'。
                          会话 id 来自 cookie,是不可信输入 —— 直接拿它拼文件名
                          会让 "../../" 这类值变成路径穿越,所以这里直接拒绝。"""
                        .formatted(c));
            }
        }
        return directory.resolve(id + SUFFIX);
    }

    /**
     * 极小的 JSON 编解码。
     *
     * <p>不引 Jackson:{@code jaravel-session} 不该为了开发期的一个
     * 存储驱动多一个依赖。会话数据的值类型有限(字符串、数字、布尔、
     * 字符串列表),手写够用。
     *
     * <p>**限制写在这里**:遇到无法表示的值类型会抛异常而不是静默丢弃 ——
     * 静默丢弃的症状是「某个会话键在开发环境时有时无」。
     */
    private record Payload(Map<String, Object> data, long expiresAtMillis) {

        boolean isExpired() {
            return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
        }

        static String render(Map<String, Object> data, long expiresAt) {
            var sb = new StringBuilder("{\"expires_at\":").append(expiresAt)
                    .append(",\"data\":{");
            boolean first = true;
            for (var entry : data.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
            }
            return sb.append("}}").toString();
        }

        private static String value(Object v) {
            if (v == null) return "null";
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            if (v instanceof String s) return quote(s);
            if (v instanceof java.util.Collection<?> c) {
                var sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(value(item));
                }
                return sb.append(']').toString();
            }
            if (v instanceof Map<?, ?> m) {
                var sb = new StringBuilder("{");
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(String.valueOf(e.getKey()))).append(':')
                      .append(value(e.getValue()));
                }
                return sb.append('}').toString();
            }
            throw new IllegalArgumentException("""
                    会话里的值类型 %s 无法写入文件会话存储。
                      可写入:String / 数字 / 布尔 / 它们的 List 与 Map。
                      放一个实体对象进会话通常不是好主意(它会随代码变化而无法反序列化);
                      改放它的 id,或换 session.driver: cache。"""
                    .formatted(v.getClass().getName()));
        }

        private static String quote(String s) {
            var sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default   -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.append('"').toString();
        }

        static Payload parse(String json) {
            var cursor = new Cursor(json);
            cursor.expect('{');

            long expiresAt = 0;
            Map<String, Object> data = new LinkedHashMap<>();

            while (true) {
                String key = cursor.readString();
                cursor.expect(':');
                if (key.equals("expires_at")) {
                    expiresAt = ((Number) cursor.readValue()).longValue();
                } else if (key.equals("data")) {
                    @SuppressWarnings("unchecked")
                    var parsed = (Map<String, Object>) cursor.readValue();
                    data = parsed;
                } else {
                    cursor.readValue();
                }
                if (!cursor.consumeIf(',')) break;
            }
            cursor.expect('}');
            return new Payload(data, expiresAt);
        }
    }

    /** 手写 JSON 游标。够用就好,不追求完整的 JSON 规范覆盖。 */
    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        void expect(char c) {
            skipSpace();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalStateException(
                        "会话文件格式错误:位置 " + i + " 期望 '" + c + "'");
            }
            i++;
        }

        boolean consumeIf(char c) {
            skipSpace();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String readString() {
            expect('"');
            var sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escaped = s.charAt(i++);
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(escaped);
                }
            }
            i++;
            return sb.toString();
        }

        Object readValue() {
            skipSpace();
            char c = s.charAt(i);

            if (c == '"') return readString();
            if (c == '{') {
                i++;
                var map = new LinkedHashMap<String, Object>();
                skipSpace();
                if (consumeIf('}')) return map;
                while (true) {
                    String key = readString();
                    expect(':');
                    map.put(key, readValue());
                    if (!consumeIf(',')) break;
                }
                expect('}');
                return map;
            }
            if (c == '[') {
                i++;
                var list = new java.util.ArrayList<Object>();
                skipSpace();
                if (consumeIf(']')) return list;
                while (true) {
                    list.add(readValue());
                    if (!consumeIf(',')) break;
                }
                expect(']');
                return list;
            }
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i))  { i += 4; return null; }

            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String number = s.substring(start, i);

            // 不能写成 `cond ? Double.valueOf(n) : Long.valueOf(n)`:
            // 三元运算符的两个分支是 Double 与 Long,Java 会做**二元数值提升**,
            // 于是整个表达式的类型变成 double,所有整数都被拆箱再装箱成 Double。
            // 症状是 put(42L) 读回来是 42.0 —— 之后
            // session.get("user_id", Long.class) 失败,用户莫名掉登录。
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.valueOf(number);
            }
            return Long.valueOf(number);
        }
    }
}
