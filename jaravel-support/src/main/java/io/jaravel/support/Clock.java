package io.jaravel.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * 框架的时间源 —— 可冻结、可快进(docs/14 §4.2)。
 *
 * <pre>{@code
 * Clock.freeze();                                    // 停在此刻
 * Clock.freeze(Instant.parse("2026-01-01T00:00:00Z"));
 * Clock.travel(Duration.ofDays(7));                  // 快进七天
 * Clock.travelTo(instant);
 * Clock.withFrozen(instant, () -> { ... });          // 只在这一段里冻结
 * Clock.reset();                                     // 回到真实时间
 * }</pre>
 *
 * <h2>★ 为什么必须有这个东西</h2>
 * <p>一个「密码重置链接 60 分钟后失效」的实现,如果时间只能靠
 * {@code Instant.now()} 读,那么测它的唯一办法是**睡 60 分钟**或者
 * 把过期时间配成 1 毫秒然后 {@code Thread.sleep(2)} —— 后者测的是
 * 「1 毫秒会不会过期」,不是那条业务规则。于是这类逻辑在绝大多数
 * 项目里是**没有测试的**,而它们恰好是最容易写错的那一类。
 *
 * <h2>★ 哪些地方必须走它,哪些地方不能</h2>
 * <p>**领域时间**走 Clock:模型时间戳、令牌与签名过期、缓存 TTL、
 * 任务的可见时刻与延迟、调度器的下次执行时间。这些是业务语义,
 * 测试要能把它们往前拨。
 *
 * <p>**存活性计时**不走 Clock:worker 的 {@code maxTime} 与关停宽限、
 * 驱动的心跳与可见性超时。它们量的是「真的过了多久」——
 * 冻结时间会让一个本该在 5 秒后退出的循环**永远跑下去**,
 * 而那不是一个可接受的失败形态。这些点显式用 {@link #realNow()},
 * 并在 ArchUnit 规则的白名单里逐条登记。
 *
 * <p>这条边界由 {@code ArchitectureBoundaryTest} 的
 * {@code no_direct_clock_reads} 规则强制:框架代码里出现
 * {@code Instant.now()} / {@code LocalDateTime.now()} /
 * {@code System.currentTimeMillis()} 就挂,除非在白名单里。
 *
 * <h2>静态状态与测试隔离</h2>
 * <p>与其它门面同样是 JVM 全局静态。{@code FeatureTest} 在每个测试前
 * {@link #reset()} —— 一个漏出去的冻结时间会让后面的测试以
 * 「令牌莫名过期/莫名不过期」失败,且只在特定执行顺序下出现。
 */
public final class Clock {

    /**
     * 冻结在哪一刻。null = 没冻结,时间照常流动。
     *
     * <p>与 {@link #offset} 是两种不同的旅行:冻结是「时间停住」,
     * offset 是「时间平移但继续走」。两者可以同时存在吗?不能 ——
     * 冻结时 offset 没有意义,所以 freeze 之后 travel 改的是 frozenAt。
     */
    private static volatile Instant frozenAt;

    /** 没冻结时的平移量 */
    private static volatile Duration offset = Duration.ZERO;

    private Clock() {
    }

    // ================= 读 =================

    /** 当前时刻(受冻结/平移影响) */
    public static Instant now() {
        Instant frozen = frozenAt;
        if (frozen != null) return frozen;

        Duration shift = offset;
        Instant real = Instant.now();
        return shift.isZero() ? real : real.plus(shift);
    }

    /**
     * **真实**的当前时刻 —— 不受冻结与平移影响。
     *
     * <p>只给存活性计时用(worker 的关停宽限、驱动心跳)。
     * 业务逻辑用它等于把自己从时间旅行里排除出去,
     * 症状是「测试里把时间拨了七天,这段代码没反应」。
     */
    public static Instant realNow() {
        return Instant.now();
    }

    public static long millis() {
        return now().toEpochMilli();
    }

    public static ZonedDateTime zoned() {
        return now().atZone(ZoneId.systemDefault());
    }

    public static ZonedDateTime zoned(ZoneId zone) {
        return now().atZone(zone);
    }

    public static LocalDateTime localDateTime() {
        return LocalDateTime.ofInstant(now(), ZoneId.systemDefault());
    }

    public static LocalDate localDate() {
        return localDateTime().toLocalDate();
    }

    /**
     * 适配需要 {@link java.time.Clock} 的第三方 API。
     *
     * <p>返回的是一个**活的视图**:它每次读都回到这里,所以之后的
     * freeze / travel 对已经拿走它的调用方同样生效。返回快照的话,
     * 「先注入 clock、后 travel」这个最常见的顺序会不生效。
     */
    public static java.time.Clock java() {
        return new LiveClock(ZoneId.systemDefault());
    }

    public static java.time.Clock java(ZoneId zone) {
        return new LiveClock(zone);
    }

    /** 每次读都回到 {@link Clock#now()} 的 {@link java.time.Clock} */
    private static final class LiveClock extends java.time.Clock {

        private final ZoneId zone;

        private LiveClock(ZoneId zone) {
            this.zone = zone;
        }

        @Override public ZoneId getZone() {
            return zone;
        }

        @Override public java.time.Clock withZone(ZoneId other) {
            return new LiveClock(other);
        }

        @Override public Instant instant() {
            return Clock.now();
        }
    }

    // ================= 旅行 =================

    /** 停在此刻 */
    public static void freeze() {
        frozenAt = now();
    }

    /** 停在指定时刻 */
    public static void freeze(Instant instant) {
        frozenAt = instant;
    }

    /**
     * 快进(负数即回退)。
     *
     * <p>冻结中则移动冻结点;没冻结则平移,时间继续流动。
     */
    public static void travel(Duration amount) {
        if (amount == null || amount.isZero()) return;

        Instant frozen = frozenAt;
        if (frozen != null) {
            frozenAt = frozen.plus(amount);
        } else {
            offset = offset.plus(amount);
        }
    }

    /** 跳到指定时刻。冻结中则仍然冻结,只是换了位置 */
    public static void travelTo(Instant instant) {
        if (frozenAt != null) {
            frozenAt = instant;
        } else {
            offset = Duration.between(Instant.now(), instant);
        }
    }

    public static boolean isFrozen() {
        return frozenAt != null;
    }

    /** 当前的平移量 —— 诊断用 */
    public static Duration drift() {
        Instant frozen = frozenAt;
        return frozen == null ? offset : Duration.between(Instant.now(), frozen);
    }

    /**
     * 只在这一段里冻结,结束后**还原到调用前的状态**(不是还原到真实时间)。
     *
     * <p>还原到真实时间会让嵌套使用出错:外层冻在 2026-01-01、
     * 内层 {@code withFrozen(...)} 结束后外层的冻结就没了,
     * 而那个失效只在有嵌套时出现。
     */
    public static void withFrozen(Instant instant, Runnable body) {
        withFrozen(instant, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T withFrozen(Instant instant, Supplier<T> body) {
        Instant previousFrozen = frozenAt;
        Duration previousOffset = offset;
        frozenAt = instant;
        try {
            return body.get();
        } finally {
            frozenAt = previousFrozen;
            offset = previousOffset;
        }
    }

    /** 回到真实时间。测试基类每个测试前调它 */
    public static void reset() {
        frozenAt = null;
        offset = Duration.ZERO;
    }
}
