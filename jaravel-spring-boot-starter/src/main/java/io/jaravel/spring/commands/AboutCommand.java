package io.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.routing.RouteCollection;

/**
 * {@code jaravel about}(docs/13 §2.5)—— 环境与驱动总览。
 *
 * <p>它的用途是「排查之前先确认事实」:**配置解析之后**的实际值是什么。
 * 一半的「为什么不生效」最后都是 .env 没被读到、或者被另一个配置源覆盖了,
 * 而那件事只有解析完才看得出来 —— 看 yml 文件是看不出来的。
 */
@Command(name = "about", description = "环境、驱动、配置总览")
public final class AboutCommand extends ConsoleCommand {

    private final ConfigRepository config;
    private final Supplier<RouteCollection> routes;

    public AboutCommand(ConfigRepository config, Supplier<RouteCollection> routes) {
        this.config = config;
        this.routes = routes;
    }

    @Override
    public int handle() {
        comment("环境");
        twoColumnDetail("应用名", config.string("app.name", "(未配置)"));
        twoColumnDetail("环境", Application.environment());
        twoColumnDetail("调试模式", String.valueOf(Application.isDebug()));
        twoColumnDetail("Java", System.getProperty("java.version")
                + " / " + System.getProperty("java.vm.name"));
        twoColumnDetail("虚拟线程", String.valueOf(Thread.currentThread().isVirtual()
                ? "是(当前线程)" : "当前线程不是虚拟线程"));

        newLine();
        comment("驱动");
        twoColumnDetail("数据库", config.string("database.default", "(未配置)"));
        twoColumnDetail("缓存", config.string("cache.default", "(未配置)"));
        twoColumnDetail("会话", config.string("session.driver", "(未配置)"));
        twoColumnDetail("队列", config.string("queue.default", "(未配置)"));
        twoColumnDetail("邮件", config.string("mail.default", "(未配置)"));
        twoColumnDetail("存储", config.string("filesystems.default", "(未配置)"));

        newLine();
        comment("路由");
        twoColumnDetail("已注册", routes.get().size() + " 条");

        newLine();
        line("某一项配置解析后的完整值:config:show <键>(还没实现)");
        return 0;
    }
}
