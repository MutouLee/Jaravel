package io.jaravel.core.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 消息目录与翻译(docs/02 §2.3 的 {@code Lang} 契约)。
 *
 * <pre>{@code
 * Lang.get("posts.created");                                  // 一条消息
 * Lang.get("posts.greeting", Map.of("name", user.name));      // 带占位符
 * Lang.choice("posts.count", 3);                              // 复数
 * Lang.setLocale("en");                                       // 切语言
 * }</pre>
 *
 * <h2>消息放哪</h2>
 * <p>{@code resources/lang/<locale>/<group>.yml},键用点号寻址:
 *
 * <pre>
 * resources/lang/zh_CN/posts.yml     posts.created → created: 文章已发布
 * resources/lang/en/posts.yml        posts.created → created: Post published
 * </pre>
 *
 * <p>一层以上的嵌套也行({@code posts.form.title}),而**第一段永远是
 * 文件名** —— 这与 Laravel 一致,也让「这条消息在哪个文件里」可以
 * 从键本身读出来。
 *
 * <h2>★ 找不到键时返回键本身</h2>
 * <p>不抛异常,也不返回空串。理由:
 * <ul>
 *   <li>抛异常会让一个漏翻的字符串**打挂整个页面** ——
 *       而漏翻是翻译工作里最正常的中间状态;</li>
 *   <li>空串会让那个位置静默消失,于是没人知道漏了;</li>
 *   <li>返回 {@code posts.created} 这个键:页面还能用,而漏翻在
 *       页面上一眼可见,也能直接 grep 到位置。</li>
 * </ul>
 *
 * <h2>占位符用 {@code :name}</h2>
 * <p>与 Laravel 一致({@code "你好 :name"})。不用 {@code {name}}:
 * 那个形状与 jblade 的 {@code {{ }}} 以及 Java 的
 * {@code MessageFormat} 都容易混。
 *
 * <p>替换是**一次扫描**完成的,所以一个值里恰好含 {@code :other}
 * 不会被二次替换 —— 否则「用户填的内容里带冒号」会变成一个注入点。
 */
public final class Translator {

    /** locale → (完整键 → 消息) */
    private final Map<String, Map<String, String>> catalogs = new LinkedHashMap<>();

    private volatile String locale;
    private volatile String fallbackLocale;

    public Translator(String locale, String fallbackLocale) {
        this.locale = normalize(locale, "zh_CN");
        this.fallbackLocale = normalize(fallbackLocale, "en");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ================= 目录 =================

    /**
     * 装入一个 locale 的消息。
     *
     * <p>重复装同一个 locale 是**合并**而不是替换 —— 包(package)
     * 提供的消息与应用自己的消息要能共存,而应用的那份后装、覆盖同名键。
     */
    public Translator put(String localeName, Map<String, String> messages) {
        catalogs.computeIfAbsent(normalize(localeName, locale), k -> new LinkedHashMap<>())
                .putAll(messages);
        return this;
    }

    /** 这个 locale 装了多少条 —— 诊断与测试用 */
    public int size(String localeName) {
        return catalogs.getOrDefault(localeName, Map.of()).size();
    }

    public java.util.Set<String> loadedLocales() {
        return java.util.Set.copyOf(catalogs.keySet());
    }

    // ================= locale =================

    public String locale() {
        return locale;
    }

    public void setLocale(String value) {
        this.locale = normalize(value, locale);
    }

    public String fallbackLocale() {
        return fallbackLocale;
    }

    public void setFallbackLocale(String value) {
        this.fallbackLocale = normalize(value, fallbackLocale);
    }

    /** 当前 locale 对应的 {@link Locale} —— 给需要它的格式化器 */
    public Locale asLocale() {
        return Locale.forLanguageTag(locale.replace('_', '-'));
    }

    // ================= 查询 =================

    public boolean has(String key) {
        return lookup(key).isPresent();
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    public String get(String key, Map<String, ?> replacements) {
        return replace(lookup(key).orElse(key), replacements);
    }

    /** 指定 locale 取,忽略当前 locale —— 给「按收件人语言发邮件」用 */
    public String getIn(String localeName, String key, Map<String, ?> replacements) {
        String message = messageIn(localeName, key)
                .or(() -> messageIn(fallbackLocale, key))
                .orElse(key);
        return replace(message, replacements);
    }

    /**
     * 复数(docs/04 §1.8 的 {@code @choice})。
     *
     * <p>消息用 {@code |} 分段,支持两种写法:
     *
     * <pre>
     * 一条消息|:count 条消息                      两段:1 与其余
     * {0} 没有消息|[1,19] :count 条|[20,*] 很多   显式区间
     * </pre>
     *
     * <p>{@code :count} 会被替换成实参。显式区间与两段写法可以混用,
     * 匹配顺序是「先看有没有区间命中,再退回按位置选」。
     */
    public String choice(String key, long count) {
        return choice(key, count, Map.of());
    }

    public String choice(String key, long count, Map<String, ?> replacements) {
        String raw = lookup(key).orElse(null);
        if (raw == null) return key;

        var merged = new LinkedHashMap<String, Object>(replacements);
        merged.putIfAbsent("count", count);
        return replace(select(raw, count), merged);
    }

    // ================= 内部 =================

    private Optional<String> lookup(String key) {
        return messageIn(locale, key).or(() -> messageIn(fallbackLocale, key));
    }

    private Optional<String> messageIn(String localeName, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        Map<String, String> catalog = catalogs.get(localeName);
        return catalog == null ? Optional.empty() : Optional.ofNullable(catalog.get(key));
    }

    /**
     * 占位符替换。
     *
     * <p>★ **一次扫描**,不是对每个键做一次 {@code replace}。
     * 后者的问题:先替换 {@code :name} 得到的值里如果含 {@code :count},
     * 下一轮会把它也替换掉 —— 用户填的内容因此能影响输出,
     * 而那是一个注入点(轻则串字,重则伪造消息)。
     */
    private static String replace(String message, Map<String, ?> replacements) {
        if (message == null || message.indexOf(':') < 0
                || replacements == null || replacements.isEmpty()) {
            return message;
        }

        var out = new StringBuilder(message.length() + 16);
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            if (c != ':') {
                out.append(c);
                i++;
                continue;
            }

            int end = i + 1;
            while (end < message.length()
                    && (Character.isLetterOrDigit(message.charAt(end))
                        || message.charAt(end) == '_')) {
                end++;
            }
            String name = message.substring(i + 1, end);
            Object value = name.isEmpty() ? null : replacements.get(name);
            if (value == null) {
                // 没有对应的替换值 —— 原样留着。
                // 一条「约 :count 条」在没传 count 时显示成「约 :count 条」,
                // 比显示成「约 条」更容易发现是谁漏传了
                out.append(message, i, end);
            } else {
                out.append(value);
            }
            i = end;
        }
        return out.toString();
    }

    /** 按数量挑一段 */
    private static String select(String raw, long count) {
        List<String> parts = splitSegments(raw);
        if (parts.size() == 1) return parts.get(0);

        // 1) 显式区间优先
        for (String part : parts) {
            var range = Range.parse(part);
            if (range != null && range.contains(count)) return range.message();
        }

        // 2) 退回按位置:两段是「1 / 其余」,三段是「0 / 1 / 其余」
        List<String> plain = new ArrayList<>();
        for (String part : parts) {
            if (Range.parse(part) == null) plain.add(part);
        }
        if (plain.isEmpty()) return parts.get(parts.size() - 1).trim();
        if (plain.size() == 1) return plain.get(0).trim();
        if (plain.size() == 2) return (count == 1 ? plain.get(0) : plain.get(1)).trim();
        if (count == 0) return plain.get(0).trim();
        if (count == 1) return plain.get(1).trim();
        return plain.get(2).trim();
    }

    private static List<String> splitSegments(String raw) {
        var out = new ArrayList<String>();
        for (String part : raw.split("\\|")) out.add(part);
        return out;
    }

    /** {@code {0} …} / {@code [1,19] …} / {@code [20,*] …} */
    private record Range(long from, long to, String message) {

        private static final java.util.regex.Pattern EXACT =
                java.util.regex.Pattern.compile("^\\s*\\{\\s*(-?\\d+)\\s*\\}\\s*(.*)$",
                        java.util.regex.Pattern.DOTALL);

        private static final java.util.regex.Pattern SPAN =
                java.util.regex.Pattern.compile(
                        "^\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+|\\*)\\s*\\]\\s*(.*)$",
                        java.util.regex.Pattern.DOTALL);

        static Range parse(String part) {
            var exact = EXACT.matcher(part);
            if (exact.matches()) {
                long value = Long.parseLong(exact.group(1));
                return new Range(value, value, exact.group(2).trim());
            }
            var span = SPAN.matcher(part);
            if (span.matches()) {
                long from = Long.parseLong(span.group(1));
                long to = "*".equals(span.group(2)) ? Long.MAX_VALUE
                                                    : Long.parseLong(span.group(2));
                return new Range(from, to, span.group(3).trim());
            }
            return null;
        }

        boolean contains(long count) {
            return count >= from && count <= to;
        }
    }
}
