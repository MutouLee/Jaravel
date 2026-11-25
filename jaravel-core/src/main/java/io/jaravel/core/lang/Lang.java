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

    /**
     * 改**默认** locale(整个 JVM)。
     *
     * <p>按请求切语言不要用它 —— 见 {@link #useLocale(String)}
     * 与 {@link Translator#locale()} 里「为什么不能只有那个 volatile 字段」。
     */
    public static void setLocale(String locale) {
        Translator t = translator;
        if (t != null) t.setLocale(locale);
    }

    /**
     * 设定**当前执行作用域**的 locale —— 一个请求、一个队列任务、一条命令。
     *
     * <p>{@code SetLocale} 中间件用的就是它(按 {@code Accept-Language}
     * 选语言)。作用域结束自动失效,不需要配对的恢复调用。
     */
    public static void useLocale(String locale) {
        Translator t = translator;
        if (t != null) t.useLocale(locale);
    }

    /**
     * 在某个 locale 下跑一段 —— 「按收件人语言渲染邮件」用。
     *
     * <p>覆盖只对**当前线程**可见:并发的另一个请求/任务不受影响。
     * 此前这里是「改全局默认值再改回来」,那在多线程下会串台
     * (两个 worker 各发一封不同语言的邮件时互相覆盖)。
     */
    public static void withLocale(String locale, Runnable body) {
        Translator t = translator;
        if (t == null) {
            body.run();
            return;
        }
        t.withLocale(locale, body);
    }
}
