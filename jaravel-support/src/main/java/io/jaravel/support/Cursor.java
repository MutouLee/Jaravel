package io.jaravel.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游标 —— 一页数据的「位置」(docs/12 §2.1)。
 *
 * <p>它记的是**上一页最后一行的排序列值**,而不是一个偏移量:
 *
 * <pre>
 * created_at=2026-11-05T10:00Z, id=42, 方向=往后
 * </pre>
 *
 * <h2>★ 为什么游标分页比 offset 强</h2>
 * <p>{@code offset 10000} 要求数据库**数过前一万行**(即便有索引),
 * 而且翻页期间有人插入新行的话,偏移量会让一行**被跳过或被看两次** ——
 * 那正是无限滚动列表里「有时少一条」的来历。
 *
 * <p>游标用的是 {@code where (created_at, id) > (…, …)},
 * 走索引、与偏移量无关,而且对并发插入是稳定的。
 *
 * <h2>★ 为什么要编码成不透明串</h2>
 * <p>客户端拿到的是 {@code eyJ2MSJ9…} 这样一段 base64。那不是为了保密
 * (它只是 base64,谁都能解开),而是为了**不让它变成契约**:
 *
 * <ul>
 *   <li>一旦客户端看懂了 {@code ?after_id=42},就会有人手写
 *       {@code ?after_id=100} —— 而那在排序不是按 id 的接口上
 *       会给出一个毫无意义的结果页;</li>
 *   <li>服务端改了排序列(从 id 改成 created_at+id),明文参数就**静默**
 *       失效,而不透明游标会**解不开**(于是能明确报错)。</li>
 * </ul>
 *
 * <p>用 URL 安全的 base64:它要进查询串,而标准 base64 里的
 * {@code +} 与 {@code /} 在那里要转义,一旦有人忘了转义就是
 * 「偶尔翻页失败」。
 */
public final class Cursor {

    /** 版本前缀 —— 将来改编码时能认出旧游标并明确报错,而不是解出一堆垃圾 */
    private static final String VERSION = "v1";

    private static final char FIELD_SEPARATOR = '|';
    private static final char PART_SEPARATOR = ':';

    private final Map<String, Object> position;
    private final boolean pointsToNext;

    private Cursor(Map<String, Object> position, boolean pointsToNext) {
        this.position = new LinkedHashMap<>(position);
        this.pointsToNext = pointsToNext;
    }

    public static Cursor at(Map<String, Object> position, boolean pointsToNext) {
        return new Cursor(position, pointsToNext);
    }

    /** 排序列 → 那一行上的值。顺序与 ORDER BY 一致 —— 字典序比较要用 */
    public Map<String, Object> position() {
        return Map.copyOf(position);
    }

    public List<String> columns() {
        return List.copyOf(position.keySet());
    }

    public Object valueOf(String column) {
        return position.get(column);
    }

    /** true = 这个游标指向「后面的数据」(下一页);false = 前一页 */
    public boolean pointsToNext() {
        return pointsToNext;
    }

    // ================= 编码 =================

    public String encode() {
        var raw = new StringBuilder(VERSION);
        position.forEach((column, value) -> raw.append(FIELD_SEPARATOR)
                .append(escape(column)).append(PART_SEPARATOR)
                .append(Type.of(value).tag).append(PART_SEPARATOR)
                .append(escape(Type.of(value).encode(value))));
        raw.append(FIELD_SEPARATOR).append(pointsToNext ? "next" : "prev");

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解开一个游标。
     *
     * @throws IllegalArgumentException 解不开时 —— **明确报错而不是当成第一页**。
     *         当成第一页的后果是:客户端一直翻页,而服务端一直返回第一页,
     *         于是那个列表永远滚不动(而且不报错)。
     */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("游标是空的");
        }

        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("游标不是合法的 base64:" + abbreviate(encoded), e);
        }

        List<String> fields = split(raw, FIELD_SEPARATOR);
        if (fields.size() < 2 || !fields.get(0).equals(VERSION)) {
            throw new IllegalArgumentException(
                    "游标的版本对不上(期望 " + VERSION + "):" + abbreviate(encoded)
                            + " —— 服务端改过排序列的话,旧游标就是解不开的,"
                            + "客户端应当从第一页重新开始。");
        }

        boolean next = "next".equals(fields.get(fields.size() - 1));
        var position = new LinkedHashMap<String, Object>();
        for (int i = 1; i < fields.size() - 1; i++) {
            List<String> parts = split(fields.get(i), PART_SEPARATOR);
            if (parts.size() != 3) {
                throw new IllegalArgumentException("游标里有一段读不懂:" + fields.get(i));
            }
            String column = unescape(parts.get(0));
            Type type = Type.byTag(parts.get(1));
            position.put(column, type.decode(unescape(parts.get(2))));
        }
        return new Cursor(position, next);
    }

    /**
     * 列名与值里可能出现分隔符。
     *
     * <p>不转义的话一个带冒号的值(时间戳!)会把那一段切成四块 ——
     * 而 {@code 2026-11-05T10:00:00Z} 恰恰是最常见的排序列值。
     * 这是写完第一版立刻就会撞到的事,所以转义不是防御性编程。
     */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace(String.valueOf(FIELD_SEPARATOR), "\\p")
                .replace(String.valueOf(PART_SEPARATOR), "\\c");
    }

    private static String unescape(String raw) {
        var out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            out.append(switch (next) {
                case 'p' -> FIELD_SEPARATOR;
                case 'c' -> PART_SEPARATOR;
                default -> next;
            });
        }
        return out.toString();
    }

    private static List<String> split(String raw, char separator) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                current.append(c).append(raw.charAt(++i));
                continue;
            }
            if (c == separator) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static String abbreviate(String value) {
        return value.length() <= 24 ? value : value.substring(0, 24) + "…";
    }

    @Override
    public String toString() {
        return "Cursor" + position + (pointsToNext ? "→" : "←");
    }

    /**
     * 值的类型标签。
     *
     * <h2>★ 为什么必须带类型</h2>
     * <p>游标解开之后那些值要**绑进 SQL 的 where**。全当字符串绑的话:
     * SQLite 会把 {@code "42"} 与 {@code 42} 当成不同的东西比较
     * (类型亲和性),于是翻页条件永远不成立 —— 列表卡在第一页。
     * 而时间戳更糟:字符串比较在跨时区格式下给出错误的顺序。
     */
    private enum Type {
        LONG("l") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return Long.valueOf(v); }
        },
        INT("i") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return Integer.valueOf(v); }
        },
        STRING("s") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return v; }
        },
        BOOL("b") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return Boolean.valueOf(v); }
        },
        DECIMAL("d") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return new java.math.BigDecimal(v); }
        },
        INSTANT("t") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return java.time.Instant.parse(v); }
        },
        DATE("D") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return java.time.LocalDate.parse(v); }
        },
        DATETIME("T") {
            @Override String encode(Object v) { return v.toString(); }
            @Override Object decode(String v) { return java.time.LocalDateTime.parse(v); }
        },
        NULL("n") {
            @Override String encode(Object v) { return ""; }
            @Override Object decode(String v) { return null; }
        };

        final String tag;

        Type(String tag) {
            this.tag = tag;
        }

        abstract String encode(Object value);

        abstract Object decode(String value);

        static Type of(Object value) {
            if (value == null) return NULL;
            if (value instanceof Long) return LONG;
            if (value instanceof Integer || value instanceof Short) return INT;
            if (value instanceof Boolean) return BOOL;
            if (value instanceof java.math.BigDecimal) return DECIMAL;
            if (value instanceof java.time.Instant) return INSTANT;
            if (value instanceof java.time.LocalDate) return DATE;
            if (value instanceof java.time.LocalDateTime) return DATETIME;
            if (value instanceof CharSequence) return STRING;

            // ★ 不静默当成字符串。
            //
            // 一个 UUID 主键 toString 之后当字符串绑进去,在某些驱动上
            // 比较不出正确的顺序 —— 而症状是「翻页跳行」,
            // 查到那里要很久。宁可现在就报错,说清怎么绕过。
            throw new IllegalArgumentException("""
                    游标暂时不支持把 %s 作为排序列的类型。
                      已支持:整数 / 字符串 / 布尔 / BigDecimal /
                      Instant / LocalDate / LocalDateTime。
                      出路:按一个受支持的列排序(主键几乎总是可以),
                      或者用 paginate(…) / simplePaginate(…)。"""
                    .formatted(value.getClass().getName()));
        }

        static Type byTag(String tag) {
            for (Type type : values()) {
                if (type.tag.equals(tag)) return type;
            }
            throw new IllegalArgumentException("游标里有一个不认识的类型标记:" + tag);
        }
    }
}
