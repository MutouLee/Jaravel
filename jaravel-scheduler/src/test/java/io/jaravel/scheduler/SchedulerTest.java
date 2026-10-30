package io.jaravel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.scheduler.events.ScheduleEvents;

/** 频率 DSL、约束、互斥、多实例去重、钩子 */
class SchedulerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(String iso) {
        return ZonedDateTime.parse(iso + ":00Z").withZoneSameInstant(UTC);
    }

    private final List<String> log = new CopyOnWriteArrayList<>();
    private EventBus bus;

    @BeforeEach
    void setUp() {
        log.clear();
        bus = EventBus.standalone();
        Event.use(bus);
    }

    @AfterEach
    void tearDown() {
        Event.reset();
    }

    /**
     * 测试用的 schedule —— **固定 UTC**。
     *
     * <p>不固定的话任务用 JVM 时区而断言用 UTC 构造时刻,
     * 于是在非 UTC 机器上一整组断言会失败。
     * 这是写这些测试时当场踩到的第一个坑,也正是
     * {@code Schedule.timezone(...)} 存在的理由。
     */
    private Schedule schedule() {
        return new Schedule().timezone(UTC);
    }

    // ================= 频率 DSL =================

    @Nested
    @DisplayName("频率方法")
    class Frequencies {

        @Test
        @DisplayName("★ 频率方法都落到 cron 表达式上")
        void mapToCron() {
            var s = schedule();

            assertThat(s.call("a", () -> {}).everyMinute()
                    .cronExpression().expression()).isEqualTo("* * * * *");
            assertThat(s.call("b", () -> {}).everyFiveMinutes()
                    .cronExpression().expression()).isEqualTo("*/5 * * * *");
            assertThat(s.call("c", () -> {}).hourly()
                    .cronExpression().expression()).isEqualTo("0 * * * *");
            assertThat(s.call("d", () -> {}).hourlyAt(17)
                    .cronExpression().expression()).isEqualTo("17 * * * *");
            assertThat(s.call("e", () -> {}).daily()
                    .cronExpression().expression()).isEqualTo("0 0 * * *");
            assertThat(s.call("f", () -> {}).dailyAt("13:45")
                    .cronExpression().expression()).isEqualTo("45 13 * * *");
            assertThat(s.call("g", () -> {}).twiceDaily(1, 13)
                    .cronExpression().expression()).isEqualTo("0 1,13 * * *");
            assertThat(s.call("h", () -> {}).weeklyOn(1, "08:00")
                    .cronExpression().expression()).isEqualTo("0 8 * * 1");
            assertThat(s.call("i", () -> {}).monthlyOn(4, "15:30")
                    .cronExpression().expression()).isEqualTo("30 15 4 * *");
            assertThat(s.call("j", () -> {}).twiceMonthly(1, 16)
                    .cronExpression().expression()).isEqualTo("0 0 1,16 * *");
            assertThat(s.call("k", () -> {}).quarterly()
                    .cronExpression().expression()).isEqualTo("0 0 1 1,4,7,10 *");
            assertThat(s.call("l", () -> {}).yearly()
                    .cronExpression().expression()).isEqualTo("0 0 1 1 *");
        }

        @Test
        @DisplayName("dailyAt 接受 \"3\" 也接受 \"03:00\"")
        void dailyAtFormats() {
            var s = schedule();

            assertThat(s.call("a", () -> {}).dailyAt("3")
                    .cronExpression().expression()).isEqualTo("0 3 * * *");
            assertThat(s.call("b", () -> {}).dailyAt("03:00")
                    .cronExpression().expression()).isEqualTo("0 3 * * *");
        }

        @Test
        @DisplayName("★ 时间格式错误时说清不支持秒")
        void badTimeFormat() {
            assertThatThrownBy(() -> schedule().call("a", () -> {}).dailyAt("03:00:00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("调度的精度是分钟");
        }

        @Test
        @DisplayName("★ lastDayOfMonth 靠过滤器 —— 标准 cron 表达不了它")
        void lastDayOfMonth() {
            var task = schedule().call("月末", () -> {}).lastDayOfMonth("02:00");

            assertThat(task.cronExpression().expression())
                    .as("cron 本身是每天 —— 所以 schedule:list 里的"
                            + "下次运行时间是乐观估计")
                    .isEqualTo("0 2 * * *");
            assertThat(task.hasFilters()).isTrue();

            // 2026-10-31 是十月最后一天
            assertThat(task.isDue(at("2026-10-31T02:00"))).isTrue();
            assertThat(task.isDue(at("2026-10-30T02:00"))).isFalse();

            // 2 月:平年 28 日是最后一天
            assertThat(task.isDue(at("2026-02-28T02:00"))).isTrue();
            assertThat(task.isDue(at("2026-02-27T02:00"))).isFalse();

            // 闰年 2 月是 29 日
            assertThat(task.isDue(at("2028-02-29T02:00"))).isTrue();
            assertThat(task.isDue(at("2028-02-28T02:00")))
                    .as("闰年里 28 日不是最后一天 —— 写死 28 的实现会在这里错")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 连写两个频率时后者覆盖前者")
        void laterFrequencyWins() {
            var task = schedule().call("a", () -> {}).daily().hourly();

            assertThat(task.cronExpression().expression()).isEqualTo("0 * * * *");
        }

        @Test
        @DisplayName("超范围的参数报错")
        void outOfRangeArguments() {
            assertThatThrownBy(() -> schedule().call("a", () -> {}).hourlyAt(60))
                    .hasMessageContaining("0-59");
            assertThatThrownBy(() -> schedule().call("a", () -> {}).monthlyOn(32, "0:00"))
                    .hasMessageContaining("1-31");
        }
    }

    // ================= 约束 =================

    @Nested
    @DisplayName("约束")
    class Constraints {

        @Test
        @DisplayName("★ 约束是叠加的,不是覆盖")
        void constraintsStack() {
            var task = schedule().call("a", () -> {}).everyMinute()
                    .weekdays()
                    .between("09:00", "17:00");

            assertThat(task.filterReasons()).hasSize(2);

            // 2026-10-30 周五 10:00 —— 都满足
            assertThat(task.isDue(at("2026-10-30T10:00"))).isTrue();
            // 周五 20:00 —— 时间不符
            assertThat(task.isDue(at("2026-10-30T20:00"))).isFalse();
            // 周六 10:00 —— 星期不符
            assertThat(task.isDue(at("2026-10-31T10:00"))).isFalse();
        }

        @Test
        @DisplayName("between 包含两端")
        void betweenIsInclusive() {
            var task = schedule().call("a", () -> {}).everyMinute()
                    .between("09:00", "17:00");

            assertThat(task.isDue(at("2026-10-30T09:00"))).isTrue();
            assertThat(task.isDue(at("2026-10-30T17:00"))).isTrue();
            assertThat(task.isDue(at("2026-10-30T08:59"))).isFalse();
            assertThat(task.isDue(at("2026-10-30T17:01"))).isFalse();
        }

        @Test
        @DisplayName("★ between 跨午夜")
        void betweenAcrossMidnight() {
            var task = schedule().call("a", () -> {}).everyMinute()
                    .between("22:00", "02:00");

            assertThat(task.isDue(at("2026-10-30T23:30"))).isTrue();
            assertThat(task.isDue(at("2026-10-30T01:00"))).isTrue();
            assertThat(task.isDue(at("2026-10-30T12:00"))).isFalse();
        }

        @Test
        @DisplayName("unlessBetween 是 between 的反面")
        void unlessBetween() {
            var task = schedule().call("a", () -> {}).everyMinute()
                    .unlessBetween("02:00", "04:00");

            assertThat(task.isDue(at("2026-10-30T03:00"))).isFalse();
            assertThat(task.isDue(at("2026-10-30T05:00"))).isTrue();
        }

        @Test
        @DisplayName("weekdays / weekends")
        void weekdaysAndWeekends() {
            var weekday = schedule().call("a", () -> {}).everyMinute().weekdays();
            var weekend = schedule().call("b", () -> {}).everyMinute().weekends();

            // 周五
            assertThat(weekday.isDue(at("2026-10-30T10:00"))).isTrue();
            assertThat(weekend.isDue(at("2026-10-30T10:00"))).isFalse();
            // 周六
            assertThat(weekday.isDue(at("2026-10-31T10:00"))).isFalse();
            assertThat(weekend.isDue(at("2026-10-31T10:00"))).isTrue();
            // 周日
            assertThat(weekend.isDue(at("2026-11-01T10:00"))).isTrue();
        }

        @Test
        @DisplayName("具体星期")
        void namedDays() {
            assertThat(schedule().call("a", () -> {}).everyMinute().fridays()
                    .isDue(at("2026-10-30T10:00"))).isTrue();
            assertThat(schedule().call("b", () -> {}).everyMinute().mondays()
                    .isDue(at("2026-10-30T10:00"))).isFalse();
            assertThat(schedule().call("c", () -> {}).everyMinute().days(1, 3, 5)
                    .isDue(at("2026-10-30T10:00"))).isTrue();
        }

        @Test
        @DisplayName("when / skip")
        void whenAndSkip() {
            var flag = new AtomicInteger(0);

            var gated = schedule().call("a", () -> {}).everyMinute()
                    .when(() -> flag.get() == 1);
            var skipped = schedule().call("b", () -> {}).everyMinute()
                    .skip(() -> flag.get() == 1);

            assertThat(gated.isDue(at("2026-10-30T10:00"))).isFalse();
            assertThat(skipped.isDue(at("2026-10-30T10:00"))).isTrue();

            flag.set(1);
            assertThat(gated.isDue(at("2026-10-30T10:00"))).isTrue();
            assertThat(skipped.isDue(at("2026-10-30T10:00"))).isFalse();
        }

        @Test
        @DisplayName("★ 跳过时能说出**哪个**约束挡住了")
        void skipReasonIsSpecific() {
            var task = schedule().call("a", () -> {}).everyMinute()
                    .weekdays()
                    .between("09:00", "17:00");

            assertThat(task.skipReason(at("2026-10-31T10:00")))
                    .as("「任务没跑」而没有原因是最让人没办法的情形")
                    .contains("仅工作日");
            assertThat(task.skipReason(at("2026-10-30T20:00")))
                    .contains("仅 09:00–17:00");
            assertThat(task.skipReason(at("2026-10-30T10:00"))).isEmpty();
        }

        @Test
        @DisplayName("★ 时区影响判定")
        void timezoneMatters() {
            var shanghai = schedule().call("a", () -> {}).dailyAt("09:00")
                    .timezone("Asia/Shanghai");

            // 上海 09:00 = UTC 01:00
            assertThat(shanghai.isDue(at("2026-10-30T01:00"))).isTrue();
            assertThat(shanghai.isDue(at("2026-10-30T09:00"))).isFalse();
        }
    }

    // ================= 执行 =================

    @Nested
    @DisplayName("执行")
    class Running {

        @Test
        @DisplayName("到点的任务被执行")
        void dueTasksRun() {
            var s = schedule();
            s.call("跑我", () -> log.add("ran")).everyMinute();
            s.call("别跑我", () -> log.add("nope")).dailyAt("03:00");

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(log).containsExactly("ran");
        }

        @Test
        @DisplayName("★ 同一分钟内不重复执行,即便被调两次")
        void oncePerMinute() {
            var s = schedule();
            s.call("a", () -> log.add("ran")).everyMinute();

            var scheduler = new Scheduler(s);
            scheduler.runDue(at("2026-10-30T10:00"));
            scheduler.runDue(at("2026-10-30T10:00"));

            assertThat(log)
                    .as("调度线程的唤醒有抖动,可能在同一分钟内醒两次")
                    .hasSize(1);

            scheduler.runDue(at("2026-10-30T10:01"));
            assertThat(log).hasSize(2);
        }

        @Test
        @DisplayName("★ 任务抛异常不影响其它任务")
        void oneFailureDoesNotStopOthers() {
            var s = schedule();
            s.call("炸", () -> {
                throw new IllegalStateException("炸了");
            }).everyMinute();
            s.call("好", () -> log.add("ok")).everyMinute();

            var scheduler = new Scheduler(s);
            scheduler.runDue(at("2026-10-30T10:00"));

            assertThat(log).containsExactly("ok");
            assertThat(scheduler.failedCount()).isEqualTo(1);
            assertThat(scheduler.ranCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 钩子的执行顺序:before → 任务 → after → onSuccess")
        void hookOrder() {
            var s = schedule();
            s.call("a", () -> log.add("task")).everyMinute()
                    .before(() -> log.add("before"))
                    .after(() -> log.add("after"))
                    .onSuccess(o -> log.add("success"));

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(log).containsExactly("before", "task", "after", "success");
        }

        @Test
        @DisplayName("★ after 在失败时也跑,且在 onFailure 之前")
        void afterRunsOnFailureToo() {
            var s = schedule();
            s.call("a", () -> {
                throw new IllegalStateException("炸了");
            }).everyMinute()
                    .after(() -> log.add("after"))
                    .onFailure(o -> log.add("failure:" + o.failure().getMessage()));

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(log)
                    .as("after 的语义是「收尾」—— 无论成败都要做,"
                            + "而且要在「通知外部」之前做完")
                    .containsExactly("after", "failure:炸了");
        }

        @Test
        @DisplayName("★ 钩子自己抛异常不把任务判成失败")
        void brokenHookDoesNotFailTheTask() {
            var s = schedule();
            s.call("a", () -> log.add("task")).everyMinute()
                    .before(() -> {
                        throw new NullPointerException("日志代码的 bug");
                    })
                    .onSuccess(o -> log.add("success"));

            var scheduler = new Scheduler(s);
            scheduler.runDue(at("2026-10-30T10:00"));

            assertThat(log)
                    .as("一个 before(() -> log.info(...)) 里的 NPE "
                            + "不该让任务被判成失败并触发告警")
                    .containsExactly("task", "success");
            assertThat(scheduler.failedCount()).isZero();
        }

        @Test
        @DisplayName("onSuccess 的 outcome 带耗时")
        void outcomeCarriesDuration() {
            var captured = new ArrayList<TaskOutcome>();
            var s = schedule();
            s.call("a", () -> {}).everyMinute().onSuccess(captured::add);

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(captured).singleElement().satisfies(o -> {
                assertThat(o.succeeded()).isTrue();
                assertThat(o.duration()).isNotNull();
                assertThat(o.summary()).contains("成功");
            });
        }

        @Test
        @DisplayName("runNow 绕过 cron 与约束")
        void runNowBypassesEverything() {
            var s = schedule();
            s.call("手动", () -> log.add("ran"))
                    .yearly()
                    .when(() -> false);

            assertThat(new Scheduler(s).runNow("手动")).isPresent();
            assertThat(log)
                    .as("schedule:test 的用途就是「现在就跑一次看看」")
                    .containsExactly("ran");
        }

        @Test
        @DisplayName("runNow 找不到任务时返回 empty")
        void runNowUnknownTask() {
            assertThat(new Scheduler(schedule()).runNow("不存在")).isEmpty();
        }

        @Test
        @DisplayName("后台任务不阻塞调用方")
        void backgroundTasksDoNotBlock() throws Exception {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);

            var s = schedule();
            s.call("慢", () -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                log.add("done");
            }).everyMinute().runInBackground();

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(started.await(2, TimeUnit.SECONDS))
                    .as("runDue 不该等它跑完")
                    .isTrue();
            assertThat(log).isEmpty();

            release.countDown();
        }
    }

    // ================= 互斥 =================

    @Nested
    @DisplayName("互斥与多实例")
    class Mutex {

        @Test
        @DisplayName("TTL 必须是正数")
        void ttlIsRequired() {
            assertThatThrownBy(() -> schedule().call("a", () -> {})
                    .withoutOverlapping(Duration.ZERO))
                    .hasMessageContaining("永久");
        }

        @Test
        @DisplayName("★ 上一轮还在跑时跳过,并说明原因")
        void skipsWhileStillRunning() throws Exception {
            var release = new CountDownLatch(1);
            var started = new CountDownLatch(1);
            var skips = new CopyOnWriteArrayList<String>();

            var s = schedule();
            s.call("慢", () -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }).everyMinute()
                    .withoutOverlapping(Duration.ofMinutes(10))
                    .runInBackground();

            var scheduler = new Scheduler(s).addListener(new Scheduler.Listener() {
                @Override
                public void onSkipped(ScheduledTask task, String reason) {
                    skips.add(reason);
                }
            });

            scheduler.runDue(at("2026-10-30T10:00"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            scheduler.runDue(at("2026-10-30T10:01"));

            assertThat(skips).singleElement()
                    .satisfies(r -> assertThat(r).contains("上一轮还在运行"));

            release.countDown();
        }

        @Test
        @DisplayName("★ onOneServer:抢不到锁就跳过,理由不同于本地互斥")
        void onOneServerUsesTheLock() {
            var skips = new CopyOnWriteArrayList<String>();

            var s = schedule();
            s.call("独占", () -> log.add("ran")).everyMinute().onOneServer();

            var scheduler = new Scheduler(s)
                    .locks(new Scheduler.TaskLocks() {
                        @Override
                        public Handle acquire(String key, Duration ttl) {
                            return null;            // 假装别的实例抢到了
                        }

                        @Override
                        public void forceRelease(String key) {
                        }
                    })
                    .addListener(new Scheduler.Listener() {
                        @Override
                        public void onSkipped(ScheduledTask task, String reason) {
                            skips.add(reason);
                        }
                    });

            scheduler.runDue(at("2026-10-30T10:00"));

            assertThat(log).isEmpty();
            assertThat(skips).singleElement()
                    .satisfies(r -> assertThat(r)
                            .as("三种跳过的处理方式完全不同,所以理由要分开")
                            .contains("另一个实例"));
        }

        @Test
        @DisplayName("★ 抢到锁时执行,并在结束后释放")
        void lockIsReleasedAfterRunning() {
            var held = ConcurrentHashMap.<String>newKeySet();

            var s = schedule();
            s.call("独占", () -> log.add("ran")).everyMinute().onOneServer();

            var scheduler = new Scheduler(s).locks(new Scheduler.TaskLocks() {
                @Override
                public Handle acquire(String key, Duration ttl) {
                    if (!held.add(key)) return null;
                    return () -> held.remove(key);
                }

                @Override
                public void forceRelease(String key) {
                    held.remove(key);
                }
            });

            scheduler.runDue(at("2026-10-30T10:00"));
            scheduler.runDue(at("2026-10-30T10:01"));

            assertThat(log).hasSize(2);
            assertThat(held)
                    .as("不释放的话这个任务会被挡到 TTL 过期为止")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 没装锁存储时 onOneServer 放行,不是拒绝执行")
        void noLockStoreMeansAllow() {
            var s = schedule();
            s.call("独占", () -> log.add("ran")).everyMinute().onOneServer();

            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(log)
                    .as("单实例部署里 onOneServer 是无害的声明;"
                            + "拒绝执行会让任务完全不跑,比「多实例下跑两遍」更糟")
                    .containsExactly("ran");
        }

        @Test
        @DisplayName("重名任务在构造时就报错")
        void duplicateNamesAreRejected() {
            var s = schedule();
            s.call("同名", () -> {}).everyMinute();
            s.call("同名", () -> {}).hourly();

            assertThatThrownBy(() -> new Scheduler(s))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("共用一个互斥锁 key");
        }
    }

    // ================= 事件 =================

    @Nested
    @DisplayName("调度事件")
    class Events {

        @Test
        @DisplayName("★ Starting / Finished 真的发出来")
        void lifecycleEvents() {
            var events = new CopyOnWriteArrayList<Object>();
            bus.listen(ScheduleEvents.TaskStarting.class, events::add);
            bus.listen(ScheduleEvents.TaskFinished.class, events::add);

            var s = schedule();
            s.call("任务", () -> {}).everyMinute();
            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(ScheduleEvents.TaskStarting.class);
            assertThat((ScheduleEvents.TaskFinished) events.get(1))
                    .satisfies(e -> {
                        assertThat(e.succeeded()).isTrue();
                        assertThat(e.task()).isEqualTo("任务");
                    });
        }

        @Test
        @DisplayName("★ Skipped 带上**为什么** —— 这是最有价值的那个事件")
        void skippedCarriesReason() {
            var skips = new CopyOnWriteArrayList<ScheduleEvents.TaskSkipped>();
            bus.listen(ScheduleEvents.TaskSkipped.class, skips::add);

            var s = schedule();
            s.call("被挡住", () -> {}).everyMinute().when(() -> false);
            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(skips).singleElement().satisfies(e -> {
                assertThat(e.task()).isEqualTo("被挡住");
                assertThat(e.reason())
                        .as("一个失败的任务留下异常与日志;"
                                + "一个被跳过的任务什么都不留 —— 除了这个事件")
                        .contains("when(...)");
            });
        }

        @Test
        @DisplayName("失败时 Finished 带上失败描述")
        void failureIsReported() {
            var finished = new CopyOnWriteArrayList<ScheduleEvents.TaskFinished>();
            bus.listen(ScheduleEvents.TaskFinished.class, finished::add);

            var s = schedule();
            s.call("炸", () -> {
                throw new IllegalStateException("炸了");
            }).everyMinute();
            new Scheduler(s).runDue(at("2026-10-30T10:00"));

            assertThat(finished).singleElement().satisfies(e -> {
                assertThat(e.succeeded()).isFalse();
                assertThat(e.failure()).contains("炸了");
            });
        }
    }

    // ================= schedule:list =================

    @Nested
    @DisplayName("列表")
    class Listing {

        @Test
        @DisplayName("★ 有约束的任务标出「下次运行是乐观估计」")
        void constrainedTasksAreMarked() {
            var s = schedule();
            s.call("无约束", () -> {}).dailyAt("03:00");
            s.call("有约束", () -> {}).dailyAt("03:00").weekdays();

            var entries = new Scheduler(s).list(at("2026-10-30T10:00"));

            assertThat(entries.get(0).nextRunIsOptimistic()).isFalse();
            assertThat(entries.get(1).nextRunIsOptimistic())
                    .as("不标的话一个 weekdays() 的任务会在列表里显示一个周六的时间")
                    .isTrue();
            assertThat(entries.get(1).constraints()).contains("仅工作日");
        }

        @Test
        @DisplayName("列表带时区 —— 它默认是 JVM 时区,会随部署环境变")
        void timezoneIsShown() {
            var s = schedule();
            s.call("a", () -> {}).daily().timezone("Asia/Shanghai");

            assertThat(new Scheduler(s).list(at("2026-10-30T10:00")).get(0).timezone())
                    .isEqualTo("Asia/Shanghai");
        }

        @Test
        @DisplayName("★ 日/周取「或」的提醒出现在列表里")
        void dayLogicWarningIsSurfaced() {
            var s = schedule();
            s.call("a", () -> {}).cron("0 0 13 * 5");

            assertThat(new Scheduler(s).list(at("2026-10-30T10:00")).get(0)
                    .dayLogicWarning())
                    .contains("取**或**");
        }

        @Test
        @DisplayName("永不运行的表达式 nextRun 为 null")
        void impossibleTaskHasNoNextRun() {
            var s = schedule();
            s.call("永不", () -> {}).cron("0 0 30 2 *");

            assertThat(new Scheduler(s).list(at("2026-10-30T10:00")).get(0).nextRun())
                    .isNull();
        }
    }

    // ================= 入口的守卫 =================

    @Nested
    @DisplayName("入口的前置检查")
    class Guards {

        @Test
        @DisplayName("★ s.call 必须给名字")
        void callNeedsALabel() {
            assertThatThrownBy(() -> schedule().call("", () -> {}))
                    .hasMessageContaining("withoutOverlapping 在一次发布后失效");
        }

        @Test
        @DisplayName("★ 没有队列通道时 s.job 报错,说明后果")
        void jobNeedsAQueue() {
            assertThatThrownBy(() -> schedule().job(String.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("其它所有任务都延迟");
        }

        @Test
        @DisplayName("没有 CLI 时 s.command 说清这是 v0.5")
        void commandNeedsCli() {
            assertThatThrownBy(() -> schedule().command("cache:clear"))
                    .hasMessageContaining("v0.5");
        }

        @Test
        @DisplayName("s.exec 至少要一个命令")
        void execNeedsACommand() {
            assertThatThrownBy(() -> schedule().exec())
                    .hasMessageContaining("至少要一个命令");
        }
    }
}
