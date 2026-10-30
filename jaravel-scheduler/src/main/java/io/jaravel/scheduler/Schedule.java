package io.jaravel.scheduler;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划任务的登记处(docs/10 §3.1)。
 *
 * <pre>{@code
 * @Component
 * public class ConsoleKernel implements ScheduleDefinition {
 *     @Override
 *     public void schedule(Schedule s) {
 *         s.job(PruneOldLogs.class).dailyAt("03:00");
 *         s.command("cache:clear").hourly();
 *         s.call("刷新指标", () -> metrics.flush()).everyFiveMinutes();
 *         s.exec("/usr/bin/backup.sh").dailyAt("04:30");
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么 {@code call} 要一个名字</h2>
 * <p>Laravel 的 {@code s.call(closure)} 不要名字。Jaravel 要,
 * 因为锁的 key 与日志都得用它,而 Java lambda 的名字是
 * {@code Kernel$$Lambda$17} —— 那个数字**会随代码改动而变**。
 *
 * <p>后果很具体:{@code withoutOverlapping} 的锁 key 跟着变,
 * 于是一次发布之后它挡不住上一轮还在跑的那个实例。
 * 一个要求显式命名的 API 比一个在发布时静默失效的 API 好。
 */
public final class Schedule {

    private final List<ScheduledTask> tasks = new ArrayList<>();

    /** 投队列任务的通道。null = 没装(应用没引队列) */
    private final TaskAction.JobDispatcher jobDispatcher;

    /** 跑 CLI 命令的通道 */
    private final TaskAction.CommandRunner commandRunner;

    /**
     * 整个 schedule 的默认时区。
     *
     * <p>存在的理由很实际:{@link ScheduledTask#timezone} 的默认值是
     * **JVM 时区**,而那会随部署环境变(本地 UTC+8、容器里 UTC)。
     * 没有这个 schedule 级的默认值的话,正确的写法是给**每一个**任务
     * 都加 `.timezone(...)` —— 而漏掉一个的后果是那个任务的运行时刻
     * 悄悄差了几小时。
     *
     * <p>写这个类的测试时我自己立刻踩到了这件事:一组 `between(...)`
     * 的断言全部失败,因为测试用 UTC 构造时刻而任务用的是本机时区。
     * 一个让作者当场踩到的默认值,用户也会踩到。
     *
     * <p>仍然保留「默认 JVM 时区」而不是改成 UTC:业务说的「每天 03:00
     * 做日终结算」指的是本地时间,默认 UTC 会让那个任务在生产环境
     * 悄悄改时刻。这里的解法是让**显式声明变容易**,不是换一个
     * 同样会错的默认值。
     */
    private ZoneId defaultTimezone;

    public Schedule() {
        this(null, null);
    }

    public Schedule(TaskAction.JobDispatcher jobDispatcher,
                    TaskAction.CommandRunner commandRunner) {
        this.jobDispatcher = jobDispatcher;
        this.commandRunner = commandRunner;
    }

    /**
     * 给这个 schedule 里**之后登记的**任务设默认时区。
     *
     * <pre>{@code
     * s.timezone("Asia/Shanghai");      // 放在最前面
     * s.job(DailySettlement.class).dailyAt("03:00");
     * }</pre>
     *
     * <p>「之后登记的」是刻意的:它让这一行的作用范围从代码的位置
     * 就能看出来。作用于已登记任务的版本会让「把这行往下挪一行」
     * 悄悄改变一批任务的行为。
     *
     * <p>单个任务的 {@code .timezone(...)} 仍然覆盖它。
     */
    public Schedule timezone(String zone) {
        return timezone(ZoneId.of(zone));
    }

    public Schedule timezone(ZoneId zone) {
        this.defaultTimezone = zone;
        return this;
    }

    public ZoneId defaultTimezone() {
        return defaultTimezone;
    }

    /**
     * 定时把一个队列任务投进队列。
     *
     * <p>⚠ 它**只保证入队**,不保证在这一分钟内跑完 ——
     * 见 {@link TaskAction.Job} 的注释。要「跑完才算成功」用
     * {@link #call}。
     */
    public ScheduledTask job(Class<?> jobType) {
        if (jobDispatcher == null) {
            throw new IllegalStateException("""
                    s.job(%s) 需要队列,但调度器没有队列通道。
                      原因通常是 jaravel-queue 不在 classpath 上。
                      修复:引入 jaravel-queue,或者改用
                            s.call("名字", () -> ...) 直接执行。
                      这里报错而不是退回同步执行 —— 一个本该进队列的任务
                      在调度线程里跑,会让调度器被它卡住,
                      而后果是**其它所有任务都延迟**。"""
                    .formatted(jobType.getSimpleName()));
        }
        return add(new TaskAction.Job(jobType, jobDispatcher));
    }

    /** 定时跑一条 Jaravel CLI 命令 */
    public ScheduledTask command(String command) {
        if (commandRunner == null) {
            throw new IllegalStateException("""
                    s.command("%s") 需要 CLI,但调度器没有命令通道。
                      v0.4 还没有 jaravel-console 模块 —— 这个入口要到
                      CLI 实现之后才可用(v0.5)。
                      现在可以用 s.call("名字", () -> ...) 直接调那段逻辑。"""
                    .formatted(command));
        }
        return add(new TaskAction.Command(command, commandRunner));
    }

    /**
     * 定时执行一段代码。
     *
     * @param label 任务的名字,**必须给** —— 见类注释
     */
    public ScheduledTask call(String label, TaskAction.ThrowingRunnable body) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("""
                    s.call(...) 必须给一个名字。
                      锁的 key 与日志都用它,而 lambda 的自动名字
                      (Kernel$$Lambda$17)会随代码改动而变 ——
                      那会让 withoutOverlapping 在一次发布后失效。""");
        }
        return add(new TaskAction.Call(label, body));
    }

    /** 定时跑一个外部进程 */
    public ScheduledTask exec(String... command) {
        if (command.length == 0) {
            throw new IllegalArgumentException("s.exec(...) 至少要一个命令。");
        }
        return add(new TaskAction.Exec(List.of(command)));
    }

    private ScheduledTask add(TaskAction action) {
        var task = new ScheduledTask(action);
        if (defaultTimezone != null) task.timezone(defaultTimezone);
        tasks.add(task);
        return task;
    }

    public List<ScheduledTask> tasks() {
        return List.copyOf(tasks);
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * 名字重复的任务。
     *
     * <p>重复的名字意味着**共用一个互斥锁 key** —— 于是两个无关的任务
     * 会互相挡住,而那个症状(「这个任务有时不跑」)极难归因。
     *
     * <p>调度器在启动时检查它并报错。
     */
    public List<String> duplicateNames() {
        var seen = new java.util.HashSet<String>();
        var duplicates = new java.util.LinkedHashSet<String>();
        for (ScheduledTask task : tasks) {
            if (!seen.add(task.action().name())) {
                duplicates.add(task.action().name());
            }
        }
        return List.copyOf(duplicates);
    }

    /** 应用实现它来登记任务 */
    public interface ScheduleDefinition {
        void schedule(Schedule s);
    }
}
