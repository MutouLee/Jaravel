package io.jaravel.spring.commands;

import java.util.function.Supplier;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.queue.FailedJobStore;

/**
 * {@code jaravel queue:forget <id>}(docs/10 §2)—— 删掉一条失败记录。
 *
 * <p>用途:确认过「这条不用重试了」(数据已经手工补过、或者那是一次
 * 压测留下的垃圾),又不想 {@code queue:flush} 把别的记录一起删掉。
 *
 * <h2>删之前先把它打出来</h2>
 * <p>id 是从 {@code queue:failed} 那张表上抄的,抄错一位就删错一条 ——
 * 而失败记录是重试的唯一依据,删了就回不去。所以先显示「要删的是哪个任务、
 * 哪个连接、什么原因」,再删。
 */
@Command(name = "queue:forget", description = "删掉一条失败任务的记录")
public final class QueueForgetCommand extends RuntimeCommand {

    @Argument(description = "失败记录的 id(从 queue:failed 里看)")
    String id;

    private final Supplier<FailedJobStore> failedJobs;

    public QueueForgetCommand(Supplier<FailedJobStore> failedJobs) {
        this.failedJobs = failedJobs;
    }

    @Override
    public int handle() {
        String target = requireId();
        FailedJobStore store = failedJobs.get();

        var failed = store.find(target);
        if (failed.isEmpty()) {
            error("没有 id 为 " + target + " 的失败记录。用 queue:failed 看现有的。");
            return 1;
        }

        var job = failed.get();
        line("将删掉:" + job.displayName() + "(" + job.connection() + ":" + job.queue() + ")");
        if (!job.reason().isBlank()) line("  原因:" + job.reason());

        if (!confirmInProduction("删掉这条失败记录(删了就不能重试了)")) return 1;

        // forget 返回 false = 这条记录在我们读出来之后被别人删了。
        // 那不是错误(目标状态已经达成),但要如实说 —— 否则一个
        // 「两个人同时在清」的场景会显示成「删成功了」
        boolean removed = store.forget(target);
        if (removed) {
            success("已删掉 #" + target + "。");
        } else {
            comment("#" + target + " 已经不在了(可能刚被别人删掉)。");
        }
        return 0;
    }

    /**
     * ★ 不校验 id 的形状 —— 它是存储给出的**句柄**:
     * database / redis 是自增编号("3"),KV 型存储(dynamodb)是任务 uuid。
     * 在这里要求「必须是数字」会让 uuid 句柄得到一条指着用户输入、
     * 而原因在存储选型的报错。
     */
    private String requireId() {
        if (id == null || id.isBlank()) {
            throw new ConsoleException("""
                    要给一个失败记录的 id。
                      id 是 queue:failed 的第一列。
                      要全删用 queue:flush,要重试用 queue:retry <id|all>。""");
        }

        // ★ "all" 单独挡一下。它是一个会犯的错 —— queue:retry 收 all,
        // 而这条不收。不挡的话用户得到的是「没有 id 为 all 的失败记录」,
        // 那句话正确但没用:他要的是「全删」,而那条命令叫 queue:flush
        if (id.trim().equalsIgnoreCase("all")) {
            throw new ConsoleException("""
                    queue:forget 不收 all —— 要全删用 queue:flush。
                      (queue:retry 收 all,两条命令在这一点上不同。)""");
        }
        return id.trim();
    }
}
