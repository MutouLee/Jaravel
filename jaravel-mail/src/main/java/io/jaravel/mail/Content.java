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
     * <p>**不是一个 HTML 解析器** —— 它按顺序做这几件事:
     * 去掉 head / style / script 的内容、把链接变成「文字:URL」、
     * 把块级元素与单元格换成换行与制表符、剥掉标签、还原几个常见实体。
     *
     * <p>为什么不引一个库(jsoup):邮件模板的 HTML 是**自己写的**,
     * 不是从网上抓来的任意 HTML —— 它不需要容错解析。
     *
     * <h2>★ 链接必须留下 URL</h2>
     * <p>只剥标签的话,{@code <a href="…">查看物流</a>} 会变成光秃秃的
     * 「查看物流」—— 而那正是收件人要点的东西。内置的
     * {@code <x-mail::button>} 把整封邮件的行动号召放在一个 {@code <a>} 里,
     * 所以这不是一个边角情况:**退化版本的那封邮件会变得没法用**。
     * 格式与 {@link SimpleMailable} 手写的那份一致(「文字:URL」)。
     *
     * <h2>★ head 要整段去掉</h2>
     * <p>只剥标签会把 {@code <title>} 的文字留在正文最前面 ——
     * 于是文本版本以一行重复的标题开头(而那常常就是收件箱摘要那一行)。
     *
     * <p>**手写的文本版本永远更好**({@code .text(...)} 就在旁边):
     * 这里不知道哪一行是重点、哪个链接可以省,只能保证不丢信息。
     */
    static String plainTextFallback(String html) {
        if (html == null) return null;

        String out = html;
        // head 的**内容**整段去掉:title 的文字会漏进正文
        out = out.replaceAll("(?is)<head[^>]*>.*?</head>", "");
        // style / script 同理(body 里也可能有),只剥标签会把 CSS 留在正文里
        out = out.replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", "");

        // ★ 标签**之间**的换行与缩进先去掉 —— 它是排版噪音,不是内容。
        //
        // 不这么做的话表格的列挨不上:模板里每个 <x-mail::td> 自成一行,
        // 于是渲染出的 HTML 在两个单元格之间留着换行与空行,
        // 后面把 </td> 换成制表符也救不回来(中间夹着换行)。
        //
        // 只吃**含换行**的那些空白:同一行里 </strong> <em> 之间的一个空格
        // 是内容,吃掉它会把两个词连在一起。
        out = out.replaceAll("(?s)>[ \\t]*\\n\\s*<", "><");

        out = rewriteLinks(out);

        // 块级元素与 br 换成换行,否则所有文字会连成一行。
        // 段落与标题给两个换行:上面刚把标签间的换行吃掉了,
        // 不补的话整封邮件会变成一行接一行、没有段落的一块文字。
        out = out.replaceAll("(?i)<br\\s*/?>", "\n");
        out = out.replaceAll("(?i)</(p|h[1-6])>", "\n\n");
        out = out.replaceAll("(?i)</(div|li)>", "\n");

        // 单元格之间用制表符:多数文本客户端会把它对齐成列。
        //
        // ★ 为什么不用「 | 」:邮件的布局本身就是 table 套 table
        // (那是邮件里唯一可靠的布局办法),所以分不清哪个 <td> 是数据、
        // 哪个是布局。制表符是空白 —— 布局单元格留下的那个会被行尾清理掉;
        // 竖线不会,它会变成正文里一串无来由的「|」。
        out = out.replaceAll("(?i)</t[dh]>", "\t");
        out = out.replaceAll("(?i)</tr>", "\n");

        out = stripTags(out);
        out = decodeEntities(out);

        // 压掉多余空白 —— 剥标签会留下大量空行与缩进
        out = out.replaceAll(" {2,}", " ")
                 .replaceAll("(?m)[ \\t]+$", "")
                 .replaceAll("(?m)^[ ]+", "")
                 .replaceAll("\n{3,}", "\n\n");
        return out.trim();
    }


    /** {@code <a href="U">T</a>} → {@code T:U} */
    private static String rewriteLinks(String html) {
        var matcher = java.util.regex.Pattern
                .compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")
                .matcher(html);
        var out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            String label = decodeEntities(stripTags(matcher.group(2))).replaceAll("\\s+", " ").trim();
            // 文字就是 URL 时不要「URL:URL」;没有文字时只留 URL
            String replacement = label.isEmpty() || label.equals(url) ? url : label + ":" + url;
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?s)<[^>]+>", "");
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   // &amp; 放最后:否则 &amp;lt; 会被解成 <
                   .replace("&amp;", "&");
    }
}
