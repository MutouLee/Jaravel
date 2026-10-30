package io.jaravel.scheduler;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.jaravel.scheduler.events.ScheduleEvents;

/**
 * 应用内置的调度器(docs/10 §3.3)。
 *
 * <pre>{@code
 * var scheduler = new Scheduler(schedule).locks(taskLocks);
 * scheduler.start();          // 后台虚拟线程,每分钟对齐唤醒
 * }</pre>
 *
 * <h2>与 Laravel 的关键差异:不需要外部 cron</h2>
 * <p>Laravel 必须在 crontab 里加 {@code * * * * * php artisan schedule:run},
 * 因为 PHP 没有常驻进程。Jaravel 的应用本身常驻,所以调度器就在进程里。
 *
 * <p>这是一个**真实的优势**,但它带来一个 Laravel 没有的问题:
 * 多实例部署时每个实例都有一个调度器。所以 {@link ScheduledTask#onOneServer()}
 * 不是可选项而是多实例部署的**必需品** —— 而它依赖共享的锁存储。
 * {@link SchedulerConfigCheck} 在启动时检查这个组合。
 *
 * <h2>对齐到分钟边界</h2>
 * <p>调度线程不是「每 60 秒醒一次」,而是**醒在每分钟的第 0 秒附近**。
 *
 * <p>差别很重要:固定 60 秒间隔会累积漂移(每次唤醒有几毫秒误差),
 * 几小时后就会落在上一分钟的第 59 秒上 —— 那时一个
 * {@code dailyAt("03:00")} 的任务会在 02:59 判定一次、03:00 判定一次,
 * 要么跑两遍要么一遍不跑,取决于漂移的方向。
 *
 * <p>所以每次都重新算「到下一分钟的第 0 秒还有多久」。
 *
 * <h2>同一分钟内不重复执行</h2>
 * <p>即便被唤醒两次,{@link #lastRunMinute} 保证一个任务在同一分钟里
 * 只跑一次。这是本地的最后一道闸 —— 它不跨进程,跨进程靠
 * {@code onOneServer}。
 */
public final class Scheduler {

    private final Schedule schedule;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private Thread thread;

    /** 任务名 → 它上一次运行的那一分钟。同分钟内去重 */
    private final ConcurrentHashMap<String, String> lastRunMinute = new ConcurrentHashMap<>();

    /** 本进程内正在跑的任务(withoutOverlapping 的本地部分) */
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private TaskLocks locks = TaskLocks.NONE;
    private Pinger pinger = Pinger.NONE;

    private final AtomicLong ran = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public Scheduler(Schedule schedule) {
        this.schedule = schedule;

        var duplicates = schedule.duplicateNames();
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("""
                    有重名的计划任务:%s
                      重名意味着它们**共用一个互斥锁 key**,于是两个无关的任务
                      会互相挡住,而症状是「这个任务有时不跑」—— 极难归因。
                      给 s.call(...) 换一个名字,或者给重复的任务加
                      .description(...) 之外的区分(锁 key 用的是动作名)。"""
                    .formatted(duplicates));
        }
    }

    /** 装上分布式锁(onOneServer 用)。不装时 onOneServer 退化为本地互斥 */
    public Scheduler locks(TaskLocks taskLocks) {
        this.locks = taskLocks == null ? TaskLocks.NONE : taskLocks;
        return this;
    }

    /** 装上 HTTP ping 的实现(pingOnSuccess / pingOnFailure 用) */
    public Scheduler pinger(Pinger implementation) {
        this.pinger = implementation == null ? Pinger.NONE : implementation;
        return this;
    }

    public Scheduler addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    // ================= 生命周期 =================

    /** 后台起一个虚拟线程,每分钟对齐唤醒 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("调度器已经在跑了");
        }
        stopping.set(false);

        thread = Thread.ofVirtual().name("jaravel-scheduler").start(this::loop);
    }

    public void stop() {
        stopping.set(true);
        Thread current = thread;
        if (current != null) current.interrupt();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        try {
            while (!stopping.get()) {
                sleepUntilNextMinute();
                if (stopping.get()) break;

                try {
                    runDue(ZonedDateTime.now());
                } catch (RuntimeException e) {
                    // 一个任务的问题不该停掉调度器 —— 停了的话**所有**任务
                    // 都不再运行,而那比单个任务失败严重得多
                    System.err.printf("[jaravel] 调度器这一轮出错:%s%n", e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 睡到下一分钟的第 0 秒。
     *
     * <p>每次重新计算,**不用固定 60 秒** —— 见类注释里关于漂移的说明。
     *
     * <p>多睡 50 毫秒:正好落在第 0.000 秒时,时钟的毫秒抖动可能让
     * {@code now()} 读到上一分钟的 59.999 秒,于是这一分钟被整个跳过。
     * 那种丢失是不可见的 —— 没有日志、没有异常。
     */
    private void sleepUntilNextMinute() {
        var now = ZonedDateTime.now();
        var nextMinute = now.withSecond(0).withNano(0).plusMinutes(1);
        long millis = Duration.between(now, nextMinute).toMillis() + 50;

        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping.set(true);
        }
    }

    // ================= 执行 =================

    /**
     * 跑这一刻该跑的任务。
     *
     * <p>**公开**:外部触发模式({@code jaravel schedule:run},
     * 用于 Kubernetes CronJob)调它。
     *
     * @return 这一轮真的执行了的任务
     */
    public List<ScheduledTask> runDue(ZonedDateTime now) {
        var executed = new ArrayList<ScheduledTask>();
        String minute = minuteKey(now);

        for (ScheduledTask task : schedule.tasks()) {
            if (!task.cronMatches(now)) continue;

            // 同一分钟内不重复 —— 即便被唤醒两次
            String previous = lastRunMinute.get(task.action().name());
            if (minute.equals(previous)) continue;

            var reason = task.skipReason(now);
            if (reason.isPresent()) {
                skip(task, reason.get());
                continue;
            }

            lastRunMinute.put(task.action().name(), minute);
            if (dispatch(task, now)) executed.add(task);
        }
        return executed;
    }

    private static String minuteKey(ZonedDateTime now) {
        return now.withSecond(0).withNano(0).toInstant().toString();
    }

    /**
     * 执行一个任务(含互斥与多实例判定)。
     *
     * @return 真的跑了吗
     */
    private boolean dispatch(ScheduledTask task, ZonedDateTime now) {
        // 本地互斥:上一轮还在跑
        if (task.preventsOverlap() && !inFlight.add(task.action().name())) {
            skip(task, "上一轮还在运行(withoutOverlapping)");
            return false;
        }

        // 多实例去重
        TaskLocks.Handle handle = null;
        if (task.runsOnOneServer()) {
            handle = locks.acquire(task.mutexKey(), overlapTtlOrDefault(task));
            if (handle == null) {
                inFlight.remove(task.action().name());
                skip(task, "另一个实例抢到了锁(onOneServer)");
                return false;
            }
        }

        var lockHandle = handle;
        Runnable body = () -> {
            try {
                execute(task, now);
            } finally {
                // 顺序:先放锁再清本地标记。反过来的话本地标记已清、
                // 锁还在,此时同进程的下一轮会进到 acquire 然后被自己挡住,
                // 而那次跳过的理由会显示成「另一个实例抢到了锁」——
                // 一个误导性的诊断
                if (lockHandle != null) lockHandle.release();
                inFlight.remove(task.action().name());
            }
        };

        if (task.runsInBackground()) {
            Thread.ofVirtual().name("jaravel-schedule-" + task.action().name()).start(body);
        } else {
            body.run();
        }
        return true;
    }

    /**
     * 多实例锁的 TTL。
     *
     * <p>没配 {@code withoutOverlapping} 时用一个**保守的默认值**而不是
     * 永久锁:持锁实例崩掉时,永久锁会让这个任务在所有实例上
     * 永远不再运行 —— 那是最糟的失败模式。
     */
    private Duration overlapTtlOrDefault(ScheduledTask task) {
        return task.overlapTtl() != null ? task.overlapTtl() : Duration.ofMinutes(5);
    }

    private void execute(ScheduledTask task, ZonedDateTime now) {
        listeners.forEach(l -> l.onStarting(task));
        io.jaravel.events.Event.tryDispatchIfListening(
                ScheduleEvents.TaskStarting.class,
                () -> new ScheduleEvents.TaskStarting(task.label(), task.cronExpression().expression()));

        long startNanos = System.nanoTime();
        runHooks(task.before(), task, "before");

        String output = "";
        int exitCode = 0;
        Throwable failure = null;

        try {
            if (task.action() instanceof TaskAction.Exec exec) {
                var result = runProcess(exec);
                output = result.output();
                exitCode = result.exitCode();
                if (exitCode != 0) {
                    failure = new TaskFailedException(
                            "外部命令退出码 %d:%s".formatted(exitCode, exec.name()));
                }
            } else {
                task.action().run();
            }
        } catch (Throwable t) {
            failure = t;
        }

        var duration = Duration.ofNanos(System.nanoTime() - startNanos);
        var outcome = failure == null
                ? TaskOutcome.success(task, now, duration, output)
                : TaskOutcome.failure(task, now, duration, output, exitCode, failure);

        // after 钩子**不管成败都跑**,而且在 onSuccess/onFailure 之前 ——
        // 它的语义是「收尾」(关文件、放连接),那些事无论成败都要做,
        // 而且要在「通知外部」之前做完
        runHooks(task.after(), task, "after");

        if (outcome.succeeded()) {
            ran.incrementAndGet();
            runOutcomeHooks(task.onSuccessHooks(), outcome, task, "onSuccess");
            ping(task.pingSuccessUrl(), task);
        } else {
            failed.incrementAndGet();
            runOutcomeHooks(task.onFailureHooks(), outcome, task, "onFailure");
            ping(task.pingFailureUrl(), task);

            System.err.printf("[jaravel] 计划任务失败:%s%n", outcome.summary());
        }

        listeners.forEach(l -> l.onFinished(outcome));
        io.jaravel.events.Event.tryDispatchIfListening(
                ScheduleEvents.TaskFinished.class,
                () -> new ScheduleEvents.TaskFinished(
                        task.label(), duration, outcome.succeeded(),
                        outcome.failure() == null ? null : outcome.failure().toString()));
    }

    private void skip(ScheduledTask task, String reason) {
        skipped.incrementAndGet();
        listeners.forEach(l -> l.onSkipped(task, reason));
        io.jaravel.events.Event.tryDispatchIfListening(
                ScheduleEvents.TaskSkipped.class,
                () -> new ScheduleEvents.TaskSkipped(task.label(), reason));
    }

    /**
     * 跑钩子。
     *
     * <p>钩子抛异常时**只记录,不影响任务判定** —— 一个
     * {@code before(() -> log.info(...))} 里的 NPE 不该让任务被判成失败。
     *
     * <p>反过来(让钩子的异常传播)的后果更糟:它会让一个成功的任务
     * 被记成失败并触发告警,而告警里的异常指向日志代码,
     * 与任务本身毫无关系。
     */
    private void runHooks(List<Runnable> hooks, ScheduledTask task, String phase) {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                System.err.printf("[jaravel] 计划任务 %s 的 %s 钩子抛了异常:%s%n",
                        task.label(), phase, e);
            }
        }
    }

    private void runOutcomeHooks(List<java.util.function.Consumer<TaskOutcome>> hooks,
                                 TaskOutcome outcome, ScheduledTask task, String phase) {
        for (var hook : hooks) {
            try {
                hook.accept(outcome);
            } catch (RuntimeException e) {
                System.err.printf("[jaravel] 计划任务 %s 的 %s 钩子抛了异常:%s%n",
                        task.label(), phase, e);
            }
        }
    }

    private void ping(String url, ScheduledTask task) {
        if (url == null) return;
        try {
            pinger.ping(url);
        } catch (RuntimeException e) {
            // ping 失败不该影响任务判定。但要吵 —— ping 的用途正是
            // 「发现任务没跑」,而一个静默失败的 ping 会让外部监控
            // 误报「任务没跑」
            System.err.printf("[jaravel] 计划任务 %s 的心跳 ping 失败(%s):%s%n",
                    task.label(), url, e.getMessage());
        }
    }

    /**
     * 跑一个外部进程并收集输出。
     *
     * <p>**合并 stderr 到 stdout**:一个失败的脚本的诊断信息通常在
     * stderr 里,分开收集意味着 {@code onFailure} 钩子要处理两个流,
     * 而实践中大家只看一个 —— 于是另一个里的信息丢了。
     */
    private ProcessResult runProcess(TaskAction.Exec exec) throws Exception {
        var builder = new ProcessBuilder(exec.command());
        builder.redirectErrorStream(true);

        Process process = builder.start();

        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }

        // 必须在读完输出之后 waitFor —— 反过来会在输出超过管道缓冲区时死锁
        int exitCode = process.waitFor();
        return new ProcessResult(output, exitCode);
    }

    private record ProcessResult(String output, int exitCode) {
    }

    // ================= 查询 =================

    /**
     * 列出所有任务及下次运行时间({@code schedule:list})。
     *
     * <p>**带上「有约束」的标注**:下次运行时间只按 cron 算,
     * 约束要到那一刻才判。所以对有约束的任务那个时间是**乐观估计** ——
     * 不标出来的话一个 {@code weekdays()} 的任务会在列表里
     * 显示一个周六的时间。
     */
    public List<Entry> list(ZonedDateTime from) {
        var out = new ArrayList<Entry>();
        for (ScheduledTask task : schedule.tasks()) {
            out.add(new Entry(
                    task.label(),
                    task.cronExpression().expression(),
                    task.zone().getId(),
                    task.nextRun(from).orElse(null),
                    task.filterReasons(),
                    task.cronExpression().describeDayLogic()));
        }
        return out;
    }

    /**
     * 立即执行一个任务,**绕过 cron 与约束**({@code schedule:test})。
     *
     * @return 执行结果;没有这个名字的任务时 empty
     */
    public java.util.Optional<ScheduledTask> runNow(String nameOrDescription) {
        for (ScheduledTask task : schedule.tasks()) {
            if (task.action().name().equals(nameOrDescription)
                    || task.label().equals(nameOrDescription)) {
                execute(task, ZonedDateTime.now());
                return java.util.Optional.of(task);
            }
        }
        return java.util.Optional.empty();
    }

    /** 清掉所有互斥锁({@code schedule:clear-mutexes})—— 进程崩溃后的补救 */
    public void clearMutexes() {
        inFlight.clear();
        for (ScheduledTask task : schedule.tasks()) {
            if (task.preventsOverlap() || task.runsOnOneServer()) {
                locks.forceRelease(task.mutexKey());
            }
        }
    }

    public long ranCount()     { return ran.get(); }
    public long skippedCount() { return skipped.get(); }
    public long failedCount()  { return failed.get(); }

    public Schedule schedule() {
        return schedule;
    }

    // ================= 类型 =================

    /**
     * {@code schedule:list} 的一行。
     *
     * @param nextRun    按 cron 算的下次运行时刻;永不运行时为 null
     * @param constraints 约束的理由列表 —— 非空时 nextRun 是乐观估计
     * @param dayLogicWarning 日/周取「或」的提醒,无歧义时为空串
     */
    public record Entry(String label, String cron, String timezone,
                        ZonedDateTime nextRun, List<String> constraints,
                        String dayLogicWarning) {

        public boolean nextRunIsOptimistic() {
            return !constraints.isEmpty();
        }
    }

    /** 分布式锁。{@code onOneServer} 用 */
    public interface TaskLocks {

        TaskLocks NONE = new TaskLocks() {
            @Override
            public Handle acquire(String key, Duration ttl) {
                // 没装锁存储时**放行**。
                //
                // 不是「拒绝执行」:单实例部署里 onOneServer 是个无害的
                // 声明,拒绝执行会让任务完全不跑 —— 那比「多实例下跑两遍」
                // 更糟,而且单实例是更常见的情形。
                //
                // 多实例 + 没有共享锁的组合由 SchedulerConfigCheck
                // 在启动时拦下,所以这里放行是安全的
                return () -> { };
            }

            @Override
            public void forceRelease(String key) {
            }
        };

        /** @return 锁句柄;没抢到时返回 null */
        Handle acquire(String key, Duration ttl);

        void forceRelease(String key);

        interface Handle {
            void release();
        }
    }

    /** HTTP 心跳。starter 用 JDK 的 HttpClient 实现它 */
    public interface Pinger {

        Pinger NONE = url -> {
            throw new IllegalStateException("""
                    配了 pingOnSuccess/pingOnFailure 但调度器没有 HTTP 通道。
                      正常情况下 starter 会装上它。""");
        };

        void ping(String url);
    }

    /** 本进程内的观察点。跨进程的关注用事件 */
    public interface Listener {
        default void onStarting(ScheduledTask task) {}

        default void onFinished(TaskOutcome outcome) {}

        default void onSkipped(ScheduledTask task, String reason) {}
    }

    /** 外部命令失败。带退出码 */
    public static class TaskFailedException extends RuntimeException {
        public TaskFailedException(String message) {
            super(message);
        }
    }
}
