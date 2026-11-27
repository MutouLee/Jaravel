package net.dobeen.jaravel.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Clock} —— 冻结、平移、嵌套还原(docs/14 §4.2) */
class ClockTest {

    private static final Instant NEW_YEAR = Instant.parse("2026-01-01T00:00:00Z");

    @AfterEach
    void tearDown() {
        Clock.reset();
    }

    @Test
    @DisplayName("默认就是真实时间")
    void defaultsToRealTime() {
        assertThat(Clock.now()).isCloseTo(Instant.now(), within(2));
        assertThat(Clock.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("★ 冻结之后 now() 不动")
    void freezeStopsTime() throws Exception {
        Clock.freeze(NEW_YEAR);

        assertThat(Clock.now()).isEqualTo(NEW_YEAR);
        Thread.sleep(20);
        assertThat(Clock.now()).isEqualTo(NEW_YEAR);
        assertThat(Clock.localDateTime().getYear()).isEqualTo(2026);
    }

    @Test
    @DisplayName("★ travel 在冻结中移动冻结点,没冻结时平移且时间继续流动")
    void travelBehavesDifferentlyWhenFrozen() throws Exception {
        Clock.freeze(NEW_YEAR);
        Clock.travel(Duration.ofDays(7));
        assertThat(Clock.now()).isEqualTo(NEW_YEAR.plus(Duration.ofDays(7)));

        Clock.reset();
        Clock.travel(Duration.ofDays(7));
        Instant first = Clock.now();
        Thread.sleep(20);

        assertThat(Clock.now())
                .as("没冻结时时间还在走")
                .isAfter(first);
        assertThat(Clock.now())
                .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(2));
    }

    @Test
    @DisplayName("travelTo 没冻结时也只是平移")
    void travelTo() {
        Clock.travelTo(NEW_YEAR);
        assertThat(Clock.now()).isCloseTo(NEW_YEAR, within(2));

        Clock.freeze();
        Clock.travelTo(NEW_YEAR.plusSeconds(60));
        assertThat(Clock.now()).isEqualTo(NEW_YEAR.plusSeconds(60));
    }

    @Test
    @DisplayName("★ withFrozen 还原到调用前的状态,而不是真实时间")
    void withFrozenRestoresPreviousState() {
        Clock.freeze(NEW_YEAR);

        String out = Clock.withFrozen(NEW_YEAR.plusSeconds(3600), () -> {
            assertThat(Clock.now()).isEqualTo(NEW_YEAR.plusSeconds(3600));
            return "ok";
        });

        assertThat(out).isEqualTo("ok");
        assertThat(Clock.now())
                .as("外层的冻结不该被内层抹掉 —— 否则嵌套时外层静默失效")
                .isEqualTo(NEW_YEAR);
    }

    @Test
    @DisplayName("withFrozen 抛异常也还原")
    void withFrozenRestoresOnThrow() {
        Clock.freeze(NEW_YEAR);

        try {
            Clock.withFrozen(NEW_YEAR.plusSeconds(1), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 预期
        }

        assertThat(Clock.now()).isEqualTo(NEW_YEAR);
    }

    @Test
    @DisplayName("★ realNow 不受冻结影响 —— 存活性计时用它")
    void realNowIgnoresTravel() {
        Clock.freeze(NEW_YEAR);

        assertThat(Clock.realNow())
                .as("冻结时间不该让一个 5 秒后该退出的循环永远跑下去")
                .isCloseTo(Instant.now(), within(2));
    }

    @Test
    @DisplayName("java.time.Clock 视图是活的 —— 之后的 travel 仍然生效")
    void javaClockViewIsLive() {
        java.time.Clock view = Clock.java();

        Clock.freeze(NEW_YEAR);

        assertThat(view.instant())
                .as("先拿走 clock、后 travel 是最常见的顺序")
                .isEqualTo(NEW_YEAR);
    }

    @Test
    @DisplayName("reset 回到真实时间")
    void resetRestoresRealTime() {
        Clock.freeze(NEW_YEAR);
        Clock.reset();

        assertThat(Clock.isFrozen()).isFalse();
        assertThat(Clock.now()).isCloseTo(Instant.now(), within(2));
        assertThat(Clock.drift()).isEqualTo(Duration.ZERO);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(seconds,
                java.time.temporal.ChronoUnit.SECONDS);
    }
}
