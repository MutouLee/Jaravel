package io.jaravel.notifications;

import java.util.Map;

/**
 * 一条短信(docs/11 §2.1 的 toSms)。
 *
 * <pre>{@code
 * SmsMessage.of("发票 #42 已支付");
 * SmsMessage.template("SMS_123", Map.of("code", "1234"));
 * }</pre>
 *
 * <h2>只有抽象,没有服务商实现</h2>
 * <p>docs/11 §2.5 的表里 sms 标的是「抽象」—— 具体服务商
 * (阿里云、腾讯云、Twilio)是扩展包。
 *
 * <p>这个分工是对的:短信服务商的 API 差异极大(签名算法、
 * 模板 ID 机制、国内运营商的内容审核规则),把它们塞进框架
 * 意味着框架要跟着它们的 API 变更走。
 *
 * <p>所以这里只定义消息的形状,投递由 {@code SmsChannel.SmsSender}
 * 的实现负责。没装实现时**报错**并说清 —— 静默不发会让
 * 「验证码收不到」变成一个查不到原因的问题。
 *
 * <h2>templateId 是为国内服务商留的</h2>
 * <p>阿里云/腾讯云的短信**必须用预先报备的模板**,不能发任意文本。
 * 它们的 API 收的是「模板 ID + 变量」而不是一段内容。
 *
 * <p>所以这个类同时支持两种形态:
 * <ul>
 *   <li>{@code of("内容")} —— Twilio 这类直接发文本的;</li>
 *   <li>{@code template("SMS_123", Map.of("code", "1234"))} —— 国内服务商。</li>
 * </ul>
 *
 * <p>不做这个区分的话,国内服务商的适配器只能把 {@code content}
 * 当成模板 ID,而那是一个会让人非常困惑的约定。
 *
 * @param content    短信内容。模板形态下为 null
 * @param templateId 模板 ID。文本形态下为 null
 * @param variables  模板变量
 * @param signature  签名(国内服务商必填)
 */
public record SmsMessage(
        String content,
        String templateId,
        Map<String, String> variables,
        String signature
) {

    public SmsMessage {
        variables = variables == null ? Map.of() : Map.copyOf(variables);

        if ((content == null || content.isBlank())
                && (templateId == null || templateId.isBlank())) {
            throw new IllegalArgumentException("""
                    SmsMessage 既没有 content 也没有 templateId。
                      两种形态二选一:
                        SmsMessage.of("内容")                       — Twilio 这类
                        SmsMessage.template("SMS_123", Map.of(...))  — 阿里云/腾讯云
                      国内服务商必须用预先报备的模板,不能发任意文本。""");
        }
    }

    public static SmsMessage of(String content) {
        return new SmsMessage(content, null, null, null);
    }

    public static SmsMessage template(String templateId, Map<String, String> variables) {
        return new SmsMessage(null, templateId, variables, null);
    }

    public SmsMessage withSignature(String value) {
        return new SmsMessage(content, templateId, variables, value);
    }

    public boolean isTemplated() {
        return templateId != null;
    }
}
