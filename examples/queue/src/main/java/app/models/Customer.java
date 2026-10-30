package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.notifications.Notifiable;

/**
 * 客户 —— 站内通知的归属。
 *
 * <p>这个示例里它只有三个字段:通知需要一个「发给谁」,
 * 而那个「谁」必须是一个 {@link Notifiable}。
 */
@Model(table = "customers")
@Fillable({"name", "email"})
public class Customer extends CustomerBase implements Notifiable {

    @Id public Long id;
    public String name;
    public String email;

    public Instant createdAt;
    public Instant updatedAt;

    /**
     * 这个渠道往哪发。
     *
     * <p>返回 null 表示「这个渠道不可用」—— 那个渠道会被**跳过**而不是
     * 报错。这里只给 mail:这个示例没有短信与 Slack 的配置,
     * 而一个返回了地址但发不出去的渠道比一个被跳过的渠道更难排查。
     *
     * <p>⚠ {@code database} 渠道**不走这个方法** —— 它要的是
     * {@link #notifiableId()} / {@link #notifiableType()}。
     * 这就是为什么只实现 mail 的客户也能收到站内通知。
     */
    @Override
    public String routeNotificationFor(String channel) {
        return switch (channel) {
            case "mail" -> email;
            default -> null;
        };
    }

    @Override
    public Object notifiableId() {
        return id;
    }

    @Override
    public String notifiableType() {
        // 简单名而不是全限定名 —— 一次包重构不该让历史通知的
        // notifiable_type 全部对不上(理由同 notifications.type,
        // 见那张表的迁移注释)
        return "Customer";
    }
}
