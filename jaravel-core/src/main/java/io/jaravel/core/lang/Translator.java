package io.jaravel.core.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.jaravel.core.context.ExecutionScope;

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

    /** {@link ExecutionScope} 里存「这次请求/这个任务的 locale」用的键 */
    private static final String SCOPE_KEY = "jaravel.lang.locale";

    /**
     * 「这一段代码的 locale」—— 比作用域更窄,见 {@link #withLocale}。
     *
     * <p>每个 Translator 实例一份,所以测试里换一个 Translator 不会继承
     * 上一个的覆盖值。
     */
    private final ThreadLocal<String> override = new ThreadLocal<>();

    /**
     * 当前 locale,按**从窄到宽**三层解析:
     *
     * <ol>
     *   <li>{@link #withLocale} 的线程内覆盖 —— 「这几行按收件人的语言渲染」;</li>
     *   <li>当前执行作用域的 locale —— 「这个请求按 Accept-Language 渲染」
     *       (由 {@code SetLocale} 中间件用 {@link #overrideLocale} 写入);</li>
     *   <li>配置里的默认值({@code app.locale})。</li>
     * </ol>
     *
     * <h2>★ 为什么不能只有第 3 层(那个 volatile 字段)</h2>
     * <p>它是**整个 JVM 一份**。按请求切语言如果去改它,两个并发请求会互相
     * 串台:A 把它改成 en,B 正在渲染页面 —— B 的中文页面里混进英文。
     * 同理,队列 worker 里「按收件人语言发邮件」会让两个线程互相覆盖。
     *
     * <p>这类 bug 在低并发下几乎不出现,出现时也无法复现 ——
     * 所以它必须在设计上不可能发生,而不是靠「小心使用」。
     */
    public String locale() {
        String narrow = override.get();
        if (narrow != null) return narrow;

        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.attribute(SCOPE_KEY, String.class))
                .orElse(locale);
    }

    /**
     * 改**默认** locale(整个 JVM)。
     *
     * <p>用于启动时读配置、以及测试前重置。**不要用它实现「按请求切语言」**
     * —— 那会串台,理由见 {@link #locale()}。请求级的切换用
     * {@link #overrideLocale(String)},一段代码内的切换用 {@link #withLocale}。
     */
    public void setLocale(String value) {
        this.locale = normalize(value, locale);
    }

    /**
     * 设定**当前执行作用域**的 locale —— 一个请求、一个队列任务、一条命令。
     *
     * <p>作用域结束时自动失效(它存在 {@link ExecutionScope} 的属性里),
     * 所以不需要也不应该有配对的「恢复」调用。
     *
     * <p>覆盖的是渲染阶段也看得到的那一层:视图在中间件链**返回之后**才
     * 渲染(见 {@code JaravelHandlerAdapter}),所以请求级的 locale 不能用
     * try/finally 的线程覆盖来做 —— 那种写法在模板里会失效,
     * 而症状是「中间件生效了,但页面还是默认语言」。
     *
     * @throws IllegalStateException 不在任何执行作用域里
     */
    public void overrideLocale(String value) {
        String normalized = normalize(value, locale);
        ExecutionScope.tryCurrent().orElseThrow(() -> new IllegalStateException("""
                overrideLocale("%s") 不在任何执行作用域里(请求 / 队列任务 / 命令)。
                  要在一段代码里临时切语言用 Lang.withLocale(locale, () -> ...);
                  要改整个 JVM 的默认语言用 Lang.setLocale(locale)。"""
                .formatted(value))).put(SCOPE_KEY, normalized);
    }

    /**
     * 在某个 locale 下跑一段代码,结束后恢复(可嵌套)。
     *
     * <p>覆盖只对**当前线程**可见,所以并发的另一个请求/任务不受影响。
     */
    public void withLocale(String value, Runnable body) {
        String previous = override.get();
        override.set(normalize(value, locale));
        try {
            body.run();
        } finally {
            if (previous == null) {
                override.remove();
            } else {
                override.set(previous);
            }
        }
    }

    public String fallbackLocale() {
        return fallbackLocale;
    }

    public void setFallbackLocale(String value) {
        this.fallbackLocale = normalize(value, fallbackLocale);
    }

    /** 当前 locale 对应的 {@link Locale} —— 给需要它的格式化器 */
    public Locale asLocale() {
        return Locale.forLanguageTag(locale().replace('_', '-'));
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
        // locale() 而不是 locale 字段 —— 请求级/线程级的覆盖必须对查询生效,
        // 否则中间件设了语言而消息还是默认语言(见 locale() 的注释)
        return messageIn(locale(), key).or(() -> messageIn(fallbackLocale, key));
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
