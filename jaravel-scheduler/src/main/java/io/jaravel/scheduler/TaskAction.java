package io.jaravel.scheduler;

import java.util.List;

/**
 * 一个计划任务「做什么」。
 *
 * <p>四种形态对应 docs/10 §3.1 的四个入口:
 * <ul>
 *   <li>{@link Job} —— {@code s.job(PruneOldLogs.class)},投进队列;</li>
 *   <li>{@link Command} —— {@code s.command("cache:clear")},跑一条 CLI 命令;</li>
 *   <li>{@link Call} —— {@code s.call(() -> metrics.flush())},直接执行;</li>
 *   <li>{@link Exec} —— {@code s.exec("/usr/bin/backup.sh")},跑一个外部进程。</li>
 * </ul>
 *
 * <h2>job 与 call 的区别值得想清楚</h2>
 * <p>{@code s.job(X.class)} **投进队列**,不在调度线程里执行。
 * 所以:
 * <ul>
 *   <li>它有队列的全套能力 —— 重试、超时、failed_jobs;</li>
 *   <li>它**不保证在这一分钟内跑完**,只保证入队;</li>
 *   <li>{@code onSuccess} 钩子看到的是「投递成功」而不是「执行成功」。</li>
 * </ul>
 *
 * <p>最后一条是真实的陷阱,所以 {@link Job#name()} 的返回值里带了
 * {@code (queued)},让 {@code schedule:list} 里看得出来。
 *
 * <p>要「跑完才算成功」就用 {@code s.call(() -> ...)} —— 它在调度线程
 * (或后台虚拟线程)里同步执行,异常会被钩子看到。
 */
public sealed interface TaskAction {

    /** 执行。抛异常表示失败 */
    void run() throws Exception;

    /** 用于锁的 key 与日志 —— 必须稳定,不能每次调用都变 */
    String name();

    /**
     * 投一个队列任务。
     *
     * <p>{@code dispatcher} 由 starter 注入 —— 调度模块不依赖
     * {@code jaravel-queue},理由见 {@code pom.xml} 里的注释。
     */
    record Job(Class<?> jobType, JobDispatcher dispatcher) implements TaskAction {
        @Override
        public void run() {
            dispatcher.dispatch(jobType);
        }

        @Override
        public String name() {
            // (queued) 是刻意带上的 —— 它提醒「成功只表示投递成功」
            return jobType.getSimpleName() + "(queued)";
        }
    }

    /** 跑一条 Jaravel CLI 命令 */
    record Command(String command, CommandRunner runner) implements TaskAction {
        @Override
        public void run() throws Exception {
            runner.run(command);
        }

        @Override
        public String name() {
            return "command:" + command;
        }
    }

    /**
     * 直接执行一段代码。
     *
     * @param label 名字。**必须显式给** —— lambda 没有有意义的名字,
     *              而锁的 key 与日志都要用它。
     *              自动生成的话会是 {@code Schedule$$Lambda$17},
     *              那个数字会随代码改动而变,于是锁的 key 跟着变,
     *              {@code withoutOverlapping} 在一次发布后就挡不住了
     */
    record Call(String label, ThrowingRunnable body) implements TaskAction {
        @Override
        public void run() throws Exception {
            body.run();
        }

        @Override
        public String name() {
            return label;
        }
    }

    /**
     * 跑一个外部进程。
     *
     * <p>输出会被收集进 {@link TaskOutcome#output()},好让
     * {@code onFailure} 钩子能把它发出去 —— 一个备份脚本失败时,
     * 它的 stderr 就是全部的诊断信息。
     */
    record Exec(List<String> command) implements TaskAction {
        public Exec(String... parts) {
            this(List.of(parts));
        }

        @Override
        public void run() {
            // 真正的执行在 Scheduler 里 —— 它要收集输出与退出码。
            // 这里抛错而不是重复实现一遍:两份实现注定会分叉
            throw new UnsupportedOperationException(
                    "Exec 由 Scheduler 直接执行以收集输出,不走 TaskAction.run()");
        }

        @Override
        public String name() {
            return "exec:" + String.join(" ", command);
        }
    }

    /** 投队列任务的通道。由 starter 注入 */
    interface JobDispatcher {
        void dispatch(Class<?> jobType);
    }

    /** 跑 CLI 命令的通道。由 starter 注入 */
    interface CommandRunner {
        void run(String command) throws Exception;
    }

    /** 能抛检查异常的 Runnable —— 任务体里常有 IO */
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
