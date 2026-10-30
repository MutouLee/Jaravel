package io.jaravel.notifications.channel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.database.Connection;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.HasDatabase;
import io.jaravel.notifications.NotificationChannel;

/**
 * database 渠道 —— 站内通知(docs/11 §2.4)。
 *
 * <pre>{@code
 * create table notifications (
 *   id              char(36) primary key,
 *   type            varchar(255) not null,
 *   notifiable_type varchar(255) not null,
 *   notifiable_id   varchar(64)  not null,
 *   data            text         not null,
 *   read_at         timestamp    null,
 *   created_at      timestamp    not null,
 *   index (notifiable_type, notifiable_id, read_at)
 * );
 * }</pre>
 *
 * <h2>notifiable_id 是 varchar 而不是 bigint</h2>
 * <p>docs/11 §2.4 里写的是 {@code bigint}。改成字符串,因为
 * 主键不一定是数字 —— UUID 主键在多实例写入的场景里很常见,
 * 而 {@code @Model} 也支持非自增主键。
 *
 * <p>代价:索引稍大、按 id 范围查不方便。两者对这张表都无所谓
 * (它只按「谁的 + 读没读」查)。
 *
 * <p>做成 bigint 的后果是:一个 UUID 主键的应用用不了站内通知,
 * 而那时的报错会是一个数据库层的类型转换错误。
 *
 * <h2>type 存的是什么</h2>
 * <p>通知类的**简单名**,不是全限定名。
 *
 * <p>理由与 {@link Notifiable#notifiableType()} 相同:
 * 一次包重构会让所有历史通知的 type 对不上,
 * 而那些数据还在表里,只是前端按类型分支的那段代码全部失效了。
 *
 * <p>代价:两个不同包里的同名通知会撞。那种情况下显式覆盖
 * {@link Notification#displayName()}。
 */
public class DatabaseChannel implements NotificationChannel {

    /** 这张表只存 JSON 字符串,所以用一个最小配置的 mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature
                    .WRITE_DATES_AS_TIMESTAMPS);

    private final Connection connection;
    private final String table;

    public DatabaseChannel(Connection connection) {
        this(connection, "notifications");
    }

    public DatabaseChannel(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    @Override
    public String name() {
        return "database";
    }

    /**
     * 站内通知**不需要**一个外部地址。
     *
     * <p>不覆盖这个方法的话,一个没实现
     * {@code routeNotificationFor("database")} 的模型会让这个渠道
     * 被静默跳过 —— 而那是一个很难查的「站内通知不出现」。
     */
    @Override
    public boolean requiresRoute() {
        return false;
    }

    @Override
    public void send(Notifiable notifiable, Notification notification) {
        Map<String, Object> data = require(notifiable, notification,
                HasDatabase.class).toDatabase(notifiable);

        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.randomUUID().toString());
        row.put("type", notification.displayName());
        row.put("notifiable_type", notifiable.notifiableType());
        row.put("notifiable_id", String.valueOf(notifiable.notifiableId()));
        row.put("data", encode(notification, data));
        row.put("read_at", null);
        row.put("created_at", Instant.now());

        connection.table(table).insert(row);
    }

    /**
     * 把 data 序列化成 JSON。
     *
     * <p>序列化失败时**报错并说清是哪条通知** —— 而不是存一个
     * 空的 {@code {}}。后者会让站内通知列表里出现一条
     * 什么都显示不出来的条目,而原因(toDatabase 里放了一个模型)
     * 已经无从追溯。
     */
    private String encode(Notification notification, Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("""
                    通知 %s 的 toDatabase() 返回的数据无法序列化成 JSON:%s
                      站内通知的 data 列存 JSON,所以它必须是 JSON 可表示的。
                      常见原因:放了一个 Eloquent 模型或其它实体。
                      改成只放渲染这条通知需要的字段 —— 而且**只放必要的**:
                      站内通知会被长期保留(用户可能一年后才看),
                      而一年前的模型快照与现在的数据不一致。
                      要最新数据就只存 id,渲染时查。"""
                    .formatted(notification.displayName(), e.getMessage()), e);
        }
    }
}
