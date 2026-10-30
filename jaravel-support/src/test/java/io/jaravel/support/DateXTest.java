package io.jaravel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** DateX —— docs/02 §6.3 的 Carbon 等价物 */
class DateXTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 一个固定的参照时刻:2026-10-28(周三)10:15:30 +08:00 */
    private static DateX fixed() {
        return DateX.of(ZonedDateTime.of(
                LocalDateTime.of(2026, 10, 28, 10, 15, 30), SHANGHAI));
    }

    @Nested
    @DisplayName("解析")
    class Parsing {

        @Test
        @DisplayName("四种常见格式都认")
        void commonFormats() {
            assertThat(DateX.parse("2026-10-28").toDateString()).isEqualTo("2026-10-28");
            assertThat(DateX.parse("2026-10-28T10:15:30").toTimeString())
                    .isEqualTo("10:15:30");
            assertThat(DateX.parse("2026-10-28 10:15:30").toDateTimeString())
                    .isEqualTo("2026-10-28 10:15:30");
            assertThat(DateX.parse("2026-10-28 10:15").toTimeString())
                    .isEqualTo("10:15:00");
        }

        @Test
        @DisplayName("★ 带空格的那种也认 —— 数据库里最常见")
        void databaseFormat() {
            assertThat(DateX.parse("2026-10-28 10:15:30").hour())
                    .as("它不是 ISO(中间是空格而不是 T),但到处都是")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("★ 有歧义的格式**拒绝**,不猜")
        void ambiguousFormatsAreRejected() {
            assertThatThrownBy(() -> DateX.parse("01/02/2026"))
                    .as("美式是 1 月 2 日,欧式是 2 月 1 日 —— "
                            + "猜错不会报错,只会让数据悄悄错掉")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("有歧义");
        }

        @Test
        @DisplayName("显式给模式时可以解析任意格式")
        void explicitPattern() {
            assertThat(DateX.parse("01/02/2026", "MM/dd/yyyy").toDateString())
                    .isEqualTo("2026-01-02");
            assertThat(DateX.parse("2026年10月28日", "yyyy年MM月dd日").dayOfMonth())
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("空入参报错")
        void blankInput() {
            assertThatThrownBy(() -> DateX.parse("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DateX.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("★ 不可变 —— 与 Carbon 刻意不同")
    class Immutability {

        @Test
        @DisplayName("addDays 不会改掉原来那个")
        void addDoesNotMutate() {
            var start = fixed();
            var end = start.addDays(7);

            assertThat(start.toDateString())
                    .as("""
                            Carbon 的实例是**可变**的,$start->addDays(7) 会把
                            $start 也改掉 —— 那是 Carbon 最有名的坑。
                            照搬它的 API,但不照搬它的可变性。""")
                    .isEqualTo("2026-10-28");
            assertThat(end.toDateString()).isEqualTo("2026-11-04");
        }

        @Test
        @DisplayName("startOfDay / timezone 同样返回新实例")
        void alignmentDoesNotMutate() {
            var at = fixed();

            assertThat(at.startOfDay().toTimeString()).isEqualTo("00:00:00");
            assertThat(at.toTimeString()).isEqualTo("10:15:30");

            assertThat(at.timezone("UTC").hour()).isEqualTo(2);
            assertThat(at.hour()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("加减与对齐")
    class Arithmetic {

        @Test
        @DisplayName("链式写法")
        void chaining() {
            assertThat(fixed().addDays(3).startOfDay().toDateTimeString())
                    .isEqualTo("2026-10-31 00:00:00");
        }

        @Test
        @DisplayName("sub 就是负的 add")
        void subtraction() {
            assertThat(fixed().subDays(28).toDateString()).isEqualTo("2026-09-30");
            assertThat(fixed().subMonths(1).toDateString()).isEqualTo("2026-09-28");
            assertThat(fixed().subYears(1).toDateString()).isEqualTo("2025-10-28");
        }

        @Test
        @DisplayName("★ 月末对齐认得每个月的长度")
        void endOfMonthKnowsMonthLength() {
            assertThat(fixed().endOfMonth().toDateString())
                    .as("十月是 31 天")
                    .isEqualTo("2026-10-31");
            assertThat(DateX.parse("2026-02-10").endOfMonth().toDateString())
                    .as("平年二月 28 天")
                    .isEqualTo("2026-02-28");
            assertThat(DateX.parse("2028-02-10").endOfMonth().toDateString())
                    .as("闰年二月 29 天 —— 写死 28 的实现会在这里错")
                    .isEqualTo("2028-02-29");
        }

        @Test
        @DisplayName("★ 一周从**周一**开始")
        void weekStartsOnMonday() {
            // 2026-10-28 是周三
            assertThat(fixed().dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);

            assertThat(fixed().startOfWeek().toDateString())
                    .as("ISO-8601 与 Carbon 都是周一。两种习惯都对,"
                            + "但必须选一个并说清 —— 否则「本周的订单」"
                            + "在两个报表里会差一天")
                    .isEqualTo("2026-10-26");
            assertThat(fixed().endOfWeek().toDateString()).isEqualTo("2026-11-01");
        }

        @Test
        @DisplayName("年初年末")
        void yearBoundaries() {
            assertThat(fixed().startOfYear().toDateTimeString())
                    .isEqualTo("2026-01-01 00:00:00");
            assertThat(fixed().endOfYear().toDateString()).isEqualTo("2026-12-31");
        }
    }

    @Nested
    @DisplayName("时区")
    class Zones {

        @Test
        @DisplayName("★ 换时区是换表示,不是改时刻")
        void sameInstant() {
            var shanghai = fixed();
            var utc = shanghai.timezone("UTC");

            assertThat(utc.hour()).as("+08:00 的 10:15 是 UTC 的 02:15").isEqualTo(2);
            assertThat(utc.toInstant())
                    .as("同一个时刻")
                    .isEqualTo(shanghai.toInstant());
        }

        @Test
        @DisplayName("★ 相等按时刻比,不按表示")
        void equalityByInstant() {
            assertThat(fixed())
                    .as("ZonedDateTime.equals 会连时区一起比而判不等 —— "
                            + "而那几乎从来不是调用方想问的问题")
                    .isEqualTo(fixed().timezone("UTC"));
            assertThat(fixed().hashCode()).isEqualTo(fixed().timezone("UTC").hashCode());
        }
    }

    @Nested
    @DisplayName("判断")
    class Predicates {

        @Test
        @DisplayName("周末 / 工作日")
        void weekend() {
            assertThat(fixed().isWeekend()).as("周三").isFalse();
            assertThat(fixed().isWeekday()).isTrue();

            assertThat(DateX.parse("2026-10-31").isWeekend()).as("周六").isTrue();
            assertThat(DateX.parse("2026-11-01").isWeekend()).as("周日").isTrue();
        }

        @Test
        @DisplayName("过去 / 未来 / 今天")
        void temporalPredicates() {
            assertThat(DateX.now().subDays(1).isPast()).isTrue();
            assertThat(DateX.now().addDays(1).isFuture()).isTrue();
            assertThat(DateX.now().isToday()).isTrue();
            assertThat(DateX.now().addDays(2).isToday()).isFalse();
        }

        @Test
        @DisplayName("isSameDay 跨时区")
        void sameDayAcrossZones() {
            var at = fixed();
            assertThat(at.isSameDay(at.timezone("UTC")))
                    .as("同一个时刻在上海与 UTC 都还是 10-28")
                    .isTrue();
        }

        @Test
        @DisplayName("排序")
        void ordering() {
            var a = fixed();
            var b = a.addDays(1);

            assertThat(a.isBefore(b)).isTrue();
            assertThat(b.isAfter(a)).isTrue();
            assertThat(java.util.stream.Stream.of(b, a).sorted().toList())
                    .containsExactly(a, b);
        }
    }

    @Nested
    @DisplayName("★ diffForHumans —— java.time 完全不提供的那一个")
    class HumanDiff {

        private final DateX reference = fixed();

        private String ago(java.util.function.Function<DateX, DateX> shift) {
            return shift.apply(reference).diffForHumans(reference);
        }

        @Test
        @DisplayName("各个量级")
        void magnitudes() {
            assertThat(ago(d -> d.subSeconds(30))).isEqualTo("刚刚");
            assertThat(ago(d -> d.subMinutes(5))).isEqualTo("5 分钟前");
            assertThat(ago(d -> d.subHours(3))).isEqualTo("3 小时前");
            assertThat(ago(d -> d.subDays(3))).isEqualTo("3 天前");
            assertThat(ago(d -> d.subDays(90))).isEqualTo("3 个月前");
            assertThat(ago(d -> d.subDays(400))).isEqualTo("1 年前");
        }

        @Test
        @DisplayName("未来用「后」")
        void future() {
            assertThat(ago(d -> d.addSeconds(30))).isEqualTo("马上");
            assertThat(ago(d -> d.addHours(2))).isEqualTo("2 小时后");
            assertThat(ago(d -> d.addDays(3))).isEqualTo("3 天后");
        }

        @Test
        @DisplayName("无参版本以「现在」为参照")
        void relativeToNow() {
            assertThat(DateX.now().subDays(2).diffForHumans()).isEqualTo("2 天前");
        }
    }

    @Nested
    @DisplayName("差值与格式化")
    class DiffAndFormat {

        @Test
        @DisplayName("★ 差值取绝对值 —— 方向由 isBefore 回答")
        void diffIsAbsolute() {
            var a = fixed();
            var b = a.addDays(3);

            assertThat(a.diffInDays(b)).isEqualTo(3);
            assertThat(b.diffInDays(a))
                    .as("带符号的差值会让一半的调用点忘记处理负数")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("各个单位")
        void units() {
            var a = fixed();
            assertThat(a.diffInSeconds(a.addMinutes(2))).isEqualTo(120);
            assertThat(a.diffInMinutes(a.addHours(2))).isEqualTo(120);
            assertThat(a.diffInHours(a.addDays(2))).isEqualTo(48);
        }

        @Test
        @DisplayName("格式化")
        void formatting() {
            assertThat(fixed().toDateTimeString()).isEqualTo("2026-10-28 10:15:30");
            assertThat(fixed().toDateString()).isEqualTo("2026-10-28");
            assertThat(fixed().toTimeString()).isEqualTo("10:15:30");
            assertThat(fixed().format("yyyy/MM/dd")).isEqualTo("2026/10/28");
            assertThat(fixed().toIsoString()).startsWith("2026-10-28T10:15:30+08:00");
        }

        @Test
        @DisplayName("★ toString 走 toDateTimeString —— 模板里直接输出就对")
        void toStringIsUseful() {
            assertThat(fixed().toString())
                    .as("默认的 Object.toString 在模板里会输出一个对象地址")
                    .isEqualTo("2026-10-28 10:15:30");
        }
    }

    @Nested
    @DisplayName("互操作")
    class Interop {

        @Test
        @DisplayName("与 java.time 双向转换")
        void conversions() {
            var at = fixed();

            assertThat(DateX.of(at.toZonedDateTime())).isEqualTo(at);
            assertThat(DateX.of(at.toInstant())).isEqualTo(at);
            assertThat(at.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 28));
            assertThat(at.toLocalDateTime().getHour()).isEqualTo(10);
        }

        @Test
        @DisplayName("until 给出 Duration")
        void untilDuration() {
            assertThat(fixed().until(fixed().addHours(5)).toHours()).isEqualTo(5);
        }

        @Test
        @DisplayName("epoch")
        void epoch() {
            assertThat(fixed().epochMilli()).isEqualTo(fixed().epochSecond() * 1000);
        }
    }
}
