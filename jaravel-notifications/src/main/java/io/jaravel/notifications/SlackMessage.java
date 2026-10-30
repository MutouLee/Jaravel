package io.jaravel.notifications;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 Slack 消息(docs/11 §2.1 的 toSlack)。
 *
 * <pre>{@code
 * SlackMessage.of()
 *             .text("发票已支付")
 *             .attachment(a -> a.title("#42").color("good"));
 * }</pre>
 *
 * <h2>用 Incoming Webhook 的 JSON 格式</h2>
 * <p>不是 Slack 的 Web API(那需要 OAuth token 与权限范围)。
 * Incoming Webhook 是一个 URL,POST 一个 JSON 就行 ——
 * 对「发通知」这个用途足够,而且配置只有一个 URL。
 *
 * <p>代价:拿不到 Web API 的能力(改消息、加表情、线程回复)。
 * 真需要那些的话写一个自定义渠道。
 *
 * <h2>attachments 是旧格式,Slack 推荐 Block Kit</h2>
 * <p>这里仍然用 attachments,因为它更简单(一个 title + color
 * 就能出一条有颜色边条的消息),而且 Slack 至今仍然支持它。
 *
 * <p>Block Kit 的表达力强得多,但它的 JSON 结构复杂,
 * 为它做一套 Java 构建器是一个独立的工作量。
 * {@link #block(Map)} 留了一个口子让用户直接给 Block Kit 的 JSON。
 */
public final class SlackMessage {

    private String text;
    private String channel;
    private String username;
    private String iconEmoji;
    private final List<Map<String, Object>> attachments = new ArrayList<>();
    private final List<Map<String, Object>> blocks = new ArrayList<>();

    private SlackMessage() {
    }

    public static SlackMessage of() {
        return new SlackMessage();
    }

    public static SlackMessage of(String text) {
        return new SlackMessage().text(text);
    }

    public SlackMessage text(String value) {
        this.text = value;
        return this;
    }

    /**
     * 覆盖 webhook 默认的频道。
     *
     * <p>⚠ Slack **可能忽略它** —— 新建的 Incoming Webhook 绑定到一个
     * 固定频道,这个字段对它们无效。只有旧的 legacy webhook 支持覆盖。
     *
     * <p>这一条写在这里是因为「设了 channel 但消息还是去了原频道」
     * 看起来像 bug,而它是 Slack 的行为。
     */
    public SlackMessage channel(String value) {
        this.channel = value;
        return this;
    }

    public SlackMessage from(String name, String emoji) {
        this.username = name;
        this.iconEmoji = emoji;
        return this;
    }

    /** 加一条 attachment */
    public SlackMessage attachment(java.util.function.Consumer<Attachment> builder) {
        var attachment = new Attachment();
        builder.accept(attachment);
        attachments.add(attachment.toMap());
        return this;
    }

    /** 直接给一个 Block Kit 的 block —— 需要 attachments 表达不了的东西时用 */
    public SlackMessage block(Map<String, Object> blockJson) {
        blocks.add(blockJson);
        return this;
    }

    /** 序列化成 Incoming Webhook 的 payload */
    public Map<String, Object> payload() {
        var out = new LinkedHashMap<String, Object>();
        if (text != null) out.put("text", text);
        if (channel != null) out.put("channel", channel);
        if (username != null) out.put("username", username);
        if (iconEmoji != null) out.put("icon_emoji", iconEmoji);
        if (!attachments.isEmpty()) out.put("attachments", attachments);
        if (!blocks.isEmpty()) out.put("blocks", blocks);

        if (out.isEmpty()) {
            throw new IllegalStateException("""
                    SlackMessage 是空的 —— 至少要有 text、attachment 或 block。
                      Slack 会拒掉一个空 payload(400 invalid_payload),
                      而那个错误消息不会告诉你是哪条通知发的。""");
        }
        return out;
    }

    /** 一条 attachment */
    public static final class Attachment {

        private final Map<String, Object> fields = new LinkedHashMap<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();

        public Attachment title(String value) {
            fields.put("title", value);
            return this;
        }

        public Attachment titleLink(String url) {
            fields.put("title_link", url);
            return this;
        }

        public Attachment text(String value) {
            fields.put("text", value);
            return this;
        }

        /**
         * 边条颜色。
         *
         * <p>Slack 认三个语义值({@code good} / {@code warning} / {@code danger})
         * 以及任意十六进制。
         *
         * <p>**用语义值而不是十六进制** —— 它们在亮色/暗色主题下都可读,
         * 而一个写死的十六进制在暗色主题下可能看不见。
         */
        public Attachment color(String value) {
            fields.put("color", value);
            return this;
        }

        public Attachment footer(String value) {
            fields.put("footer", value);
            return this;
        }

        /** 一个 key-value 字段 */
        public Attachment field(String title, Object value, boolean inline) {
            rows.add(Map.of("title", title, "value", String.valueOf(value),
                    "short", inline));
            return this;
        }

        Map<String, Object> toMap() {
            var out = new LinkedHashMap<>(fields);
            if (!rows.isEmpty()) out.put("fields", rows);
            return out;
        }
    }
}
