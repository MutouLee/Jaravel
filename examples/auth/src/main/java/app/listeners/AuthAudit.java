package app.listeners;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.jaravel.auth.events.AuthEvents;
import io.jaravel.events.Listener;
import org.springframework.stereotype.Component;

/**
 * 认证审计 —— 一个真实的监听器。
 *
 * <p>它同时是对三件事的端到端验证:
 * <ul>
 *   <li>APT 真的为 {@code @Listener} 生成了绑定;</li>
 *   <li>starter 真的从 {@code listeners.idx} 把它装上了;</li>
 *   <li>框架真的在那些调用点发出了事件。</li>
 * </ul>
 *
 * <p>三者缺任何一个,这个类都会「存在但从不触发」—— 而那在没有
 * 断言的情况下看不出来。所以 {@code AuthFeatureTest} 里有对它的断言。
 *
 * <h2>为什么同时标 @Component 与 @Listener</h2>
 * <p>{@code @Listener} 告诉 APT「为这个类生成绑定」;
 * {@code @Component} 让容器能建出它(监听器的依赖从构造器注入)。
 * 两者职责不同,所以都要。
 *
 * <p>只标 {@code @Listener} 的后果是启动时报
 * 「无法实例化监听器 …… 给它加 @Component」—— 那条消息刻意说得很具体。
 */
@Component
@Listener
public class AuthAudit {

    private final List<String> entries = new CopyOnWriteArrayList<>();

    public void handle(AuthEvents.Login e) {
        entries.add("login:" + e.user().getAuthIdentifier() + ":remember=" + e.remember());
    }

    public void handle(AuthEvents.Logout e) {
        // user 可能为 null —— 一个已过期的会话点「退出」是合法的
        entries.add("logout:" + (e.user() == null ? "anonymous"
                : e.user().getAuthIdentifier()));
    }

    public void handle(AuthEvents.Failed e) {
        // ⚠ 这里**拿不到密码** —— 事件里没有它。
        // 这正是那个设计的意义:一个把事件写进日志的监听器
        // 不可能意外泄露明文密码
        entries.add("failed:" + e.identifier() + ":exists=" + e.userExists()
                + ":keys=" + e.attemptedKeys().stream().sorted().toList());
    }

    public void handle(AuthEvents.Registered e) {
        entries.add("registered:" + e.user().getAuthIdentifier());
    }

    public void handle(AuthEvents.Verified e) {
        entries.add("verified:" + e.user().getAuthIdentifier());
    }

    public void handle(AuthEvents.PasswordReset e) {
        entries.add("password-reset:" + e.user().getAuthIdentifier());
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    public void reset() {
        entries.clear();
    }
}
