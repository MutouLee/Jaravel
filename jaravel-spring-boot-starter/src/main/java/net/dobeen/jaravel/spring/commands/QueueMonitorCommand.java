package net.dobeen.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.dobeen.jaravel.console.Argument;
import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.console.ConsoleException;
import net.dobeen.jaravel.console.Option;
import net.dobeen.jaravel.queue.QueueManager;

/**
 * {@code jaravel queue:monitor redis:default,redis:mail --max=1000}(docs/10 §2)。
 *
 * <p>看队列的**长度**,超过阈值就以退出码 1 结束。用途是接到调度器上:
 *
 * <pre>{@code
 * schedule.command("queue:monitor redis:default --max=1000").everyFiveMinutes();
 * }</pre>
 *
 * <h2>★ 为什么用退出码而不是只打一行字</h2>
 * <p>「积压了」这件事要能触发告警,而能接告警的东西(调度器的失败通知、
 * CI、k8s 的探针、cron 的 MAILTO)认的都是退出码。只打字的命令等于
 * 把判断留给人去看终端,而没人会一直看。
 *
 * <p>所以:正常 0,有队列超阈值 1。两种情况都把表打出来 ——
 * 退出码说「有没有问题」,表说「问题在哪」。
 *
 * <h2>长度不等于「有没有在干活」</h2>
 * <p>一个空队列可能意味着 worker 很健康,也可能意味着**没有 worker**
 * (没人取,但也没人投)。所以这条命令只回答「积压了吗」,
 * 「worker 活着吗」要看 worker 自己的心跳 —— 那是另一件事,
 * 这里不假装覆盖它。
 */
@Command(name = "queue:monitor", description = "检查队列积压,超阈值以退出码 1 结束")
public final class QueueMonitorCommand extends RuntimeCommand {

    @Argument(description = "要看的队列,形如 redis:default,redis:mail;省略连接名用默认连接")
    String targets;

    @Option(names = "--max", description = "阈值:超过这个长度就算积压(默认 1000)")
    int max;

    private final Supplier<QueueManager> queues;

    public QueueMonitorCommand(Supplier<QueueManager> queues) {
        this.queues = queues;
    }

    @Override
    public int handle() {
        QueueManager manager = queues.get();
        int threshold = max > 0 ? max : 1000;

        var rows = new ArrayList<List<?>>();
        var busy = new ArrayList<String>();

        for (String target : parseTargets()) {
            String connection = connectionOf(target, manager);
            String queue = queueOf(target);

            long size = sizeOf(manager, connection, queue);
            boolean over = size > threshold;
            if (over) busy.add(connection + ":" + queue);

            rows.add(List.of(connection + ":" + queue, String.valueOf(size),
                    over ? "积压" : "正常"));
        }

        table(List.of("队列", "长度", "状态"), rows);

        if (busy.isEmpty()) {
            newLine();
            success("都在阈值(" + threshold + ")之内。");
            return 0;
        }

        newLine();
        error("超过阈值(" + threshold + "):" + String.join("、", busy));
        return 1;
    }

    private long sizeOf(QueueManager manager, String connection, String queue) {
        try {
            return manager.connection(connection).size(queue);
        } catch (RuntimeException e) {
            // 连接名写错、redis 连不上 —— 都要指名道姓,不能让一条
            // 监控命令以「0 积压」结束(那是最糟的假阴性)
            throw new ConsoleException("""
                    读不到 %s:%s 的长度:%s
                      已注册的连接:%s"""
                    .formatted(connection, queue, e.getMessage(),
                            manager.registeredConnections()), e);
        }
    }

    private List<String> parseTargets() {
        if (targets == null || targets.isBlank()) return List.of("default");
        return java.util.Arrays.stream(targets.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code redis:mail} → {@code redis};不带冒号就是默认连接 */
    private String connectionOf(String target, QueueManager manager) {
        int colon = target.indexOf(':');
        return colon < 0 ? manager.defaultConnectionName() : target.substring(0, colon);
    }

    private String queueOf(String target) {
        int colon = target.indexOf(':');
        String queue = colon < 0 ? target : target.substring(colon + 1);
        return queue.isBlank() ? "default" : queue;
    }
}
