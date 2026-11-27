package net.dobeen.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import net.dobeen.jaravel.console.Argument;
import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.console.ConsoleException;
import net.dobeen.jaravel.queue.FailedJobStore;
import net.dobeen.jaravel.queue.JobSerializer;
import net.dobeen.jaravel.queue.QueueManager;

/**
 * {@code jaravel queue:retry <id|all>}(docs/13 §2.4)。
 *
 * <p>从失败记录里的原始信封(payload)还原任务,**放回它原来的那个连接**
 * —— 不是默认连接。一个 redis 上失败的任务被重试到 database 上,
 * 症状是「重试了但没反应」,而两张表各自看起来都正常。
 */
@Command(name = "queue:retry", description = "把失败的任务放回队列")
public final class QueueRetryCommand extends RuntimeCommand {

    @Argument(description = "失败记录的 id,或者 all")
    String target;

    private final Supplier<QueueManager> queues;
    private final Supplier<FailedJobStore> failedJobs;
    private final Supplier<JobSerializer> serializer;

    public QueueRetryCommand(Supplier<QueueManager> queues,
                             Supplier<FailedJobStore> failedJobs,
                             Supplier<JobSerializer> serializer) {
        this.queues = queues;
        this.failedJobs = failedJobs;
        this.serializer = serializer;
    }

    @Override
    public int handle() {
        FailedJobStore store = failedJobs.get();
        List<FailedJobStore.FailedJob> targets = resolve(store);

        if (targets.isEmpty()) {
            comment("没有可重试的失败任务。");
            return 0;
        }
        if (!confirmInProduction("把 " + targets.size() + " 个失败任务放回队列")) return 1;

        int pushed = 0;
        for (var failed : targets) {
            var envelope = serializer.get().decode(failed.payload());
            queues.get().connection(failed.connection()).push(envelope);
            // 放回之后删掉记录:留着的话下一次 retry all 会再放一遍,
            // 而那是「同一个任务跑了两遍」最常见的来源
            store.forget(failed.id());
            success("已放回 " + failed.displayName() + "(" + failed.connection() + ")");
            pushed++;
        }

        newLine();
        line("放回 " + pushed + " 个。要让它们真的执行,记得有 worker 在跑。");
        return 0;
    }

    private List<FailedJobStore.FailedJob> resolve(FailedJobStore store) {
        if ("all".equalsIgnoreCase(target.trim())) return store.all();

        // ★ 不解析 id 的形状。它是存储给出的**句柄**:
        // database / redis 是自增编号("3"),KV 型存储(dynamodb)是任务 uuid。
        // 第一版在这里 Long.parseLong,于是 uuid 句柄会得到
        // 「既不是 id 也不是 all」—— 一条指着用户输入、而原因在存储选型的报错
        String id = target.trim();
        if (id.isEmpty()) {
            throw new ConsoleException("""
                    要给一个 id,或者 all。

                      id 来自 queue:failed 的第一列。""");
        }
        return store.find(id)
                .map(List::of)
                .orElseThrow(() -> new ConsoleException(
                        "没有 id 为 " + id + " 的失败记录 —— queue:failed 看一下现有的。"));
    }
}
