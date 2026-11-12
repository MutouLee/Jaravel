package io.jaravel.spring;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.http.Response;

/**
 * 内容协商(docs/12 §6.2):按 {@code Accept} 选格式,并写出 XML / CSV。
 *
 * <p>JSON 由应用自己的 {@code ObjectMapper} 写(尊重它的日期格式、包含策略
 * 与自定义序列化器 —— 包括 {@code JsonResource} / 分页信封)。XML 和 CSV
 * **先生成 JSON 树再转换**,走的是同一个 mapper:两份表示因此永远是同一份
 * 数据的两种写法,而不是两套会漂的实现。
 *
 * <h2>★ 不引入 XML 序列化库</h2>
 * <p>{@code jackson-dataformat-xml} 会多两个依赖,而且它默认的根元素名来自
 * 类名({@code LinkedHashMap} 之类),需要额外配置。这里的数据形状
 * (对象 / 数组 / 标量)很小,自己写一棵树的 XML 输出即可:
 * 文本转义、非法元素名(用 {@code <entry name="...">})都是二十行的事。
 *
 * <h2>Accept 的 q 与具体性</h2>
 * <p>对**每个**启用的格式分别算「最具体的那条媒体范围」的 q 值,再取最高者。
 * 不能只按 Accept 里的顺序取第一条 — {@code application/json;q=0, * / *;q=0.8}
 * 里 JSON 被显式排除,只按顺序会把它又选回来。
 */
final class ContentNegotiation {

    private static final List<Response.Format> PREFERENCE =
            List.of(Response.Format.JSON, Response.Format.XML, Response.Format.CSV);

    private ContentNegotiation() {
    }

    /**
     * 选一个格式。
     *
     * @param enabled 控制器声明的格式;空集合按 JSON 处理
     * @return 选中的格式;null 表示客户端不接受任何一个 → 406
     */
    static Response.Format select(Set<Response.Format> enabled, String accept) {
        Set<Response.Format> supported = enabled == null || enabled.isEmpty()
                ? Set.of(Response.Format.JSON)
                : enabled;

        if (accept == null || accept.isBlank()) return first(supported);

        List<MediaRange> ranges = parse(accept);
        if (ranges.isEmpty()) return first(supported);

        Response.Format best = null;
        double bestQ = 0;

        for (Response.Format format : PREFERENCE) {
            if (!supported.contains(format)) continue;

            Double quality = quality(format, ranges);
            if (quality != null && quality > 0 && quality > bestQ) {
                best = format;
                bestQ = quality;
            }
        }
        return best;
    }

    private static Response.Format first(Set<Response.Format> supported) {
        for (Response.Format format : PREFERENCE) {
            if (supported.contains(format)) return format;
        }
        return null;
    }

    /** 这个格式的最具体匹配范围的 q;没有匹配返回 null */
    private static Double quality(Response.Format format, List<MediaRange> ranges) {
        int bestSpecificity = -1;
        Double best = null;

        for (MediaRange range : ranges) {
            int specificity = specificity(range, format);
            if (specificity < 0) continue;

            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                best = range.q();
            } else if (specificity == bestSpecificity && (best == null || range.q() > best)) {
                best = range.q();
            }
        }
        return best;
    }

    /**
     * 一个媒体范围对这个格式有多具体:精确 3 > 类型通配 2 > 全通配 1。
     *
     * @return -1 表示不匹配
     */
    private static int specificity(MediaRange range, Response.Format format) {
        if (range.type().equals("*") && range.subtype().equals("*")) return 1;
        if (range.type().equals("*")) return -1;

        for (String mediaType : mediaTypesOf(format)) {
            int slash = mediaType.indexOf('/');
            String type = mediaType.substring(0, slash);
            String subtype = mediaType.substring(slash + 1);

            if (range.subtype().equals("*")) {
                if (range.type().equals(type)) return 2;
                continue;
            }
            if (range.type().equals(type) && range.subtype().equals(subtype)) return 3;
        }

        // application/vnd.api+json 这类结构化后缀
        if (range.type().equals("application") && range.subtype().endsWith("+json")
                && format == Response.Format.JSON) {
            return 3;
        }
        if (range.type().equals("application") && range.subtype().endsWith("+xml")
                && format == Response.Format.XML) {
            return 3;
        }
        return -1;
    }

    private static List<String> mediaTypesOf(Response.Format format) {
        return switch (format) {
            case JSON -> List.of("application/json");
            case XML -> List.of("application/xml", "text/xml");
            case CSV -> List.of("text/csv");
        };
    }

    private static List<MediaRange> parse(String accept) {
        var ranges = new ArrayList<MediaRange>();

        for (String part : accept.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;

            String[] segments = piece.split(";");
            String media = segments[0].trim().toLowerCase(Locale.ROOT);
            int slash = media.indexOf('/');
            if (slash <= 0 || slash == media.length() - 1) continue;

            String type = media.substring(0, slash).trim();
            String subtype = media.substring(slash + 1).trim();
            double q = 1.0;

            for (int i = 1; i < segments.length; i++) {
                String parameter = segments[i].trim();
                int equals = parameter.indexOf('=');
                if (equals <= 0) continue;
                if (!parameter.substring(0, equals).trim().equalsIgnoreCase("q")) continue;

                try {
                    q = Double.parseDouble(parameter.substring(equals + 1).trim());
                } catch (NumberFormatException ignored) {
                    // 坏的 q 按默认 1.0
                }
            }
            if (!Double.isFinite(q)) q = 1.0;
            q = Math.max(0, Math.min(1, q));

            ranges.add(new MediaRange(type, subtype, q));
        }
        return ranges;
    }

    private record MediaRange(String type, String subtype, double q) {
    }

    // ================= XML =================

    /**
     * JSON 树 → XML。
     *
     * <p>根元素固定 {@code <response>};对象字段用同名字段,数组元素用
     * {@code <item>}。字段名不是合法 XML 名时(空格、冒号、以数字开头…)
     * 用 {@code <entry name="原键">} —— 直接塞进去会生成一份**解析不了**
     * 的 XML,而那种错误在客户端上表现为「整个响应坏了」。
     */
    static String xml(ObjectMapper mapper, Object data) {
        JsonNode tree = mapper.valueToTree(data);
        var out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        element(out, "response", null, tree);
        return out.toString();
    }

    private static void element(StringBuilder out, String name, String attributeKey,
                                JsonNode value) {
        out.append('<').append(name);
        if (attributeKey != null) {
            out.append(" name=\"").append(escape(attributeKey, true)).append('"');
        }
        if (value == null || value.isNull()) {
            out.append("/>");
            return;
        }

        out.append('>');
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                if (validName(entry.getKey())) {
                    element(out, entry.getKey(), null, entry.getValue());
                } else {
                    element(out, "entry", entry.getKey(), entry.getValue());
                }
            });
        } else if (value.isArray()) {
            value.forEach(item -> element(out, "item", null, item));
        } else {
            out.append(escape(value.asText(), false));
        }
        out.append("</").append(name).append('>');
    }

    private static boolean validName(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_.-]*");
    }

    private static String escape(String text, boolean attribute) {
        var out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\'' -> out.append(attribute ? "&apos;" : "'");
                default -> {
                    // XML 1.0 不允许大部分控制字符
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') out.append(c);
                }
            }
        }
        return out.toString();
    }

    // ================= CSV =================

    /**
     * JSON 树 → RFC 4180 CSV。
     *
     * <p>数据必须是对象数组(单个对象视为一行)。嵌套对象/数组的单元格
     * 写成它的 JSON 文本 —— 展开成多列需要一套列名约定,而那不该由
     * 框架替用户决定。
     *
     * <p>⚠ **不防公式注入**:以 {@code = + - @} 开头的单元格在 Excel 里
     * 会被当成公式执行。导出内容来自不可信输入时,调用方要自己消毒 ——
     * 框架若默认改写数据(比如加前导单引号),一份「原始数据导出」
     * 就不再是原始数据了。
     */
    static String csv(ObjectMapper mapper, Object data, List<String> configuredHeaders)
            throws IOException {

        JsonNode tree = mapper.valueToTree(data);
        var rows = new ArrayList<JsonNode>();

        if (tree.isArray()) {
            tree.forEach(rows::add);
        } else if (tree.isObject()) {
            rows.add(tree);
        } else {
            throw new IllegalArgumentException(
                    "CSV 响应需要对象数组(或单个对象),实际是 " + tree.getNodeType());
        }
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw new IllegalArgumentException("CSV 数组的元素必须是对象");
            }
        }

        List<String> headers = configuredHeaders == null || configuredHeaders.isEmpty()
                ? (rows.isEmpty() ? List.of() : fieldNames(rows.get(0)))
                : configuredHeaders;
        if (headers.isEmpty()) return "";

        var out = new StringBuilder();
        appendRow(out, headers);
        for (JsonNode row : rows) {
            var cells = new ArrayList<String>(headers.size());
            for (String header : headers) cells.add(cell(row.get(header)));
            appendRow(out, cells);
        }
        return out.toString();
    }

    private static List<String> fieldNames(JsonNode object) {
        var names = new ArrayList<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String cell(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isValueNode()) return node.asText();
        return node.toString();
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(escapeCsv(cells.get(i)));
        }
        out.append("\r\n");
    }

    private static String escapeCsv(String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
