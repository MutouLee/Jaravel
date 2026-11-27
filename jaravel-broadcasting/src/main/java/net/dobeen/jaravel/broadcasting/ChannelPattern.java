package net.dobeen.jaravel.broadcasting;

import java.util.HashMap;
import java.util.Map;

/**
 * 一个编译好的频道模式(docs/19 §3.2)。
 *
 * <pre>
 * orders.{orderId}         {x} 捕获一个段(非空、不含点)
 * private-users.*          * 只允许出现在末尾,匹配任意剩余段(含空)
 * </pre>
 *
 * <p>频道名只允许 {@code [a-zA-Z0-9._-]},长度 ≤ 200,不允许空段
 * (连续的点 / 首尾的点)。名字与模式共用同一套校验 ——
 * 用户代码里注册的模式与客户端发来的频道名犯的是同一类错,
 * 报错信息也应该是同一句话。
 */
public final class ChannelPattern {

    private final String raw;
    private final Segment[] segments;
    private final boolean wildcardTail;

    private ChannelPattern(String raw, Segment[] segments, boolean wildcardTail) {
        this.raw = raw;
        this.segments = segments;
        this.wildcardTail = wildcardTail;
    }

    public static ChannelPattern compile(String pattern) {
        validateCommon(pattern, "频道模式");
        if (!pattern.matches("[a-zA-Z0-9._\\-{}*]+")) {
            throw new IllegalArgumentException("""
                    频道模式 "%s" 含有非法字符 —— 只允许字母、数字、点、下划线、
                    连字符、{name} 占位与末尾的 *。"""
                    .formatted(pattern));
        }
        String[] parts = pattern.split("\\.");
        Segment[] segments = new Segment[parts.length];
        boolean wildcardTail = false;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if ("*".equals(part)) {
                if (i != parts.length - 1) {
                    throw new IllegalArgumentException(
                            "频道模式 \"" + pattern + "\" 里 * 只能出现在末尾。");
                }
                segments[i] = Segment.WILDCARD;
                wildcardTail = true;
            } else if (part.startsWith("{") && part.endsWith("}")) {
                String name = part.substring(1, part.length() - 1);
                if (!name.matches("[a-zA-Z0-9_]+")) {
                    throw new IllegalArgumentException(
                            "频道模式 \"" + pattern + "\" 里 {name} 的名字只能是字母、数字、下划线。");
                }
                segments[i] = Segment.capture(name);
            } else {
                segments[i] = Segment.literal(part);
            }
        }
        return new ChannelPattern(pattern, segments, wildcardTail);
    }

    /**
     * 校验一个频道名:字符集、长度、空段。
     *
     * <p>公开的静态方法 —— 传输层用它校验**客户端发来的频道名**。
     * 与模式共用长度/空段约束,但字符集更窄:频道名里不允许
     * {@code {name}} 与 {@code *},否则一个名为
     * {@code private-x.{y}} 的频道会匹配上两条毫不相干的规则。
     */
    public static void validate(String name) {
        validateCommon(name, "频道名");
        if (!name.matches("[a-zA-Z0-9._\\-]+")) {
            throw new IllegalArgumentException("""
                    频道名 "%s" 含有非法字符 —— 只允许字母、数字、点、下划线、连字符。
                      频道命名见 docs/19 §1.1:orders / private-orders.42 / presence-chat.1。"""
                    .formatted(name));
        }
    }

    /** 频道名与模式共用的约束:非空、长度、无空段 */
    private static void validateCommon(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + "不能为空。");
        }
        if (value.length() > 200) {
            throw new IllegalArgumentException(
                    what + " \"" + value + "\" 太长(> 200 字符)。");
        }
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            throw new IllegalArgumentException(
                    what + " \"" + value + "\" 有空段(连续的点或首尾的点)。");
        }
    }

    /**
     * 匹配。不匹配返回 {@code null};匹配返回捕获的参数
     * ({@code *} 捕获的剩余段以 "." 连接,键名为 {@code "*"})。
     */
    public Map<String, String> match(String channel) {
        String[] parts = channel.split("\\.");
        if (!wildcardTail && parts.length != segments.length) return null;
        if (wildcardTail && parts.length < segments.length) return null;

        Map<String, String> captured = null;
        for (int i = 0; i < segments.length; i++) {
            Segment segment = segments[i];
            if (segment.isWildcard()) {
                var map = new HashMap<String, String>();
                StringBuilder rest = new StringBuilder();
                for (int j = i; j < parts.length; j++) {
                    if (j > i) rest.append('.');
                    rest.append(parts[j]);
                }
                map.put("*", rest.toString());
                return map;
            }
            if (segment.isCapture()) {
                if (captured == null) captured = new HashMap<>();
                captured.put(segment.name(), parts[i]);
            } else if (!segment.name().equals(parts[i])) {
                return null;
            }
        }
        return captured == null ? Map.of() : captured;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    /** 模式的一段 */
    private record Segment(String name, boolean capture, boolean wildcard) {

        static Segment literal(String name) {
            return new Segment(name, false, false);
        }

        static Segment capture(String name) {
            return new Segment(name, true, false);
        }

        static final Segment WILDCARD = new Segment("*", false, true);

        boolean isCapture() {
            return capture;
        }

        boolean isWildcard() {
            return wildcard;
        }
    }
}
