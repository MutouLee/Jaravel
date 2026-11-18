package io.jaravel.testing;

import org.springframework.context.ApplicationContext;

import io.jaravel.auth.Auth;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.EmailVerification;
import io.jaravel.auth.Verification;
import io.jaravel.auth.access.Gate;
import io.jaravel.auth.access.GateRegistry;
import io.jaravel.auth.hash.Hash;
import io.jaravel.auth.hash.HashManager;
import io.jaravel.auth.passwords.Password;
import io.jaravel.auth.passwords.PasswordBroker;
import io.jaravel.broadcasting.Broadcast;
import io.jaravel.broadcasting.BroadcastManager;
import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.database.Connection;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.mail.Mail;
import io.jaravel.mail.MailManager;
import io.jaravel.migration.schema.Schema;
import io.jaravel.notifications.NotificationManager;
import io.jaravel.notifications.Notifications;
import io.jaravel.permissions.PermissionStore;
import io.jaravel.permissions.Permissions;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.storage.FilesystemManager;
import io.jaravel.storage.Storage;
import io.jaravel.validation.DatabaseRule;

/**
 * 把静态门面重绑到**当前测试的** Spring 上下文。
 *
 * <h2>★ 为什么需要它</h2>
 * <p>门面({@code Artisan} / {@code Queue} / {@code Eloquent} / {@code Mail} …)
 * 是 JVM 全局的静态字段,由 starter 在**上下文启动时**赋值。一个测试 JVM
 * 里通常同时缓存着**多个上下文**:
 *
 * <ul>
 *   <li>默认的 {@code JaravelTestApplication}(全部 {@link FeatureTest} 共用);</li>
 *   <li>自带 {@code @SpringBootTest(classes = ...)} 的端到端测试
 *       (例如队列示例的广播/多实例测试,它们还各自带一份独立的内存库)。</li>
 * </ul>
 *
 * <p>Spring 的上下文缓存**复用**已启动的上下文,不会重新启动。于是出现这样的顺序:
 *
 * <pre>
 * 默认上下文启动          → 门面指向它
 * 广播测试的上下文启动     → 门面指向广播上下文(另一份库)
 * 默认上下文的测试继续跑   → 门面仍指向广播上下文
 * </pre>
 *
 * <p>症状是「测试刚插入的数据,命令/任务查不到」——失败取决于测试类的
 * **执行顺序**,单独跑全绿,全量跑偶发。这正是 CI 上出现过的那一例。
 *
 * <h2>为什么放在 @BeforeEach 而不是 @BeforeAll</h2>
 * <p>同一个类里也可能有多个测试上下文(Spring 按配置缓存);
 * 每个测试前重绑一次最便宜,也不会影响子类 {@code @BeforeEach} 里
 * 安装的 fake({@code Mail.fake()} 等)—— 基类的 {@code @BeforeEach}
 * 先跑,JUnit 保证这一点。
 *
 * <h2>与 starter 的关系</h2>
 * <p>这里是 starter 各 {@code Jaravel*AutoConfiguration} 里静态绑定的镜像。
 * 新增一个 JVM 级门面时,**两边都要加**;漏了这里的后果是测试里偶发
 * 看到另一个上下文的数据。绑定统一用「能取到 bean 才绑」,
 * 于是没启用某个模块时不会报错。
 */
final class StaticBindings {

    private StaticBindings() {
    }

    static void bind(ApplicationContext context) {
        Container container = bean(context, Container.class);
        if (container != null) {
            Application.bootstrap(container);
        }

        ConfigRepository config = bean(context, ConfigRepository.class);
        if (config != null) {
            Application.setEnvironment(config.string("app.env", "production"));
            Application.setDebug(config.bool("app.debug", false));
        }

        Connection connection = bean(context, Connection.class);
        if (connection != null) {
            Eloquent.useConnection(connection);
            Schema.useConnection(connection);
            DatabaseRule.useConnection(connection);

            if (config != null && config.has("jaravel.eloquent.prevent-lazy-loading")) {
                Eloquent.preventLazyLoading(
                        config.bool("jaravel.eloquent.prevent-lazy-loading"));
            }
        }

        CommandRegistry commands = bean(context, CommandRegistry.class);
        if (commands != null) {
            Artisan.use(commands);
        }

        EventBus events = bean(context, EventBus.class);
        if (events != null) {
            Event.use(events);
        }

        QueueManager queues = bean(context, QueueManager.class);
        if (queues != null) {
            Queue.use(queues);
        }

        MailManager mail = bean(context, MailManager.class);
        if (mail != null) {
            Mail.use(mail);
        }

        NotificationManager notifications = bean(context, NotificationManager.class);
        if (notifications != null) {
            Notifications.use(notifications);
        }

        FilesystemManager filesystems = bean(context, FilesystemManager.class);
        if (filesystems != null) {
            Storage.use(filesystems);
        }

        BroadcastManager broadcast = bean(context, BroadcastManager.class);
        if (broadcast != null) {
            Broadcast.use(broadcast);
        }

        AuthManager auth = bean(context, AuthManager.class);
        if (auth != null) {
            Auth.use(auth);
        }

        GateRegistry gate = bean(context, GateRegistry.class);
        if (gate != null) {
            Gate.use(gate);
        }

        HashManager hash = bean(context, HashManager.class);
        if (hash != null) {
            Hash.use(hash);
        }

        PasswordBroker passwords = bean(context, PasswordBroker.class);
        if (passwords != null) {
            Password.use(passwords);
        }

        EmailVerification verification = bean(context, EmailVerification.class);
        if (verification != null) {
            Verification.use(verification);
        }

        PermissionStore permissions = bean(context, PermissionStore.class);
        if (permissions != null) {
            Permissions.use(permissions);
        }
    }

    /**
     * 取 bean;没有、有多个、或创建失败都返回 null。
     *
     * <p>不抛异常是刻意的:重绑是「尽力让门面指向当前上下文」,
     * 没有某个模块的应用不该因此跑不了测试。
     */
    private static <T> T bean(ApplicationContext context, Class<T> type) {
        try {
            return context.getBeanProvider(type).getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
