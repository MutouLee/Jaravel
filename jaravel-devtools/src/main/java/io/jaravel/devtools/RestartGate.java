package io.jaravel.devtools;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 重启期间的请求闸门(docs/15 §3.3 的两项增强)。
 *
 * <h2>增强一:请求挂起而不是拒绝</h2>
 * <p>重启期间到来的请求**等待**而不是收 503。用户在浏览器里刷新的体验是
 * 「慢了 2 秒」而不是「报错了」—— 后者会让人以为改动把应用弄坏了,
 * 然后开始排查一个根本不存在的问题。
 *
 * <h2>增强二:先编译再处理,而不是让用户等重启完再手动刷新</h2>
 * <p>有待处理变更时,**下一个进来的请求触发重启**,等重启完再被处理。
 * 用户的感知是「刷新一下,改动就生效了」—— 与 PHP 的心智模型一致。
 *
 * <p>这一点是整个设计的关键:文件一存就立刻重启看起来更积极,但实际
 * 很糟 —— 连续编辑 10 个文件会触发 10 次重启,而用户一次都没刷新。
 * 按需触发让重启次数等于「刷新次数」,而不是「保存次数」。
 *
 * <h2>为什么用 ReentrantLock 而不是 synchronized</h2>
 * <p>等待需要**带超时**。无限等待意味着重启一旦卡住(编译错误、
 * 端口没释放),所有请求线程会永久挂起,连错误页都显示不出来 ——
 * 用户看到的是浏览器转圈,这比任何报错都难排查。
 */
public final class RestartGate {

    /** 请求最多等多久 */
    private final Duration maxWait;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();

    /** 是否正在重启 */
    private boolean restarting;

    /** 有待处理的变更,下一个请求将触发重启 */
    private volatile ReloadStrategy pending = ReloadStrategy.IGNORE;

    private final AtomicInteger parkedRequests = new AtomicInteger();
    private final AtomicLong totalParked = new AtomicLong();
    private final AtomicLong totalRestarts = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();

    public RestartGate() {
        this(Duration.ofSeconds(10));
    }

    public RestartGate(Duration maxWait) {
        this.maxWait = maxWait;
    }

    // ================= 变更登记 =================

    /**
     * 登记一批变更。**不立即重启** —— 见类注释的增强二。
     *
     * <p>多次登记取成本最高的那个:先改了模板又新增了类,
     * 必须按新增类处理。
     */
    public void recordChange(ReloadStrategy strategy) {
        if (strategy == ReloadStrategy.IGNORE) return;

        lock.lock();
        try {
            pending = pending.escalate(strategy);
        } finally {
            lock.unlock();
        }
    }

    public ReloadStrategy pendingStrategy() {
        return pending;
    }

    public boolean hasPendingChanges() {
        return pending != ReloadStrategy.IGNORE;
    }

    // ================= 请求侧 =================

    /**
     * 请求进入前调用。
     *
     * <p>两件事:
     * <ol>
     *   <li>正在重启 → 挂起等待;</li>
     *   <li>有待处理变更 → 先执行它,再放行(「刷新即生效」)。</li>
     * </ol>
     *
     * @param reloader 执行重载的动作。由调用方提供,本类不知道怎么重启。
     * @return 放行则 true;等超时则 false(调用方应返回 503 并说明原因)
     */
    public boolean enter(Reloader reloader) {
        if (!awaitIdle()) return false;

        ReloadStrategy toApply;
        lock.lock();
        try {
            toApply = pending;
            if (toApply == ReloadStrategy.IGNORE) return true;

            // 抢到执行权:清掉 pending 并进入重启态,
            // 其它请求会在 awaitIdle 里等
            pending = ReloadStrategy.IGNORE;
            restarting = true;
        } finally {
            lock.unlock();
        }

        totalRestarts.incrementAndGet();
        try {
            reloader.reload(toApply);
            return true;
        } catch (RuntimeException e) {
            // 重载失败(编译错误等)。把变更放回去 —— 用户修好再刷新一次
            // 就会重试。清掉它等于「改错了之后再改对也不生效」
            lock.lock();
            try {
                pending = pending.escalate(toApply);
            } finally {
                lock.unlock();
            }
            throw e;
        } finally {
            lock.lock();
            try {
                restarting = false;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 等到不在重启状态。
     *
     * @return 超时返回 false
     */
    private boolean awaitIdle() {
        lock.lock();
        try {
            if (!restarting) return true;

            parkedRequests.incrementAndGet();
            totalParked.incrementAndGet();
            try {
                long deadline = System.nanoTime() + maxWait.toNanos();
                while (restarting) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        timeouts.incrementAndGet();
                        return false;
                    }
                    if (!idle.await(remaining, TimeUnit.NANOSECONDS) && restarting) {
                        timeouts.incrementAndGet();
                        return false;
                    }
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                parkedRequests.decrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    // ================= 直接控制 =================

    /**
     * 不等请求,立刻执行一次重载。
     *
     * <p>给 {@code jaravel serve} 的手动触发(按 r 重启)与测试用。
     */
    public void restartNow(Reloader reloader) {
        lock.lock();
        try {
            while (restarting) {
                try {
                    idle.await(maxWait.toNanos(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            restarting = true;
            pending = ReloadStrategy.IGNORE;
        } finally {
            lock.unlock();
        }

        totalRestarts.incrementAndGet();
        try {
            reloader.reload(ReloadStrategy.RESTART_LOADER);
        } finally {
            lock.lock();
            try {
                restarting = false;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean isRestarting() {
        lock.lock();
        try {
            return restarting;
        } finally {
            lock.unlock();
        }
    }

    // ================= 诊断 =================

    public int parkedRequests()   { return parkedRequests.get(); }
    public long totalParked()     { return totalParked.get(); }
    public long totalRestarts()   { return totalRestarts.get(); }
    public long timeouts()        { return timeouts.get(); }
    public Duration maxWait()     { return maxWait; }

    /** 超时时给用户看的说明 —— 浏览器转圈之后至少要有个解释 */
    public String timeoutMessage() {
        return """
                等待热重载超时(%d 秒)。
                  可能的原因:
                    · 编译失败 —— 看一下控制台的 javac 输出;
                    · 某个 @PreDestroy 卡住了,旧容器关不掉;
                    · 改动触发的是 JVM 重启(pom.xml / .env),那需要手动重启。
                  临时绕过:jaravel serve --no-watch"""
                .formatted(maxWait.toSeconds());
    }

    /** 执行重载的动作。由框架侧提供实现。 */
    public interface Reloader {
        void reload(ReloadStrategy strategy);
    }
}
