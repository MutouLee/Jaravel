package io.jaravel.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.jaravel.core.context.ExecutionScope;

/**
 * 队列消费者(docs/10 §1.4)。
 *
 * <pre>{@code
 * var worker = new QueueWorker(queues, failedJobs, invoker,
 *         WorkerOptions.onQueues("high", "default").withConcurrency(50));
 * worker.run();                      // 阻塞直到 stop()
 * }</pre>
 *
 * <h2>结构</h2>
 * <pre>
 * run()                        ← 调用线程:按优先级轮询 pop
 *   └── 每个任务一个虚拟线程
 *         ├── 心跳续约(另一个虚拟线程)
 *         ├── JobScope + handle(注入依赖)
 *         ├── 成功 → delete
 *         └── 失败 → 能重试就 release(backoff),否则进 failed_jobs
 * </pre>
 *
 * <p>**虚拟线程是这里的真实优势**:{@code --concurrency=50} 在 JVM 上
 * 是 50 个虚拟线程(几十 KB),而 PHP 需要 50 个进程(数 GB)。
 * 这是 Jaravel 相对 Laravel 少有的、不靠 API 设计换来的硬优势。
 *
 * <h2>超时:Java 杀不掉一个线程</h2>
 * <p>{@link ShouldQueue#timeout()} 的实现是「在另一个虚拟线程里跑,
 * 超时后 interrupt 并把这次尝试判为失败」。
 *
 * <p>**但那个线程可能继续跑下去** —— {@code Thread.interrupt()} 只是
 * 设一个标记,只有阻塞在可中断操作上的代码才会响应它。一个
 * {@code while(true)} 的死循环或一个不检查中断的 JDBC 调用不会停。
 * {@code Thread.stop()} 在 Java 20 已被移除(它会让锁与不变量处于
 * 任意状态)。
 *
 * <p>Laravel 用 {@code pcntl_alarm} 能真的中止进程,这是平台差异。
 * 所以 Jaravel 的 timeout 语义必须说清:<b>它保证释放并发额度与
 * 推进重试,不保证停止那段代码</b>。任务里的长循环应当自己检查
 * {@code Thread.interrupted()}。
 *
 * <h2>优雅停机</h2>
 * <p>{@code stop()} 之后不再 pop,等在跑的任务完成(最多
 * {@link WorkerOptions#shutdownGrace()})。超时仍在跑的任务**不删不放回** ——
 * 它们的保留会在 retryAfter 后过期,然后被别的 worker 捡起来。
 * 那比强行 release 好:强行 release 可能让一个即将成功的任务被重复执行。
 */
public final class QueueWorker {

    private final QueueManager queues;
    private final FailedJobStore failedJobs;
    private final JobInvoker invoker;
    private final WorkerOptions options;
    private final QueueStore store;
    private final String connectionName;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Semaphore slots;

    /** 正在执行的任务 uuid → 它的线程。停机时要等它们 */
    private final ConcurrentHashMap<String, Thread> inFlight = new ConcurrentHashMap<>();

    /**
     * 续约失败的任务 uuid。
     *
     * <p>必须有这个标记,不能只靠「中断线程」来表达放弃:
     * 中断在任务里表现为 {@code InterruptedException},而那与
     * 「任务自己失败了」在 catch 里长得一模一样 —— 于是会走重试路径,
     * 把一条已经被别人接手的任务又放回队列,产生重复执行。
     *
     * <p>这正是本 worker 写完第一版时的 bug:{@code JobAbandonedException}
     * 声明了却永远不会被抛出。
     */
    private final java.util.Set<String> abandonedJobs =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public QueueWorker(QueueManager queues, FailedJobStore failedJobs,
                       JobInvoker invoker, WorkerOptions options) {
        this(queues, failedJobs, invoker, options, queues.defaultConnectionName());
    }

    public QueueWorker(QueueManager queues, FailedJobStore failedJobs,
                       JobInvoker invoker, WorkerOptions options, String connectionName) {
        this.queues = queues;
        this.failedJobs = failedJobs;
        this.invoker = invoker;
        this.options = options;
        this.connectionName = connectionName;
        this.store = queues.connection(connectionName);
        this.slots = new Semaphore(options.concurrency());

        if (!store.isAsynchronous()) {
            throw new IllegalArgumentException("""
                    不能在 "%s" 连接上起 worker —— 它是同步驱动(%s),
                    任务在投递时就执行完了,队列里永远是空的。
                      要跑 worker 请用 database 或 redis 连接。"""
                    .formatted(connectionName, store.name()));
        }
    }

    public QueueWorker addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    // ================= 主循环 =================

    /**
     * 开始消费。阻塞直到 {@link #stop()}、或达到 maxJobs / maxTime。
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("这个 worker 已经在跑了");
        }

        Instant deadline = options.hasTimeLimit()
                ? Instant.now().plus(options.maxTime())
                : null;

        try {
            while (!stopping.get()) {
                if (reachedLimits(deadline)) break;

                var job = nextJob();
                if (job.isEmpty()) {
                    if (!options.stopWhenEmpty()) {
                        sleepQuietly(options.sleep());
                        continue;
                    }

                    // ★ 这里有一个会制造 flaky 的竞态,必须按这个顺序判:
                    //
                    // 「队列空」与「没有在跑的任务」是两个时刻的观察。
                    // 先看到队列空、再看到 inFlight 空,中间那个任务可能
                    // 刚好失败并把自己**放回了队列** —— 于是 worker 退出,
                    // 而队列里还躺着一条待重试的任务。
                    //
                    // 正确的顺序:先等在跑的任务全部结束(它们可能往队列里
                    // 放东西),**然后**重新取一次。只有这次也空才能退出。
                    if (!inFlight.isEmpty()) {
                        sleepQuietly(options.sleep());
                        continue;
                    }
                    job = nextJob();
                    if (job.isEmpty()) break;
                }

                // 拿到任务之后才占额度。反过来的话(先占额度再 pop)
                // 空队列会把所有额度占着然后全在 sleep
                acquireSlot();
                if (stopping.get()) {
                    // 停机信号在 pop 与 acquire 之间到了 —— 把任务放回去,
                    // 不然它要等到保留超时才能被处理
                    slots.release();
                    store.release(job.get(), Duration.ZERO);
                    break;
                }
                // **在启动时计数**,不是在完成时。
                //
                // 在 process() 里加的话,并发度 > 1 时主循环会在计数
                // 追上来之前多取几个任务 —— maxJobs=2 实际处理 3 个。
                // 「最多处理 N 个」的自然含义是「最多开始 N 个」。
                processed.incrementAndGet();
                startJob(job.get());
            }
        } finally {
            drain();
            running.set(false);
        }
    }

    /** 在后台跑。返回的线程可用于 join。 */
    public Thread runInBackground() {
        return Thread.ofVirtual().name("jaravel-queue-worker").start(this::run);
    }

    /** 请求停机。不阻塞 —— run() 会在清理完成后返回。 */
    public void stop() {
        stopping.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }

    private boolean reachedLimits(Instant deadline) {
        if (options.hasJobLimit() && processed.get() >= options.maxJobs()) return true;
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /**
     * 按优先级取下一个任务。
     *
     * <p>**严格优先**:只要前面的队列还有任务就不看后面的。
     * 见 {@link WorkerOptions} 的类注释里为什么不做加权轮询。
     */
    private Optional<QueuedJob> nextJob() {
        for (String queue : options.queues()) {
            var job = store.pop(queue, options.retryAfter());
            if (job.isPresent()) return job;
        }
        return Optional.empty();
    }

    private void acquireSlot() {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping.set(true);
        }
    }

    // ================= 单个任务 =================

    private void startJob(QueuedJob envelope) {
        Thread runner = Thread.ofVirtual()
                .name("jaravel-job-" + envelope.uuid().substring(0, 8))
                .unstarted(() -> {
                    try {
                        process(envelope);
                    } finally {
                        inFlight.remove(envelope.uuid());
                        slots.release();
                    }
                });

        inFlight.put(envelope.uuid(), runner);
        runner.start();
    }

    /**
     * 执行一个任务。
     *
     * <p>这里不抛异常 —— 任何失败都转成 release 或 failed_jobs。
     * 让异常冒到调用方会杀掉 worker 线程,而一个任务的失败
     * 不该让整个 worker 停工。
     */
    private void process(QueuedJob envelope) {
        listeners.forEach(l -> l.onStart(envelope));

        ShouldQueue job;
        try {
            job = queues.serializer().toJob(envelope);
        } catch (RuntimeException e) {
            // 反序列化失败是**不可重试**的:再试一百次结果一样。
            // 直接归档,不浪费重试配额,也不让它在队列里打转
            archive(envelope, e, "payload 无法还原");
            return;
        }

        var heartbeat = startHeartbeat(envelope);
        try {
            runWithTimeout(envelope, job);

            // **成功也要先看标记**:这条任务可能在执行途中被判定失联
            // 并被别人接手了。此时删掉它等于把别人的那次执行抹掉
            if (wasAbandoned(envelope)) {
                reportAbandoned(envelope);
                return;
            }

            store.delete(envelope);
            succeeded.incrementAndGet();
            listeners.forEach(l -> l.onSuccess(envelope));

        } catch (Throwable cause) {
            if (wasAbandoned(envelope)) {
                // 既不删也不放回 —— 它已经被别人接手。
                // 走重试路径会把它又放回队列,产生重复执行
                reportAbandoned(envelope);
                return;
            }
            handleFailure(envelope, job, cause);

        } finally {
            heartbeat.stop();
            abandonedJobs.remove(envelope.uuid());
        }
    }

    private boolean wasAbandoned(QueuedJob envelope) {
        return abandonedJobs.contains(envelope.uuid());
    }

    private void reportAbandoned(QueuedJob envelope) {
        abandoned.incrementAndGet();
        listeners.forEach(l -> l.onAbandoned(envelope));
    }

    /**
     * 在独立的虚拟线程里跑,带超时。
     *
     * <p>超时的语义限制见类注释 —— 它保证释放并发额度与推进重试,
     * **不保证停止那段代码**。
     */
    private void runWithTimeout(QueuedJob envelope, ShouldQueue job) throws Throwable {
        Duration timeout = envelope.timeout();

        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            runInScope(envelope, job);
            return;
        }

        var failure = new Throwable[1];
        var done = new CountDownLatch(1);

        Thread execution = Thread.ofVirtual().start(() -> {
            try {
                runInScope(envelope, job);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // 本线程被中断 —— 几乎总是心跳判定失联后中断我们。
            // 内层执行线程也要中断,否则它会继续产生副作用
            execution.interrupt();
            throw interrupted;
        }

        if (!finished) {
            // interrupt 只是设标记:阻塞在可中断操作上的代码会响应,
            // 死循环不会。这一点写在类注释里 —— 它是个平台限制,
            // 不是实现缺陷
            execution.interrupt();
            timedOut.incrementAndGet();

            throw new JobTimeoutException("""
                    任务 %s 超过 %s 未完成。
                      注意:Java 无法强制终止一个线程(Thread.stop 已在 Java 20 移除)。
                      那段代码**可能仍在后台运行** —— 它只是不再占用 worker 的并发额度。
                      任务里的长循环应当自己检查 Thread.interrupted()。"""
                    .formatted(envelope.displayName(), timeout));
        }

        if (failure[0] != null) throw failure[0];
    }

    /**
     * 任务级作用域。
     *
     * <p>与请求作用域同一个机制({@code ExecutionScope})—— 于是
     * Eloquent 的身份映射、按作用域缓存的连接在任务里同样工作,
     * 而**请求级的门面会正确地报错**:队列任务里没有当前请求,
     * {@code Auth.user()} 抛 OutsideRequestException 而不是返回 null。
     * 那正是 docs/02 要消除的那个 Laravel 经典坑。
     */
    private void runInScope(QueuedJob envelope, ShouldQueue job) throws Exception {
        ExecutionScope.runIn("queue", () -> {
            ExecutionScope.current().put("queue.job", envelope);
            invoker.invoke(job);
        });
    }

    // ================= 失败处理 =================

    private void handleFailure(QueuedJob envelope, ShouldQueue job, Throwable cause) {
        Instant now = Instant.now();

        if (envelope.canRetry(now) && exceptionsWithinLimit(envelope)) {
            Duration delay = backoffFor(job, envelope.attempts());
            store.release(envelope, delay);
            released.incrementAndGet();
            listeners.forEach(l -> l.onRetry(envelope, cause, delay));
            return;
        }

        archive(envelope, cause, envelope.exhaustionReason(now));

        // failed() 在归档**之后**调:它可能自己抛异常,而那时
        // 失败记录必须已经写进去了 —— 否则一个写错的 failed()
        // 会让任务既不在队列里也不在 failed_jobs 里,彻底消失
        try {
            job.failed(cause);
        } catch (RuntimeException inFailed) {
            System.err.printf(
                    "[jaravel] 任务 %s 的 failed() 自己抛了异常:%s%n",
                    envelope.displayName(), inFailed);
        }
    }

    /**
     * 异常次数是否还在上限内。
     *
     * <p>{@code maxExceptions} 与 {@code tries} 的区别见
     * {@link ShouldQueue#maxExceptions()}:前者只数真正炸掉的次数。
     *
     * <p>当前实现用 attempts 近似 —— 因为「主动 release」还没实现
     * (任务中间件在下一块)。等它实现后这里要改成读真正的异常计数,
     * 否则 maxExceptions 与 tries 完全等价。**这一点写在这里而不是
     * 悄悄放过**,免得将来有人以为它已经生效了。
     */
    private boolean exceptionsWithinLimit(QueuedJob envelope) {
        return envelope.attempts() < envelope.maxExceptions();
    }

    private void archive(QueuedJob envelope, Throwable cause, String reason) {
        try {
            failedJobs.record(envelope, connectionName,
                    new JobFailedException(reason, cause));
        } finally {
            // 不管归档成功与否都要把它从队列里删掉 ——
            // 留着的话它会在保留超时后被再取一次,然后再失败一次,
            // 无限循环
            store.delete(envelope);
        }
        failed.incrementAndGet();
        listeners.forEach(l -> l.onFailed(envelope, cause));
    }

    /**
     * 第 n 次重试等多久。
     *
     * <p>数组比重试次数短时**重复最后一个值**,而不是回到第一个 ——
     * 回到第一个会让退避变成震荡,失去退避的意义。
     */
    private static Duration backoffFor(ShouldQueue job, int attempts) {
        Duration[] backoff = job.backoff();
        if (backoff == null || backoff.length == 0) return Duration.ZERO;

        int index = Math.min(attempts - 1, backoff.length - 1);
        return backoff[Math.max(0, index)];
    }

    // ================= 心跳 =================

    /**
     * 周期性续约,直到任务结束。
     *
     * <p>续约失败意味着这条任务已经被别人接手 —— 此时**中断执行线程**。
     * 让它继续跑会产生重复副作用,而那是队列系统里最严重的一类问题。
     */
    private Heartbeat startHeartbeat(QueuedJob envelope) {
        Duration interval = options.heartbeat();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return Heartbeat.disabled();
        }

        var stopped = new AtomicBoolean();
        Thread thread = Thread.ofVirtual()
                .name("jaravel-heartbeat-" + envelope.uuid().substring(0, 8))
                .start(() -> {
                    while (!stopped.get()) {
                        try {
                            Thread.sleep(interval.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (stopped.get()) return;

                        if (!store.touch(envelope, options.retryAfter())) {
                            // **先置标记再中断**。反了的话被中断的线程
                            // 可能先走到 catch 里、还没看到标记,
                            // 于是把一条已被接手的任务放回队列
                            abandonedJobs.add(envelope.uuid());

                            var runner = inFlight.get(envelope.uuid());
                            if (runner != null) runner.interrupt();
                            return;
                        }
                    }
                });
        return new Heartbeat(stopped, thread);
    }

    private record Heartbeat(AtomicBoolean stopped, Thread thread) {

        static Heartbeat disabled() {
            return new Heartbeat(new AtomicBoolean(true), null);
        }

        void stop() {
            stopped.set(true);
            if (thread != null) thread.interrupt();
        }
    }

    // ================= 停机 =================

    /**
     * 等在跑的任务结束。
     *
     * <p>超时后仍在跑的任务**不删不放回** —— 它们的保留会在
     * retryAfter 后过期,然后被别的 worker 捡起来。
     *
     * <p>强行 release 更糟:一个即将成功的任务会被另一个 worker
     * 同时执行,于是产生重复副作用。宁可晚一点处理。
     */
    private void drain() {
        Instant deadline = Instant.now().plus(options.shutdownGrace());

        while (!inFlight.isEmpty() && Instant.now().isBefore(deadline)) {
            sleepQuietly(Duration.ofMillis(50));
        }

        if (!inFlight.isEmpty()) {
            System.err.printf("""
                    [jaravel] 停机等待超时,仍有 %d 个任务在跑:%s
                              它们不会被删除也不会被放回 —— 保留会在 %s 后过期,
                              届时由其它 worker 接手。强行放回会让即将成功的任务
                              被重复执行。%n""",
                    inFlight.size(), inFlight.keySet(), options.retryAfter());
        }
    }

    private static void sleepQuietly(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================= 统计 =================

    public long processedCount() { return processed.get(); }
    public long succeededCount() { return succeeded.get(); }
    public long failedCount()    { return failed.get(); }
    public long releasedCount()  { return released.get(); }
    public long timedOutCount()  { return timedOut.get(); }
    public long abandonedCount() { return abandoned.get(); }
    public int  inFlightCount()  { return inFlight.size(); }

    public WorkerOptions options() { return options; }

    // ================= 观察 =================

    /** 观察 worker 的进展。日志、指标与测试用。 */
    public interface Listener {
        default void onStart(QueuedJob job) {}

        default void onSuccess(QueuedJob job) {}

        default void onRetry(QueuedJob job, Throwable cause, Duration delay) {}

        default void onFailed(QueuedJob job, Throwable cause) {}

        /** 任务被别的 worker 接手了,本 worker 放弃 */
        default void onAbandoned(QueuedJob job) {}
    }

    /** 超时。见 {@link QueueWorker} 类注释里的语义限制。 */
    public static class JobTimeoutException extends RuntimeException {
        public JobTimeoutException(String message) {
            super(message);
        }
    }

    /** 包一层,好让 failed_jobs 里的异常带上「为什么不再重试」 */
    public static class JobFailedException extends RuntimeException {
        public JobFailedException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
