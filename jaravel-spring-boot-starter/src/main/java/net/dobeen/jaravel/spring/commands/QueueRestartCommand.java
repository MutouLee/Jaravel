package net.dobeen.jaravel.spring.commands;

import java.util.function.Supplier;

import net.dobeen.jaravel.cache.Repository;
import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.queue.RestartSignal;

/**
 * {@code jaravel queue:restart}(docs/10 §2、docs/13 §2.4)。
 *
 * <p>往缓存里写一个时间戳。盯着这个信号的 worker 在**处理完手上的任务之后**
 * 退出 —— 不打断正在跑的任务(理由见 {@link RestartSignal})。
 *
 * <h2>★ 输出里必须说清「谁把它们拉起来」</h2>
 * <p>这条命令只让 worker 退出。重新启动是进程管理器的事
 * (systemd {@code Restart=always} / k8s / supervisor)。
 * 没有它的话这条命令的效果是「队列停了」,而监控上看到的只是积压在涨 ——
 * 所以每次执行都把这句话打出来,而不是只写在文档里。
 */
@Command(name = "queue:restart", description = "让所有 worker 做完手上的任务后优雅退出")
public final class QueueRestartCommand extends RuntimeCommand {

    private final Supplier<Repository> cache;

    public QueueRestartCommand(Supplier<Repository> cache) {
        this.cache = cache;
    }

    @Override
    public int handle() {
        Repository store = cache.get();
        var signal = new RestartSignal(store);

        if (!confirmInProduction("让所有 worker 退出(需要进程管理器把它们拉起来)")) {
            return 1;
        }

        var at = signal.broadcast();
        success("重启信号已发出(" + at + ")。");
        line("  盯着这个信号的 worker 会在处理完手上的任务之后退出。");
        line("  ⚠ 把它们**重新拉起来**是进程管理器的事(systemd / k8s / supervisor)。");

        warnIfNotShared(store);
        return 0;
    }

    /**
     * 缓存不共享时警告。
     *
     * <p>caffeine / array 在进程内,信号传不到 worker 所在的进程 ——
     * 而那时这条命令会「成功」且什么都不会发生,是最难查的一类结果。
     */
    private void warnIfNotShared(Repository store) {
        String driver = store.store().name();
        if (driver.equals("redis") || driver.equals("database")) return;

        warn("""
                当前缓存驱动是 %s —— 它在**进程内**,信号传不到别的进程。
                  如果 worker 是独立部署的,这条命令对它们没有任何效果。
                  多实例部署请把 cache.default 配成 redis / database。"""
                .formatted(driver));
    }
}
