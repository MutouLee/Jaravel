package io.jaravel.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 启动时的配置校验(docs/10 §3.4 明确要求的一条)。
 *
 * <p>设计文档的原话:
 *
 * <blockquote>
 * **重要警示**(文档必须写明):此机制依赖共享缓存(Redis),
 * 使用 {@code caffeine}/{@code file} 驱动时 {@code onOneServer()}
 * **无效**。配置校验在启动时检测这个组合并报错,而不是静默失效。
 * </blockquote>
 *
 * <h2>为什么这件事值得一个专门的类</h2>
 * <p>{@code onOneServer()} 是这个框架里**最容易静默失效**的 API:
 *
 * <ul>
 *   <li>它在单实例开发环境下「看起来正常」—— 本来就只有一个实例;</li>
 *   <li>它在多实例生产环境下失效的症状是「任务跑了两遍」,
 *       而那个症状对很多任务是无害的(清理缓存跑两遍没事),
 *       于是它会潜伏到遇上一个**不幂等**的任务为止 ——
 *       比如「给昨天的订单发对账邮件」;</li>
 *   <li>那时的现象是「客户收到两封一样的邮件」,
 *       而从那个现象到「缓存驱动是 caffeine」之间有很长的距离。</li>
 * </ul>
 *
 * <p>所以这个检查在启动时**直接失败**,而不是打一条警告。
 * 警告会被忽略 —— 启动日志里本来就有很多行。
 */
public final class SchedulerConfigCheck {

    /**
     * 不是共享存储的缓存驱动。
     *
     * <p>{@code file} 在这里值得解释:它看起来「持久」,但多实例通常
     * 各有自己的文件系统(容器、不同机器)。共享卷的情形存在,
     * 但那是例外而不是默认 —— 而一个默认不安全的判定该偏向报错。
     */
    private static final Set<String> NON_SHARED_DRIVERS =
            Set.of("array", "caffeine", "file", "null");

    private SchedulerConfigCheck() {
    }

    /**
     * 校验。
     *
     * @param tasks        已登记的任务
     * @param cacheDriver  当前的缓存驱动名(锁用它)
     * @param instanceCount 配置里声明的实例数。**未知时传 0**
     * @throws IllegalStateException 组合不安全时
     */
    public static void verify(List<ScheduledTask> tasks, String cacheDriver,
                              int instanceCount) {
        var offenders = tasks.stream()
                .filter(ScheduledTask::runsOnOneServer)
                .map(ScheduledTask::label)
                .toList();

        if (offenders.isEmpty()) return;

        String driver = cacheDriver == null ? "(未配置)" : cacheDriver.toLowerCase();
        if (!NON_SHARED_DRIVERS.contains(driver)) return;      // redis / database → 安全

        // 明确声明了单实例 → 放行,但说清这个声明的含义
        if (instanceCount == 1) {
            System.err.printf("""

                    [jaravel] 提示:这些任务用了 onOneServer(),而缓存驱动是 %s
                              (不是共享存储)。
                              因为 schedule.instances=1,这个组合被允许 ——
                              单实例下 onOneServer() 本来就是个无害的声明。
                              **扩到多实例之前必须换成 redis 或 database 驱动**,
                              否则这些任务会在每个实例上各跑一遍:%s
                    %n""", driver, offenders);
            return;
        }

        throw new IllegalStateException("""
                调度配置不安全:%d 个任务声明了 onOneServer(),
                但缓存驱动是 %s —— 它**不是共享存储**。

                  受影响的任务:%s

                  后果:多实例部署时每个实例各有一份锁,于是
                        onOneServer() 完全无效,这些任务会在**每个实例上
                        各跑一遍**。

                  为什么这里直接失败而不是打一条警告:
                    · 单实例开发环境下它「看起来正常」——
                      本来就只有一个实例;
                    · 多实例下失效的症状是「任务跑了两遍」,
                      而那对很多任务无害(清缓存跑两遍没事),
                      于是它会潜伏到遇上一个不幂等的任务为止;
                    · 那时的现象是「客户收到两封一样的对账邮件」,
                      而从那个现象到「缓存驱动是 %s」之间距离很远。

                  三条出路,按推荐顺序:
                    1. 把 cache.default 换成 redis 或 database;
                    2. 确实是单实例部署 → 在 config/schedule.yml 里写
                         schedule:
                           instances: 1
                       (这是一个**显式的声明**,扩容时会再次撞上这个检查);
                    3. 去掉这些任务的 onOneServer() —— 只在确认它们
                       幂等、跑两遍无害时这么做。"""
                .formatted(offenders.size(), driver, offenders, driver));
    }

    /**
     * {@code withoutOverlapping} 的 TTL 够不够长。
     *
     * <p>只能给出警告而不是报错:框架不知道任务要跑多久。
     * 但「TTL 比任务的运行周期还短」是一个**确定的**配置错误 ——
     * 那意味着第二轮必然在第一轮的锁还没过期时启动,
     * 于是 withoutOverlapping 变成了「总是跳过」或「总是重叠」,
     * 取决于时序。
     */
    public static List<String> suspiciousOverlapTtls(List<ScheduledTask> tasks) {
        var warnings = new ArrayList<String>();

        for (ScheduledTask task : tasks) {
            if (!task.preventsOverlap()) continue;

            var next = task.nextRun(java.time.ZonedDateTime.now());
            if (next.isEmpty()) continue;

            var then = task.nextRun(next.get());
            if (then.isEmpty()) continue;

            var period = java.time.Duration.between(next.get(), then.get());
            if (task.overlapTtl().compareTo(period) < 0) {
                warnings.add("""
                        任务「%s」的 withoutOverlapping TTL 是 %s,
                          但它每 %s 运行一次 —— TTL 比运行周期还短。
                          于是锁在下一轮开始前就过期了,withoutOverlapping 形同虚设。
                          把 TTL 调到大于任务的**最长执行时间**(通常也大于运行周期)。"""
                        .formatted(task.label(), task.overlapTtl(), period));
            }
        }
        return warnings;
    }
}
