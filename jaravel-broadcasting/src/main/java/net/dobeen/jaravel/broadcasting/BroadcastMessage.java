package net.dobeen.jaravel.broadcasting;

import java.util.Map;

/**
 * 通知 broadcast 渠道的 payload(docs/19 §4.1)。
 *
 * <p>收件频道不由消息决定 —— 它从收件人推导
 * ({@link BroadcastNotifiable} 覆盖或默认 {@code private-{type}.{id}}),
 * 与「一封邮件写给谁」不在 Mailable 里同一个道理。
 */
public record BroadcastMessage(Map<String, Object> data) {

    public static BroadcastMessage of(Map<String, Object> data) {
        return new BroadcastMessage(data == null ? Map.of() : Map.copyOf(data));
    }

    public static BroadcastMessage empty() {
        return of(Map.of());
    }
}
