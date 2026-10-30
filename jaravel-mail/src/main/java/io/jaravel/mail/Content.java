package io.jaravel.mail;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件正文的来源(docs/11 §1.1)。
 *
 * <pre>{@code
 * Content.view("mail.orders.shipped")
 *        .with("order", order)
 *        .text("mail.orders.shipped-text");       // 纯文本备用版本
 *
 * Content.html("<p>你好</p>");                     // 直接给 HTML
 * }</pre>
 *
 * <h2>为什么要有纯文本版本</h2>
 * <p>不是为了「某些客户端不支持 HTML」—— 那个时代过去了。真实理由:
 *
 * <ul>
 *   <li><b>垃圾邮件评分</b>:只有 HTML 没有文本的 multipart 邮件在
 *       SpamAssassin 之类的规则里会加分(MIME_HTML_ONLY);</li>
 *   <li><b>预览片段</b>:很多客户端用文本版本生成收件箱里的那行摘要,
 *       没有文本版本时它会去抓 HTML 的开头 —— 那通常是
 *       「如果无法正常显示请点击这里」;</li>
 *   <li><b>可访问性</b>:屏幕阅读器与纯文本客户端。</li>
 * </ul>
 *
 * <p>所以**没给文本版本时自动从 HTML 退化生成一个**,而不是只发 HTML。
 * 退化出来的东西不如手写的好,但比没有好 —— 见
 * {@link #plainTextFallback(String)}。
 */
public final class Content {

    private String viewName;
    private String textViewName;
    private String html;
    private String text;
    private final Map<String, Object> data = new LinkedHashMap<>();

    private Content() {
    }

    /** 用一个模板渲染 HTML 正文 */
    public static Content view(String name) {
        var content = new Content();
        content.viewName = name;
        return content;
    }

    /** 直接给 HTML,不渲染模板 */
    public static Content html(String markup) {
        var content = new Content();
        content.html = markup;
        return content;
    }

    /** 只有纯文本的邮件 */
    public static Content plain(String body) {
        var content = new Content();
        content.text = body;
        return content;
    }

    /** 纯文本版本的模板 */
    public Content text(String name) {
        this.textViewName = name;
        return this;
    }

    /** 直接给纯文本版本 */
    public Content withText(String body) {
        this.text = body;
        return this;
    }

    public Content with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Content with(Map<String, ?> values) {
        data.putAll(values);
        return this;
    }

    // ================= 访问器 =================

    public String viewName()     { return viewName; }
    public String textViewName() { return textViewName; }
    public String rawHtml()      { return html; }
    public String rawText()      { return text; }

    public Map<String, Object> viewData() {
        return Map.copyOf(data);
    }

    public boolean hasAnyBody() {
        return viewName != null || html != null || text != null;
    }

    /**
     * 从 HTML 退化出一个纯文本版本。
     *
     * <p>**不是一个 HTML 解析器** —— 它做四件事:去掉 style/script 的内容、
     * 把 br/p 之类换成换行、剥掉标签、还原几个常见实体。
     *
     * <p>为什么不引一个库(jsoup):邮件模板的 HTML 是**自己写的**,
     * 不是从网上抓来的任意 HTML —— 它不需要容错解析。
     * 而退化版本的用途(垃圾邮件评分、收件箱摘要)对质量的要求很低。
     *
     * <p>**手写的文本版本永远更好**,所以这个方法的产物带不走
     * 「这是自动生成的」这件事 —— 文档里说清了,而 API 上
     * {@code .text(...)} 就在旁边。
     */
    static String plainTextFallback(String html) {
        if (html == null) return null;

        String out = html;
        // style / script 的**内容**要整段去掉,只剥标签会把 CSS 留在正文里
        out = out.replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", "");
        // 块级元素与 br 换成换行,否则所有文字会连成一行
        out = out.replaceAll("(?i)<br\\s*/?>", "\n");
        out = out.replaceAll("(?i)</(p|div|tr|h[1-6]|li)>", "\n");
        out = out.replaceAll("(?s)<[^>]+>", "");
        out = out.replace("&nbsp;", " ")
                 .replace("&amp;", "&")
                 .replace("&lt;", "<")
                 .replace("&gt;", ">")
                 .replace("&quot;", "\"")
                 .replace("&#39;", "'");
        // 压掉连续空行 —— 剥标签会留下大量空白
        out = out.replaceAll("[ \\t]+", " ")
                 .replaceAll("\n{3,}", "\n\n");
        return out.trim();
    }
}
