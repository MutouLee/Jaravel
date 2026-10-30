package io.jaravel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/10 §3.4 明确要求的那条启动校验。
 *
 * <p>设计文档原话:「配置校验在启动时检测这个组合并报错,
 * 而不是静默失效。」
 */
class SchedulerConfigCheckTest {

    private Schedule withOneServerTask() {
        var s = new Schedule();
        s.call("对账邮件", () -> {}).dailyAt("03:00").onOneServer();
        return s;
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "caffeine", "file", "null", "CAFFEINE"})
    @DisplayName("★ onOneServer + 非共享缓存驱动 → 启动失败")
    void nonSharedDriverFails(String driver) {
        assertThatThrownBy(() -> SchedulerConfigCheck.verify(
                withOneServerTask().tasks(), driver, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是共享存储")
                .hasMessageContaining("对账邮件")
                .hasMessageContaining("每个实例上");
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "database", "memcached"})
    @DisplayName("共享驱动放行")
    void sharedDriversPass(String driver) {
        assertThatCode(() -> SchedulerConfigCheck.verify(
                withOneServerTask().tasks(), driver, 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 显式声明单实例时放行 —— 那是一个会在扩容时再撞上的声明")
    void explicitSingleInstanceIsAllowed() {
        assertThatCode(() -> SchedulerConfigCheck.verify(
                withOneServerTask().tasks(), "caffeine", 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("没有任务用 onOneServer 时不检查驱动")
    void noOffendersNoCheck() {
        var s = new Schedule();
        s.call("普通任务", () -> {}).hourly();

        assertThatCode(() -> SchedulerConfigCheck.verify(s.tasks(), "caffeine", 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 错误消息要说清为什么这里直接失败而不是警告")
    void messageExplainsWhyItIsFatal() {
        assertThatThrownBy(() -> SchedulerConfigCheck.verify(
                withOneServerTask().tasks(), "caffeine", 0))
                .hasMessageContaining("看起来正常")
                .hasMessageContaining("不幂等")
                .hasMessageContaining("三条出路");
    }

    @Test
    @DisplayName("★ TTL 比运行周期还短时给出警告")
    void shortTtlIsFlagged() {
        var s = new Schedule();
        s.call("每小时", () -> {}).hourly()
                .withoutOverlapping(Duration.ofMinutes(1));

        assertThat(SchedulerConfigCheck.suspiciousOverlapTtls(s.tasks()))
                .singleElement()
                .satisfies(w -> assertThat(w)
                        .as("TTL 比周期短意味着锁在下一轮开始前就过期了,"
                                + "withoutOverlapping 形同虚设")
                        .contains("形同虚设"));
    }

    @Test
    @DisplayName("TTL 足够长时不警告")
    void adequateTtlIsQuiet() {
        var s = new Schedule();
        s.call("每小时", () -> {}).hourly()
                .withoutOverlapping(Duration.ofHours(2));

        assertThat(SchedulerConfigCheck.suspiciousOverlapTtls(s.tasks())).isEmpty();
    }

    @Test
    @DisplayName("没配 withoutOverlapping 的任务不参与 TTL 检查")
    void tasksWithoutOverlapAreSkipped() {
        var s = new Schedule();
        s.call("每分钟", () -> {}).everyMinute();

        assertThat(SchedulerConfigCheck.suspiciousOverlapTtls(s.tasks())).isEmpty();
    }
}
