package io.jaravel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 五段式 cron 的解析与匹配 */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(String iso) {
        return ZonedDateTime.parse(iso + ":00Z").withZoneSameInstant(UTC);
    }

    // ================= 基本匹配 =================

    @Nested
    @DisplayName("字段语法")
    class Fields {

        @Test
        @DisplayName("精确值")
        void exactValues() {
            var cron = CronExpression.parse("30 14 * * *");

            assertThat(cron.matches(at("2026-10-30T14:30"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T14:31"))).isFalse();
            assertThat(cron.matches(at("2026-10-30T15:30"))).isFalse();
        }

        @Test
        @DisplayName("通配")
        void wildcard() {
            var cron = CronExpression.parse("* * * * *");

            assertThat(cron.matches(at("2026-01-01T00:00"))).isTrue();
            assertThat(cron.matches(at("2026-07-15T13:47"))).isTrue();
        }

        @Test
        @DisplayName("列表 a,b,c")
        void list() {
            var cron = CronExpression.parse("0 1,13 * * *");

            assertThat(cron.matches(at("2026-10-30T01:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T13:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T07:00"))).isFalse();
        }

        @Test
        @DisplayName("范围 a-b")
        void range() {
            var cron = CronExpression.parse("0 9-17 * * *");

            assertThat(cron.matches(at("2026-10-30T09:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T17:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T18:00"))).isFalse();
            assertThat(cron.matches(at("2026-10-30T08:00"))).isFalse();
        }

        @Test
        @DisplayName("步长 */n")
        void step() {
            var cron = CronExpression.parse("*/15 * * * *");

            for (int minute : new int[] {0, 15, 30, 45}) {
                assertThat(cron.matches(at("2026-10-30T10:%02d".formatted(minute))))
                        .as("分钟 " + minute)
                        .isTrue();
            }
            assertThat(cron.matches(at("2026-10-30T10:07"))).isFalse();
        }

        @Test
        @DisplayName("★ 带步长的单个数字表示「从它开始」—— 与 crontab 一致")
        void stepFromSingleValue() {
            var cron = CronExpression.parse("5/10 * * * *");

            for (int minute : new int[] {5, 15, 25, 35, 45, 55}) {
                assertThat(cron.matches(at("2026-10-30T10:%02d".formatted(minute))))
                        .as("分钟 " + minute)
                        .isTrue();
            }
            assertThat(cron.matches(at("2026-10-30T10:00"))).isFalse();
        }

        @Test
        @DisplayName("范围 + 步长 a-b/n")
        void rangeWithStep() {
            var cron = CronExpression.parse("0 8-18/2 * * *");

            assertThat(cron.matches(at("2026-10-30T08:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T10:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T09:00"))).isFalse();
            assertThat(cron.matches(at("2026-10-30T20:00"))).isFalse();
        }

        @Test
        @DisplayName("★ 跨界范围 22-2 绕一圈")
        void wrappingRange() {
            var cron = CronExpression.parse("0 22-2 * * *");

            assertThat(cron.matches(at("2026-10-30T22:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T23:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T00:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T02:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T03:00"))).isFalse();
            assertThat(cron.matches(at("2026-10-30T12:00"))).isFalse();
        }

        @Test
        @DisplayName("月份与星期的名字")
        void names() {
            assertThat(CronExpression.parse("0 0 1 jan *")
                    .matches(at("2026-01-01T00:00"))).isTrue();
            assertThat(CronExpression.parse("0 0 1 JAN *")
                    .matches(at("2026-02-01T00:00"))).isFalse();

            // 2026-10-30 是周五
            assertThat(CronExpression.parse("0 0 * * fri")
                    .matches(at("2026-10-30T00:00"))).isTrue();
            assertThat(CronExpression.parse("0 0 * * mon-fri")
                    .matches(at("2026-10-30T00:00"))).isTrue();
        }

        @Test
        @DisplayName("★ 周日既是 0 也是 7 —— crontab 的惯例")
        void sundayIsZeroOrSeven() {
            // 2026-11-01 是周日
            assertThat(CronExpression.parse("0 0 * * 0")
                    .matches(at("2026-11-01T00:00"))).isTrue();
            assertThat(CronExpression.parse("0 0 * * 7").matches(at("2026-11-01T00:00")))
                    .as("不接受 7 的话,从 crontab 复制过来的表达式会报错")
                    .isTrue();
        }
    }

    // ================= 日/周的「或」 =================

    @Nested
    @DisplayName("日与周同时限制时取「或」")
    class DayLogic {

        @Test
        @DisplayName("★ 0 0 13 * 5 = 每月 13 号**或**每周五")
        void orNotAnd() {
            var cron = CronExpression.parse("0 0 13 * 5");

            // 2026-11-13 恰好是周五 —— 两个条件都满足
            assertThat(cron.matches(at("2026-11-13T00:00"))).isTrue();

            // 2026-10-13 是周二 —— 只满足「13 号」
            assertThat(cron.matches(at("2026-10-13T00:00")))
                    .as("POSIX 规定取或,不是且 —— 这是 cron 最有名的坑")
                    .isTrue();

            // 2026-10-30 是周五 —— 只满足「周五」
            assertThat(cron.matches(at("2026-10-30T00:00"))).isTrue();

            // 2026-10-14 周三,也不是 13 号
            assertThat(cron.matches(at("2026-10-14T00:00"))).isFalse();
        }

        @Test
        @DisplayName("只限制日时按日匹配")
        void onlyDayOfMonth() {
            var cron = CronExpression.parse("0 0 13 * *");

            assertThat(cron.matches(at("2026-10-13T00:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-30T00:00"))).isFalse();
        }

        @Test
        @DisplayName("只限制周时按周匹配")
        void onlyDayOfWeek() {
            var cron = CronExpression.parse("0 0 * * 5");

            assertThat(cron.matches(at("2026-10-30T00:00"))).isTrue();
            assertThat(cron.matches(at("2026-10-29T00:00"))).isFalse();
        }

        @Test
        @DisplayName("★ 那个「或」要在 schedule:list 里说出来")
        void logicIsDocumentedAtRuntime() {
            assertThat(CronExpression.parse("0 0 13 * 5").describeDayLogic())
                    .as("一个只显示 cron 字符串的列表帮不上忙")
                    .contains("取**或**");

            assertThat(CronExpression.parse("0 0 13 * *").describeDayLogic())
                    .as("没有歧义时不要加噪音")
                    .isEmpty();
        }
    }

    // ================= nextRun =================

    @Nested
    @DisplayName("下次运行时刻")
    class NextRun {

        @Test
        @DisplayName("不含起点本身")
        void excludesTheStartingPoint() {
            var cron = CronExpression.parse("0 3 * * *");

            assertThat(cron.nextRun(at("2026-10-30T03:00")))
                    .as("起点自己就匹配时,要返回**下一次**而不是现在 —— "
                            + "否则调度器会在同一分钟内反复触发")
                    .contains(at("2026-10-31T03:00"));
        }

        @Test
        @DisplayName("跨天 / 跨月 / 跨年")
        void crossesBoundaries() {
            assertThat(CronExpression.parse("0 3 * * *").nextRun(at("2026-10-30T05:00")))
                    .contains(at("2026-10-31T03:00"));

            assertThat(CronExpression.parse("0 0 1 * *").nextRun(at("2026-10-30T05:00")))
                    .contains(at("2026-11-01T00:00"));

            assertThat(CronExpression.parse("0 0 1 1 *").nextRun(at("2026-10-30T05:00")))
                    .contains(at("2027-01-01T00:00"));
        }

        @Test
        @DisplayName("★ 2 月 29 日 —— 只在闰年")
        void leapDay() {
            var cron = CronExpression.parse("0 0 29 2 *");

            assertThat(cron.nextRun(at("2026-03-01T00:00")))
                    .as("2027 与 2026 都不是闰年,下一个是 2028")
                    .contains(at("2028-02-29T00:00"));
        }

        @Test
        @DisplayName("★ 永远不会匹配的表达式返回 empty,不死循环")
        void impossibleExpression() {
            // 2 月 30 日不存在
            assertThat(CronExpression.parse("0 0 30 2 *").nextRun(at("2026-01-01T00:00")))
                    .as("四年的上限既覆盖闰年,又保证不死循环")
                    .isEmpty();
        }

        @Test
        @DisplayName("连续多次")
        void multipleRuns() {
            var runs = CronExpression.parse("0 */6 * * *")
                    .nextRuns(at("2026-10-30T00:00"), 4);

            assertThat(runs).containsExactly(
                    at("2026-10-30T06:00"),
                    at("2026-10-30T12:00"),
                    at("2026-10-30T18:00"),
                    at("2026-10-31T00:00"));
        }
    }

    // ================= 错误消息 =================

    @Nested
    @DisplayName("语法错误")
    class Errors {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("空表达式")
        void blank(String expression) {
            assertThatThrownBy(() -> CronExpression.parse(expression))
                    .isInstanceOf(CronExpression.InvalidCronException.class)
                    .hasMessageContaining("不能为空");
        }

        @ParameterizedTest
        @CsvSource({
                "'* * * *',       4 段",
                "'* * * * * *',   6 段",
                "'0 0 1 1 * 2026', 7 段"})
        @DisplayName("★ 段数不对时说清不支持六/七段式")
        void wrongFieldCount(String expression, String ignored) {
            assertThatThrownBy(() -> CronExpression.parse(expression))
                    .isInstanceOf(CronExpression.InvalidCronException.class)
                    .hasMessageContaining("5 段")
                    .hasMessageContaining("六段/七段式");
        }

        @Test
        @DisplayName("超出范围的值指出是哪一段")
        void outOfRange() {
            assertThatThrownBy(() -> CronExpression.parse("60 * * * *"))
                    .hasMessageContaining("「分」")
                    .hasMessageContaining("0-59");

            assertThatThrownBy(() -> CronExpression.parse("* 24 * * *"))
                    .hasMessageContaining("「时」")
                    .hasMessageContaining("0-23");

            assertThatThrownBy(() -> CronExpression.parse("* * 32 * *"))
                    .hasMessageContaining("「日」");

            assertThatThrownBy(() -> CronExpression.parse("* * * 13 *"))
                    .hasMessageContaining("「月」");
        }

        @Test
        @DisplayName("非数字的值")
        void notANumber() {
            assertThatThrownBy(() -> CronExpression.parse("abc * * * *"))
                    .hasMessageContaining("「分」")
                    .hasMessageContaining("abc");
        }

        @Test
        @DisplayName("步长必须是正数")
        void zeroStep() {
            assertThatThrownBy(() -> CronExpression.parse("*/0 * * * *"))
                    .hasMessageContaining("步长必须是正数");
        }

        @Test
        @DisplayName("isValid 不抛异常 —— 配置校验用")
        void isValid() {
            assertThat(CronExpression.isValid("0 3 * * *")).isTrue();
            assertThat(CronExpression.isValid("nope")).isFalse();
        }
    }

    // ================= 等值 =================

    @Test
    @DisplayName("同一表达式相等")
    void equality() {
        assertThat(CronExpression.parse("0 3 * * *"))
                .isEqualTo(CronExpression.parse("  0 3 * * *  "));
        assertThat(CronExpression.parse("0 3 * * *"))
                .isNotEqualTo(CronExpression.parse("0 4 * * *"));
    }
}
