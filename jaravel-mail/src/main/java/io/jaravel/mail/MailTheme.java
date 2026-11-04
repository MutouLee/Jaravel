package io.jaravel.mail;

/**
 * 内置邮件组件的调色板(docs/11 §1.4 的 {@code <x-mail::*>})。
 *
 * <h2>为什么颜色在 Java 里而不在模板里</h2>
 * <p>邮件的 CSS 必须内联(客户端不支持外部样式表,多数也会剥掉
 * {@code <style>} 块),所以同一个色值会在模板里出现好几次 ——
 * 按钮的背景同时写在外层 {@code <td>} 和里面的 {@code <a>} 上,
 * 因为谁被剥掉另一个还在。
 *
 * <p>在模板里就得写两遍同样的三元表达式。写在这里是一处定义、
 * 可以被单元测试,而且 {@code jaravel-mail} 的测试不需要模板编译器就能跑
 * (那条规矩记在本模块的 pom 里)。
 *
 * <p>这套色值与 {@link SimpleMailable} 的内置布局**刻意一致** ——
 * 同一个应用里两条路径发出的邮件长得不一样,是个没人会去修
 * 但一直刺眼的问题。
 */
public final class MailTheme {

    private MailTheme() {
    }

    public static final String PRIMARY = "#2563eb";
    public static final String SUCCESS = "#16a34a";
    public static final String DANGER  = "#dc2626";

    /**
     * 按钮颜色名 → 色值。也接受直接写的色值({@code #0f766e})。
     *
     * <p>⚠ 认不出来的名字**回落到 primary 而不是报错**。
     *
     * <p>这与框架别处「不认识就响亮地报错」的做法相反,理由是场合:
     * 模板渲染发生在**发信**的时候,常常在一个队列任务里。
     * 为一个颜色名的拼写错误让一封密码重置邮件发不出去,
     * 比按钮颜色不对要糟得多。而颜色错了在预览里一眼就能看见。
     */
    public static String buttonBackground(String color) {
        if (color == null || color.isBlank()) return PRIMARY;
        if (color.startsWith("#")) return color;
        return switch (color) {
            case "success" -> SUCCESS;
            case "danger", "error" -> DANGER;
            default -> PRIMARY;
        };
    }
}
