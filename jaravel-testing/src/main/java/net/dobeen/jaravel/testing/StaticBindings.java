package net.dobeen.jaravel.testing;

import org.springframework.context.ApplicationContext;

import net.dobeen.jaravel.auth.Auth;
import net.dobeen.jaravel.auth.AuthManager;
import net.dobeen.jaravel.auth.EmailVerification;
import net.dobeen.jaravel.auth.Verification;
import net.dobeen.jaravel.auth.access.Gate;
import net.dobeen.jaravel.auth.access.GateRegistry;
import net.dobeen.jaravel.auth.hash.Hash;
import net.dobeen.jaravel.auth.hash.HashManager;
import net.dobeen.jaravel.auth.passwords.Password;
import net.dobeen.jaravel.auth.passwords.PasswordBroker;
import net.dobeen.jaravel.broadcasting.Broadcast;
import net.dobeen.jaravel.broadcasting.BroadcastManager;
import net.dobeen.jaravel.cache.Cache;
import net.dobeen.jaravel.cache.CacheManager;
import net.dobeen.jaravel.console.Artisan;
import net.dobeen.jaravel.console.CommandRegistry;
import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.core.config.ConfigRepository;
import net.dobeen.jaravel.core.container.Container;
import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.eloquent.Eloquent;
import net.dobeen.jaravel.events.Event;
import net.dobeen.jaravel.events.EventBus;
import net.dobeen.jaravel.mail.Mail;
import net.dobeen.jaravel.mail.MailManager;
import net.dobeen.jaravel.migration.schema.Schema;
import net.dobeen.jaravel.notifications.NotificationManager;
import net.dobeen.jaravel.notifications.Notifications;
import net.dobeen.jaravel.permissions.PermissionStore;
import net.dobeen.jaravel.permissions.Permissions;
import net.dobeen.jaravel.queue.Queue;
import net.dobeen.jaravel.queue.QueueManager;
import net.dobeen.jaravel.storage.FilesystemManager;
import net.dobeen.jaravel.storage.Storage;
import net.dobeen.jaravel.validation.DatabaseRule;

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
        // 时间回到真实时间(docs/14 §4.2)。漏出去的冻结时间会让后面的
        // 测试以「令牌莫名过期 / 莫名不过期」失败,且只在特定执行顺序下
        // 出现 —— 与门面指向错上下文是同一类故障。
        net.dobeen.jaravel.support.Clock.reset();

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

        net.dobeen.jaravel.core.lang.Translator translator =
                bean(context, net.dobeen.jaravel.core.lang.Translator.class);
        if (translator != null) {
            net.dobeen.jaravel.core.lang.Lang.use(translator);
            // locale 回到配置值 —— 上一个测试里的 setLocale("en") 不该漏过来
            translator.setLocale(config == null ? translator.locale()
                    : config.string("app.locale", translator.locale()));
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
            // ★ 顺手退出 fake 模式。QueueManager 是上下文里的单例 bean,
            // 而 fake 状态挂在它身上 —— 上一个测试装的 fake 会留到下一个
            // 测试,症状是「这个测试里任务根本没入队」且毫无线索。
            // 基类的 @BeforeEach 先于子类执行,所以子类自己装的 fake 不受影响。
            queues.stopFaking();
        }

        // @csrf / @error / @old 的数据源(starter 的 SessionViewRequestState)。
        // 它是 JVM 全局静态,所以与其它门面同样要重绑到当前上下文
        net.dobeen.jaravel.view.ViewRequestState.Source viewState =
                bean(context, net.dobeen.jaravel.view.ViewRequestState.Source.class);
        if (viewState != null) {
            net.dobeen.jaravel.view.ViewRequestState.use(viewState);
        }

        CacheManager caches = bean(context, CacheManager.class);
        if (caches != null) {
            Cache.use(caches);
            // 与队列同理:fake 状态挂在上下文的单例 manager 上,
            // 留到下一个测试的症状是「缓存怎么是空的 / 怎么没命中」
            caches.stopFaking();
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
