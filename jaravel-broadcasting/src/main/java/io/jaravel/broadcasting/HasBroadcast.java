package io.jaravel.broadcasting;

import io.jaravel.notifications.Notifiable;

/**
 * 通知要走 {@code broadcast} 渠道就必须实现它(docs/19 §4.1)。
 *
 * <p>与 mail / sms / slack 等渠道的 {@code HasX} 接口同一模式:
 * 接口存在是为了让编译器要求你把方法写对 ——
 * {@code via()} 里有 {@code "broadcast"} 而通知没实现它时,
 * 投递会带着明确的报错失败,而不是静默不发。
 */
public interface HasBroadcast {

    BroadcastMessage toBroadcast(Notifiable notifiable);
}
