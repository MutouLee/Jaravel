package io.jaravel.core.lang;

import java.util.Map;

/**
 * 翻译的静态门面(docs/02 §2.3)。
 *
 * <pre>{@code
 * Lang.get("posts.created");
 * Lang.get("posts.greeting", Map.of("name", user.name));
 * Lang.choice("posts.count", 3);
 * Lang.setLocale("en");
 * }</pre>
 *
 * <p>模板里对应 {@code @lang('posts.created')} 与
 * {@code @choice('posts.count', n)}(docs/04 §1.8)。
 *
 * <h2>没装配时**不报错**</h2>
 * <p>与 {@code Cache} / {@code Queue} 那些门面相反:取不到 Translator 时
 * 返回**键本身**,而不是抛异常。
 *
 * <p>理由与「找不到键时返回键本身」是同一条(见 {@link Translator}):
 * 一个没有消息目录的应用(绝大多数单语言应用)照样该能渲染页面,
 * 而让它在第一处 {@code @lang} 上 500 是最糟的结果。
 * 返回键本身时页面还能用,且漏翻在页面上一眼可见。
 */
public final class Lang {

    private static volatile Translator translator;

    private Lang() {
    }

    public static void use(Translator configured) {
        translator = configured;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        translator = null;
    }

    public static boolean isConfigured() {
        return translator != null;
    }

    /**
     * 取 Translator;没有就返回 null。
     *
     * <p>调用方必须处理 null —— 这是上面那条「不报错」的落点。
     */
    public static Translator translator() {
        return translator;
    }

    public static String get(String key) {
        Translator t = translator;
        return t == null ? key : t.get(key);
    }

    public static String get(String key, Map<String, ?> replacements) {
        Translator t = translator;
        return t == null ? key : t.get(key, replacements);
    }

    public static String choice(String key, long count) {
        Translator t = translator;
        return t == null ? key : t.choice(key, count);
    }

    public static String choice(String key, long count, Map<String, ?> replacements) {
        Translator t = translator;
        return t == null ? key : t.choice(key, count, replacements);
    }

    public static boolean has(String key) {
        Translator t = translator;
        return t != null && t.has(key);
    }

    /** 当前 locale;没装配时回 {@code app.locale} 的默认值 */
    public static String locale() {
        Translator t = translator;
        return t == null ? "zh_CN" : t.locale();
    }

    public static void setLocale(String locale) {
        Translator t = translator;
        if (t != null) t.setLocale(locale);
    }

    /** 在某个 locale 下跑一段 —— 「按收件人语言渲染邮件」用 */
    public static void withLocale(String locale, Runnable body) {
        Translator t = translator;
        if (t == null) {
            body.run();
            return;
        }
        String previous = t.locale();
        t.setLocale(locale);
        try {
            body.run();
        } finally {
            t.setLocale(previous);
        }
    }
}
