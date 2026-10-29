package io.jaravel.validation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 全部内置规则的实现。
 *
 * <p>集中在一个类而不是 60 个类:每条规则的实现平均 3 行,拆成 60 个文件后
 * 「这条规则怎么判空」「min 对不同类型的语义」这些横向一致性问题反而看不见了。
 * 分组用注释与静态工厂,外部只通过 {@link Rule} 的工厂方法接触它们。
 */
final class Rules {

    private Rules() {}

    // ================= 判空 =================

    /**
     * 「空」的定义。
     *
     * <p>与 Laravel 一致:null、空串、空数组/集合都算空,但 {@code 0} 与
     * {@code false} **不算**。这个定义决定了 {@code required} 的行为,
     * 搞错会让「数量填 0」被当成没填。
     */
    static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence s) return s.toString().trim().isEmpty();
        if (value instanceof Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> m) return m.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    /** 简单规则的通用实现 */
    private record Simple(String ruleName, Predicate<Rule.Context> test,
                          Map<String, String> replacements,
                          boolean isImplicit, boolean stops) implements Rule {
        @Override public String name() { return ruleName; }
        @Override public boolean passes(Context context) { return test.test(context); }
        @Override public Map<String, String> replacements() { return replacements; }
        @Override public boolean implicit() { return isImplicit; }
        @Override public boolean stopsOnFailure() { return stops; }
    }

    private static Rule simple(String name, Predicate<Rule.Context> test) {
        return new Simple(name, test, Map.of(), false, false);
    }

    private static Rule simple(String name, Predicate<Rule.Context> test,
                               Map<String, String> replacements) {
        return new Simple(name, test, replacements, false, false);
    }

    private static Rule implicitRule(String name, Predicate<Rule.Context> test) {
        return new Simple(name, test, Map.of(), true, true);
    }

    private static Rule implicitRule(String name, Predicate<Rule.Context> test,
                                     Map<String, String> replacements) {
        return new Simple(name, test, replacements, true, true);
    }

    // ================= 存在性 =================

    static Rule required() {
        return implicitRule("required", c -> !c.isEmptyValue());
    }

    /**
     * 允许为 null。
     *
     * <p>本身永远通过 —— 它是给 {@link Validator} 看的标记,
     * 让后续规则在值为 null 时整体跳过。
     */
    static Rule nullable() {
        return simple("nullable", c -> true);
    }

    static Rule sometimes() {
        return simple("sometimes", c -> true);
    }

    static Rule filled() {
        return simple("filled", c -> !c.present() || !c.isEmptyValue());
    }

    static Rule present() {
        return implicitRule("present", Rule.Context::present);
    }

    static Rule prohibited() {
        return implicitRule("prohibited", c -> !c.present() || c.isEmptyValue());
    }

    static Rule prohibitedIf(String other, String value) {
        return implicitRule("prohibited_if",
                c -> !matches(c.other(other), value) || !c.present() || c.isEmptyValue(),
                Map.of("other", other, "value", value));
    }

    static Rule missingWith(List<String> others) {
        return implicitRule("missing_with",
                c -> others.stream().noneMatch(c::otherPresent) || !c.present(),
                Map.of("values", String.join(", ", others)));
    }

    // ================= 条件必填 =================

    static Rule requiredIf(String other, String value, boolean whenEqual) {
        String ruleName = whenEqual ? "required_if" : "required_unless";
        return implicitRule(ruleName, c -> {
            boolean equal = matches(c.other(other), value);
            boolean mustBePresent = whenEqual == equal;
            return !mustBePresent || !c.isEmptyValue();
        }, Map.of("other", other, "value", value));
    }

    /**
     * {@code required_with} 一族。
     *
     * @param whenPresent true = with(其它字段存在时必填);false = without
     * @param requireAll  true = 全部满足才触发(with_all / without_all)
     */
    static Rule requiredWith(List<String> others, boolean whenPresent, boolean requireAll) {
        String ruleName = (whenPresent ? "required_with" : "required_without")
                        + (requireAll ? "_all" : "");
        return implicitRule(ruleName, c -> {
            var filled = others.stream()
                    .map(o -> c.otherPresent(o) && !isEmpty(c.other(o)))
                    .toList();
            boolean trigger = whenPresent
                    ? (requireAll ? filled.stream().allMatch(b -> b) : filled.stream().anyMatch(b -> b))
                    : (requireAll ? filled.stream().noneMatch(b -> b) : filled.stream().anyMatch(b -> !b));
            return !trigger || !c.isEmptyValue();
        }, Map.of("values", String.join(", ", others)));
    }

    static Rule requiredArrayKeys(List<String> keys) {
        return implicitRule("required_array_keys", c -> {
            if (!(c.value() instanceof Map<?, ?> map)) return false;
            return keys.stream().allMatch(map::containsKey);
        }, Map.of("values", String.join(", ", keys)));
    }

    // ================= 类型 =================

    static Rule typeRule(String name, Predicate<Object> test) {
        return simple(name, c -> test.test(c.value()));
    }

    static Rule integerRule() {
        return simple("integer", c -> {
            Object v = c.value();
            if (v instanceof Integer || v instanceof Long
                    || v instanceof Short || v instanceof Byte) return true;
            if (v instanceof java.math.BigInteger) return true;
            // 表单数据全是字符串,不接受数字字符串会让这条规则对 Web 没用
            if (v instanceof CharSequence s) {
                return s.toString().matches("^[+-]?\\d+$");
            }
            return false;
        });
    }

    static Rule numericRule() {
        return simple("numeric", c -> toDecimal(c.value()) != null);
    }

    static Rule booleanRule() {
        return simple("boolean", c -> {
            Object v = c.value();
            if (v instanceof Boolean) return true;
            // "1"/"0"/"true"/"false" 与 1/0 —— HTML 复选框只会给这些
            String s = v == null ? null : String.valueOf(v);
            return List.of("1", "0", "true", "false").contains(s);
        });
    }

    static Rule arrayRule() {
        return simple("array", c -> c.value() instanceof Collection
                || c.value() instanceof Map
                || (c.value() != null && c.value().getClass().isArray()));
    }

    static Rule jsonRule() {
        return simple("json", c -> {
            String s = c.stringValue();
            if (s == null) return false;
            String t = s.trim();
            // 不引 JSON 库做完整解析:validation 模块不该为一条规则拉一个依赖。
            // 这里做结构性检查(括号配对 + 首尾字符),能挡住绝大多数脏数据。
            if (t.isEmpty()) return false;
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            boolean shape = (first == '{' && last == '}') || (first == '[' && last == ']');
            return shape && balanced(t);
        });
    }

    private static boolean balanced(String text) {
        int curly = 0;
        int square = 0;
        boolean inString = false;
        boolean escaped = false;

        for (char ch : text.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            switch (ch) {
                case '{' -> curly++;
                case '}' -> curly--;
                case '[' -> square++;
                case ']' -> square--;
                default -> { }
            }
            if (curly < 0 || square < 0) return false;
        }
        return curly == 0 && square == 0 && !inString;
    }

    static <E extends Enum<E>> Rule enumRule(Class<E> type) {
        var names = java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        return simple("enum", c -> {
            if (type.isInstance(c.value())) return true;
            return names.contains(String.valueOf(c.value()));
        }, Map.of("values", String.join(", ", names)));
    }

    // ================= 大小 =================

    interface SizeTest {
        boolean ok(BigDecimal size, BigDecimal limit);
    }

    /**
     * {@code min}/{@code max}/{@code size} 的语义**随类型变化**:
     * 字符串按字符数、数值按值本身、数组按元素数、文件按 KB。
     *
     * <p>与 Laravel 一致。这是最容易出错的一组规则 ——
     * {@code max:200} 对数字是「不大于 200」,对字符串是「不超过 200 字」。
     */
    static Rule sizeRule(String name, Number limit, SizeTest test) {
        BigDecimal bound = new BigDecimal(limit.toString());
        return new Rule() {
            @Override public String name() { return name; }

            @Override public boolean passes(Context c) {
                BigDecimal size = sizeOf(c.value(), c);
                return size != null && test.ok(size, bound);
            }

            @Override public Map<String, String> replacements() {
                return Map.of(name, limit.toString(), "value", limit.toString());
            }
        };
    }

    /**
     * 「大小」取哪个量。
     *
     * <p>关键细节:**只有被显式声明为 numeric/integer 的字段才按数值比大小**。
     * 否则表单里的 {@code "5"} 会被当成数字 5 而不是长度 1 的字符串,
     * 于是 {@code max:3} 对 {@code "12345"} 反而通过 —— 这正是 Laravel
     * 踩过并修正的坑。
     */
    private static BigDecimal sizeOf(Object value, Rule.Context context) {
        if (value instanceof Number n) return new BigDecimal(n.toString());

        boolean numericField = context.validator() != null
                && context.validator().isDeclaredNumeric(context.attribute());
        if (numericField) {
            BigDecimal d = toDecimal(value);
            if (d != null) return d;
        }
        return Data.sizeIfCountable(value).map(BigDecimal::valueOf).orElse(null);
    }

    static Rule between(Number low, Number high) {
        BigDecimal lo = new BigDecimal(low.toString());
        BigDecimal hi = new BigDecimal(high.toString());
        if (lo.compareTo(hi) > 0) {
            throw new IllegalArgumentException(
                    "between:" + low + "," + high + " 的下界大于上界,这条规则永远不会通过。");
        }
        return new Rule() {
            @Override public String name() { return "between"; }

            @Override public boolean passes(Context c) {
                BigDecimal size = sizeOf(c.value(), c);
                return size != null && size.compareTo(lo) >= 0 && size.compareTo(hi) <= 0;
            }

            @Override public Map<String, String> replacements() {
                return Map.of("min", low.toString(), "max", high.toString());
            }
        };
    }

    static Rule digits(int count) {
        return simple("digits", c -> {
            String s = c.stringValue();
            return s != null && s.matches("\\d{" + count + "}");
        }, Map.of("digits", String.valueOf(count)));
    }

    static Rule digitsBetween(int min, int max) {
        return simple("digits_between", c -> {
            String s = c.stringValue();
            return s != null && s.matches("\\d{" + min + "," + max + "}");
        }, Map.of("min", String.valueOf(min), "max", String.valueOf(max)));
    }

    static Rule multipleOf(Number divisor) {
        BigDecimal d = new BigDecimal(divisor.toString());
        if (d.signum() == 0) {
            throw new IllegalArgumentException("multiple_of:0 无意义 —— 任何数都不是 0 的倍数。");
        }
        return simple("multiple_of", c -> {
            BigDecimal v = toDecimal(c.value());
            return v != null && v.remainder(d).signum() == 0;
        }, Map.of("value", divisor.toString()));
    }

    static Rule decimal(int min, int max) {
        return simple("decimal", c -> {
            String s = c.stringValue();
            if (s == null) return false;
            int dot = s.indexOf('.');
            int places = dot < 0 ? 0 : s.length() - dot - 1;
            return places >= min && places <= max && toDecimal(s) != null;
        }, Map.of("decimal", min == max ? String.valueOf(min) : min + "-" + max));
    }

    static Rule fieldComparison(String name, String otherField) {
        return simple(name, c -> {
            BigDecimal mine = comparableSize(c.value(), c);
            BigDecimal theirs = comparableSize(c.other(otherField), c);
            if (mine == null || theirs == null) return false;
            int cmp = mine.compareTo(theirs);
            return switch (name) {
                case "gt"  -> cmp > 0;
                case "gte" -> cmp >= 0;
                case "lt"  -> cmp < 0;
                case "lte" -> cmp <= 0;
                default -> throw new IllegalStateException(name);
            };
        }, Map.of("value", otherField));
    }

    private static BigDecimal comparableSize(Object value, Rule.Context context) {
        BigDecimal d = toDecimal(value);
        if (d != null) return d;
        return Data.sizeIfCountable(value).map(BigDecimal::valueOf).orElse(null);
    }

    // ================= 格式 =================

    static Rule pattern(String name, String regex) {
        Pattern compiled = Pattern.compile(regex);
        return simple(name, c -> {
            String s = c.stringValue();
            return s != null && compiled.matcher(s).matches();
        });
    }

    static Rule regex(String regex, boolean negate) {
        // 支持 Laravel 风格的 /pattern/flags
        String body = regex;
        int flags = 0;
        if (regex.length() > 1 && regex.charAt(0) == '/') {
            int close = regex.lastIndexOf('/');
            if (close > 0) {
                body = regex.substring(1, close);
                String modifiers = regex.substring(close + 1);
                if (modifiers.contains("i")) flags |= Pattern.CASE_INSENSITIVE;
                if (modifiers.contains("s")) flags |= Pattern.DOTALL;
                if (modifiers.contains("m")) flags |= Pattern.MULTILINE;
            }
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(body, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "regex 规则的表达式无法编译:" + regex
                  + "\n  " + e.getDescription() + "(位置 " + e.getIndex() + ")"
                  + "\n  注意:字符串规则里的 | 会被当成规则分隔符。"
                  + "含 | 的正则请用 Builder 写法 Rule.regex(\"...\")。", e);
        }
        String name = negate ? "not_regex" : "regex";
        return simple(name, c -> {
            String s = c.stringValue();
            if (s == null) return false;
            boolean found = compiled.matcher(s).find();
            return negate != found;
        });
    }

    /**
     * 邮箱。
     *
     * <p>不用「完整 RFC 5322 正则」—— 那个正则有 6000 字符、没人能审,而且
     * 它放过的地址里有大半 SMTP 服务器并不接受。这里用一个保守的实用模式:
     * 有且仅有一个 @、本地部分非空、域名至少一个点且顶级域为字母。
     */
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$");

    static Rule email(List<String> modes) {
        boolean checkDns = modes.contains("dns");
        return simple("email", c -> {
            String s = c.stringValue();
            if (s == null || !EMAIL.matcher(s).matches()) return false;
            if (s.startsWith(".") || s.contains("..")) return false;
            if (!checkDns) return true;
            try {
                String domain = s.substring(s.indexOf('@') + 1);
                java.net.InetAddress.getByName(domain);
                return true;
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        });
    }

    static Rule url() {
        return simple("url", c -> {
            String s = c.stringValue();
            if (s == null) return false;
            try {
                var uri = java.net.URI.create(s);
                return uri.getScheme() != null
                    && uri.getHost() != null
                    && List.of("http", "https", "ftp", "ftps").contains(uri.getScheme().toLowerCase());
            } catch (IllegalArgumentException e) {
                return false;
            }
        });
    }

    static Rule activeUrl() {
        return simple("active_url", c -> {
            String s = c.stringValue();
            if (s == null) return false;
            try {
                var uri = java.net.URI.create(s);
                if (uri.getHost() == null) return false;
                java.net.InetAddress.getByName(uri.getHost());
                return true;
            } catch (IllegalArgumentException | java.net.UnknownHostException e) {
                return false;
            }
        });
    }

    static Rule uuid() {
        return pattern("uuid",
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    static Rule ulid() {
        // Crockford base32,26 位,首位不超过 7(时间戳上限)
        return pattern("ulid", "^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
    }

    static Rule ip(String variant) {
        return simple(variant, c -> {
            String s = c.stringValue();
            if (s == null) return false;
            boolean v4 = isIpv4(s);
            boolean v6 = !v4 && isIpv6(s);
            return switch (variant) {
                case "ipv4" -> v4;
                case "ipv6" -> v6;
                default -> v4 || v6;
            };
        });
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            // 拒绝前导零:"01" 在某些解析器里是八进制,是 SSRF 绕过的经典手法
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        if (!s.contains(":")) return false;
        try {
            // 用 JDK 的解析器,但要先排掉它会接受的 IPv4 与主机名
            return java.net.InetAddress.getByName(s) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    static Rule timezone() {
        return simple("timezone", c -> {
            String s = c.stringValue();
            return s != null && ZoneId.getAvailableZoneIds().contains(s);
        });
    }

    static Rule affix(String name, List<String> values, boolean prefix) {
        return simple(name, c -> {
            String s = c.stringValue();
            if (s == null) return false;
            return values.stream().anyMatch(v -> prefix ? s.startsWith(v) : s.endsWith(v));
        }, Map.of("values", String.join(", ", values)));
    }

    static Rule contains(List<String> values) {
        return simple("contains", c -> {
            String s = c.stringValue();
            return s != null && values.stream().allMatch(s::contains);
        }, Map.of("values", String.join(", ", values)));
    }

    static Rule caseRule(String name, boolean lower) {
        return simple(name, c -> {
            String s = c.stringValue();
            if (s == null) return false;
            return lower ? s.equals(s.toLowerCase()) : s.equals(s.toUpperCase());
        });
    }

    // ================= 日期 =================

    static Rule date() {
        return simple("date", c -> parseDate(c.value()) != null);
    }

    static Rule dateFormat(String format) {
        rejectPhpStylePattern(format);

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "date_format 的格式串无法解析:" + format
                  + "\n  用 java.time 的模式(yyyy-MM-dd),不是 PHP 的(Y-m-d)。"
                  + "\n  这是与 Laravel 的一处必要差异 —— 底层是 DateTimeFormatter。", e);
        }
        return simple("date_format", c -> {
            String s = c.stringValue();
            if (s == null) return false;
            try {
                formatter.parse(s);
                return true;
            } catch (java.time.format.DateTimeParseException e) {
                return false;
            }
        }, Map.of("format", format));
    }

    /**
     * 拦住照抄过来的 PHP 日期格式串。
     *
     * <p>这不是吹毛求疵:{@code Y-m-d} 在 java.time 里**完全合法** ——
     * {@code Y} 是「周基年」、{@code m} 是「分钟」。于是从 Laravel 迁移过来的
     * {@code date_format:Y-m-d} 不会报错,而是静默地去校验一个
     * 「周年-分钟-日」的格式,接受和拒绝的东西都莫名其妙。
     *
     * <p>静默的错误行为比报错糟糕得多,所以这里主动识别并拒绝。
     */
    private static void rejectPhpStylePattern(String format) {
        String stripped = format.replaceAll("'[^']*'", "");   // 去掉引号里的字面量

        if (stripped.indexOf('Y') >= 0) {
            throw new IllegalArgumentException(
                    "date_format 的格式串 `" + format + "` 里有 `Y`。"
                  + "\n  在 java.time 里 `Y` 是**周基年**(week-based-year),不是普通年份 ——"
                  + "它在跨年那几天会给出和日历年不同的值。"
                  + "\n  这看起来像从 PHP/Laravel 抄来的格式串。对应关系:"
                  + "\n    PHP Y-m-d  →  java.time yyyy-MM-dd"
                  + "\n    PHP H:i:s  →  java.time HH:mm:ss"
                  + "\n  这是 Jaravel 与 Laravel 的一处必要差异:底层是 DateTimeFormatter,"
                  + "换成 PHP 的字母表会让所有 Java 工具链(IDE 提示、其它库)都对不上。");
        }

        boolean hasHour = stripped.chars().anyMatch(c -> c == 'H' || c == 'h' || c == 'k' || c == 'K');
        if (stripped.indexOf('m') >= 0 && !hasHour) {
            throw new IllegalArgumentException(
                    "date_format 的格式串 `" + format + "` 里有小写 `m` 但没有小时字段。"
                  + "\n  在 java.time 里小写 `m` 是**分钟**,月份是大写 `MM`。"
                  + "\n  想表达月份请写 `MM`;若确实要单独校验分钟,把小时一起写上。");
        }

        if (stripped.indexOf('i') >= 0) {
            throw new IllegalArgumentException(
                    "date_format 的格式串 `" + format + "` 里有 `i`。"
                  + "\n  那是 PHP 的分钟标记,java.time 用 `mm`。"
                  + "\n    PHP H:i:s  →  java.time HH:mm:ss");
        }
    }

    /**
     * {@code after}/{@code before} 一族。
     *
     * <p>参照可以是:字面日期、另一个字段名、或相对表达式
     * ({@code today} / {@code +30 days})。三者都要支持,因为真实规则里
     * 「不能早于现在」和「不能早于 publish_at」同样常见。
     */
    static Rule dateComparison(String name, String reference) {
        return simple(name, c -> {
            Instant mine = parseDate(c.value());
            if (mine == null) return false;

            Instant theirs = resolveDateReference(reference, c);
            if (theirs == null) return false;

            int cmp = mine.compareTo(theirs);
            return switch (name) {
                case "after"           -> cmp > 0;
                case "after_or_equal"  -> cmp >= 0;
                case "before"          -> cmp < 0;
                case "before_or_equal" -> cmp <= 0;
                case "date_equals"     -> cmp == 0;
                default -> throw new IllegalStateException(name);
            };
        }, Map.of("date", reference));
    }

    private static Instant resolveDateReference(String reference, Rule.Context context) {
        // 先当成字段名
        if (context.otherPresent(reference)) {
            Instant other = parseDate(context.other(reference));
            if (other != null) return other;
        }
        return parseRelative(reference);
    }

    private static final Pattern RELATIVE =
            Pattern.compile("^([+-])\\s*(\\d+)\\s*(second|minute|hour|day|week|month|year)s?$");

    static Instant parseRelative(String expression) {
        String e = expression.trim().toLowerCase();
        Instant now = Instant.now();

        switch (e) {
            case "now":       return now;
            case "today":     return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
            case "tomorrow":  return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            case "yesterday": return LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            default: break;
        }

        var m = RELATIVE.matcher(e);
        if (m.matches()) {
            long amount = Long.parseLong(m.group(2)) * (m.group(1).equals("-") ? -1 : 1);
            return switch (m.group(3)) {
                case "second" -> now.plusSeconds(amount);
                case "minute" -> now.plusSeconds(amount * 60);
                case "hour"   -> now.plusSeconds(amount * 3600);
                case "day"    -> now.plusSeconds(amount * 86_400);
                case "week"   -> now.plusSeconds(amount * 604_800);
                case "month"  -> LocalDateTime.now().plusMonths(amount)
                                     .atZone(ZoneId.systemDefault()).toInstant();
                case "year"   -> LocalDateTime.now().plusYears(amount)
                                     .atZone(ZoneId.systemDefault()).toInstant();
                default -> null;
            };
        }
        return parseDate(expression);
    }

    static Instant parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof LocalDate d) return d.atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (value instanceof LocalDateTime dt) return dt.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();

        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        try {
            return io.jaravel.support.Cast.to(s, Instant.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================= 比较与集合 =================

    static Rule confirmed() {
        return new Rule() {
            @Override public String name() { return "confirmed"; }

            @Override public boolean passes(Context c) {
                Object other = Data.get(c.data(), c.attribute() + "_confirmation");
                return java.util.Objects.equals(
                        c.value() == null ? null : String.valueOf(c.value()),
                        other == null ? null : String.valueOf(other));
            }

            @Override public Map<String, String> replacements() {
                return Map.of("other", "confirmation");
            }
        };
    }

    static Rule comparison(String name, String otherField, boolean wantEqual) {
        return simple(name, c -> {
            Object other = c.other(otherField);
            boolean equal = java.util.Objects.equals(
                    c.value() == null ? null : String.valueOf(c.value()),
                    other == null ? null : String.valueOf(other));
            return equal == wantEqual;
        }, Map.of("other", otherField));
    }

    static Rule in(List<?> candidates, boolean negate) {
        var asStrings = candidates.stream().map(String::valueOf).toList();
        String name = negate ? "not_in" : "in";
        return simple(name, c -> {
            String s = c.stringValue();
            boolean found = s != null && asStrings.contains(s);
            return negate != found;
        }, Map.of("values", String.join(", ", asStrings)));
    }

    static Rule inArray(String otherPattern) {
        return simple("in_array", c -> {
            var candidates = collectAll(c.data(), otherPattern);
            String s = c.stringValue();
            return s != null && candidates.stream().map(String::valueOf).anyMatch(s::equals);
        }, Map.of("other", otherPattern));
    }

    private static List<Object> collectAll(Map<String, Object> data, String pattern) {
        var out = new ArrayList<Object>();
        for (String path : Data.expand(data, pattern)) {
            out.add(Data.get(data, path));
        }
        return out;
    }

    static Rule distinct() {
        return simple("distinct", c -> {
            if (!(c.value() instanceof Collection<?> items)) return true;
            var seen = new LinkedHashSet<String>();
            for (Object item : items) {
                if (!seen.add(String.valueOf(item))) return false;
            }
            return true;
        });
    }

    static Rule accepted() {
        return implicitRule("accepted", c ->
                List.of("yes", "on", "1", "true").contains(
                        String.valueOf(c.value()).toLowerCase()));
    }

    static Rule acceptedIf(String other, String value) {
        return implicitRule("accepted_if", c -> {
            if (!matches(c.other(other), value)) return true;
            return List.of("yes", "on", "1", "true").contains(
                    String.valueOf(c.value()).toLowerCase());
        }, Map.of("other", other, "value", value));
    }

    static Rule declined() {
        return implicitRule("declined", c ->
                List.of("no", "off", "0", "false").contains(
                        String.valueOf(c.value()).toLowerCase()));
    }

    private static boolean matches(Object actual, String expected) {
        if (actual == null) return expected == null || expected.equals("null");
        return String.valueOf(actual).equals(expected);
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) return null;
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================= 密码强度 =================

    /**
     * 可链式配置的密码规则。
     *
     * <p>默认只要求 8 位 —— 强制「大小写 + 数字 + 符号」已被 NIST SP 800-63B
     * 明确不推荐(它促使用户写 {@code Password1!} 这类可预测口令)。
     * 长度是唯一有确凿证据的要求,其余项留给调用方按业务决定。
     */
    static final class PasswordRule implements Rule {

        private int min = 8;
        private boolean letters;
        private boolean mixedCase;
        private boolean numbers;
        private boolean symbols;
        private List<String> blocklist = List.of();

        public PasswordRule min(int length)       { this.min = length; return this; }
        public PasswordRule letters()             { this.letters = true; return this; }
        public PasswordRule mixedCase()           { this.mixedCase = true; return this; }
        public PasswordRule numbers()             { this.numbers = true; return this; }
        public PasswordRule symbols()             { this.symbols = true; return this; }

        /** 常见弱口令黑名单 */
        public PasswordRule notIn(List<String> forbidden) {
            this.blocklist = forbidden.stream().map(String::toLowerCase).toList();
            return this;
        }

        @Override public String name() { return "password"; }

        @Override public boolean passes(Context c) {
            String s = c.stringValue();
            if (s == null || s.length() < min) return false;
            if (letters && s.chars().noneMatch(Character::isLetter)) return false;
            if (mixedCase && (s.equals(s.toLowerCase()) || s.equals(s.toUpperCase()))) return false;
            if (numbers && s.chars().noneMatch(Character::isDigit)) return false;
            if (symbols && s.chars().allMatch(Character::isLetterOrDigit)) return false;
            return !blocklist.contains(s.toLowerCase());
        }

        @Override public Map<String, String> replacements() {
            return Map.of("min", String.valueOf(min));
        }
    }

    // ================= 自定义规则 =================

    /** {@code Rule.ofContext} 的工厂 —— 构造器签名擦除后会与值版冲突,故走这里 */
    static CustomRule contextRule(String name, Predicate<Rule.Context> test) {
        return new CustomRule(name, test, true);
    }

    static final class CustomRule implements Rule {

        private final String ruleName;
        private final Predicate<Rule.Context> test;
        private String message;
        private boolean isImplicit;
        private final Map<String, String> replacements = new LinkedHashMap<>();

        CustomRule(String name, Predicate<Object> valueTest) {
            this.ruleName = name;
            this.test = c -> valueTest.test(c.value());
        }

        private CustomRule(String name, Predicate<Rule.Context> contextTest, boolean marker) {
            this.ruleName = name;
            this.test = contextTest;
        }

        /** 直接给消息,不走语言包 */
        public CustomRule withMessage(String message) {
            this.message = message;
            return this;
        }

        public CustomRule with(String placeholder, String value) {
            replacements.put(placeholder, value);
            return this;
        }

        /** 字段缺失时也执行 */
        public CustomRule implicitly() {
            this.isImplicit = true;
            return this;
        }

        String message() { return message; }

        @Override public String name() { return ruleName; }
        @Override public boolean passes(Context c) { return test.test(c); }
        @Override public boolean implicit() { return isImplicit; }
        @Override public Map<String, String> replacements() { return Map.copyOf(replacements); }
    }
}
