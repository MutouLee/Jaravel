package app.notifications;

import java.util.List;
import java.util.Map;

import io.jaravel.mail.Mailable;
import io.jaravel.notifications.HasDatabase;
import io.jaravel.notifications.HasMail;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;

/**
 * 注册后的欢迎通知 —— 同时发邮件与站内。
 *
 * <p>**字段只存主键**,与队列任务一样 —— 而这与 Mailable **不同**。
 *
 * <p>理由见 {@code JaravelNotificationsAutoConfiguration.wireQueue} 的注释:
 * 邮件能在投递时渲染完(所以 Mailable 从不进 payload),而通知不能 ——
 * 各渠道的 {@code toMail} / {@code toDatabase} 产出的是不同类型的东西,
 * 没有一个统一的「渲染结果」。所以 {@code shouldQueue()} 时
 * 通知对象本身要进 payload。
 *
 * <p>三条规矩放在一起记:
 * <ul>
 *   <li>{@code ShouldQueue} 的字段 → 只存主键;</li>
 *   <li>{@code Notification} 的字段 → 只存主键;</li>
 *   <li>{@code Mailable} 的字段 → 随便放。</li>
 * </ul>
 */
public class WelcomeAboard extends Notification implements HasMail, HasDatabase {

    private final String name;

    public WelcomeAboard(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * 两个渠道。
     *
     * <p>这个示例里是固定的。真实项目里它会看收件人的偏好 ——
     * {@code via()} 是运行时决定的,正是为此。
     */
    @Override
    public List<String> via(Notifiable notifiable) {
        return List.of("mail", "database");
    }

    @Override
    public Mailable toMail(Notifiable notifiable) {
        // Mailable.simple():不需要模板。通知的 toMail 几乎总是
        // 「几行字 + 一个按钮」,为它建一个模板文件是多余的
        return Mailable.simple()
                .subject("欢迎加入")
                .greeting("你好," + name + "!")
                .line("你的账号已经创建好了。")
                .action("去看看", "http://localhost:8002/dashboard")
                .line("有问题随时找我们。");
    }

    @Override
    public Map<String, Object> toDatabase(Notifiable notifiable) {
        // 只放渲染这条站内通知需要的东西。站内通知会被长期保留
        // (用户可能一年后才看),而一年前的模型快照与现在的数据不一致
        return Map.of("message", "欢迎加入," + name);
    }
}
