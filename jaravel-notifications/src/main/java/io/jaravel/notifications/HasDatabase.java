package io.jaravel.notifications;

/**
 * 能存进站内通知表 —— 通知要走 database 渠道就必须实现它。
 *
 * <p>为什么用接口而不是靠反射找 {@code toDatabase} 方法:
 * 反射方案里把方法名拼错不会报错 —— 找不到方法就当成「这个渠道不发」,
 * 于是「没发出去」没有任何征兆。接口会让编译器要求你写对。
 *
 * <p>完整的理由与另一面(via() 与接口之间没有编译期关联)
 * 见 {@link Notification} 的类注释。
 */
public interface HasDatabase {

    java.util.Map<String, Object> toDatabase(Notifiable notifiable);
}
