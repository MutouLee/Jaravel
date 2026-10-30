package io.jaravel.notifications.channel;

import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.HasSms;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.notifications.SmsMessage;

/**
 * sms 渠道 —— **只有抽象,没有服务商实现**(docs/11 §2.5)。
 *
 * <pre>{@code
 * // 应用自己提供一个发送器
 * @Bean
 * SmsChannel.SmsSender aliyunSms(AliyunSmsClient client) {
 *     return (phone, message) -> client.send(phone,
 *             message.signature(), message.templateId(), message.variables());
 * }
 * }</pre>
 *
 * <h2>为什么不内置一个服务商</h2>
 * <p>短信服务商的 API 差异极大:签名算法(阿里云用 HMAC-SHA1 的
 * 一套特定规范化规则)、模板报备机制、国内运营商的内容审核。
 * 把其中一家塞进框架意味着框架要跟着它的 API 变更走,
 * 而那家的 SDK 本身就在做这件事。
 *
 * <p>所以这个渠道只定义「怎么拿到消息」,投递交给
 * {@link SmsSender} 的实现。
 *
 * <h2>没装发送器时**报错**</h2>
 * <p>不静默跳过。短信最常见的用途是验证码,而
 * 「验证码收不到」是一个用户会立刻放弃注册的体验 ——
 * 它必须在开发期就暴露。
 */
public class SmsChannel implements NotificationChannel {

    private final SmsSender sender;

    public SmsChannel(SmsSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return "sms";
    }

    @Override
    public void send(Notifiable notifiable, Notification notification) {
        if (sender == null) {
            throw new IllegalStateException("""
                    通知 %s 要走 sms 渠道,但没有装短信发送器。
                      Jaravel **不内置任何短信服务商** —— 它们的 API 差异极大
                      (签名算法、模板报备、内容审核),而那些厂商的 SDK
                      本身就在做这件事。
                      提供一个 @Bean:
                        @Bean SmsChannel.SmsSender sms(AliyunSmsClient client) {
                            return (phone, msg) -> client.send(phone,
                                    msg.signature(), msg.templateId(), msg.variables());
                        }
                      这里报错而不是静默跳过 —— 短信最常见的用途是验证码,
                      而「验证码收不到」是一个用户会立刻放弃注册的体验。"""
                    .formatted(notification.displayName()));
        }

        SmsMessage message = require(notifiable, notification,
                HasSms.class).toSms(notifiable);

        String phone = notifiable.routeNotificationFor("sms");
        sender.send(phone, message);
    }

    /** 由应用提供 —— 对接具体的短信服务商 */
    public interface SmsSender {

        /**
         * @param phone   手机号
         * @param message 消息。{@code isTemplated()} 为 true 时用
         *                {@code templateId} + {@code variables},
         *                否则用 {@code content} —— 见 {@link SmsMessage}
         */
        void send(String phone, SmsMessage message);
    }
}
