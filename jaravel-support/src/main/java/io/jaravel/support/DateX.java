package io.jaravel.support;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 时间门面(docs/02 §6.3)—— Carbon 的等价物。
 *
 * <pre>{@code
 * DateX.now().addDays(3).startOfDay().toDateTimeString();
 * DateX.parse("2026-10-28").diffForHumans();      // "3 天前"
 * DateX.now().isWeekend();
 * DateX.now().timezone("UTC");
 * }</pre>
 *
 * <h2>为什么需要它</h2>
 * <p>这个框架的全部论点是「Laravel 级的开发体验」,而 Carbon 在
 * Laravel 的模板里用得极多:{@code {{ post.created_at.diffForHumans() }}}。
 * 用 {@code java.time} 写同一件事要三行加一个 formatter 常量 ——
 * 而那三行会出现在**每一个**模板里。
 *
 * <h2>★ 不重新实现时间逻辑</h2>
 * <p>内部就是一个 {@link ZonedDateTime},每个方法都转发给它。
 * 这不是谦虚:时间计算(闰秒、夏令时、历法)是出了名的难,
 * 而 {@code java.time} 已经做对了。重做一遍只会引入
 * 「只在某个时区的某一天出错」那类缺陷。
 *
 * <h2>不可变</h2>
 * <p>每个 {@code addX} / {@code startOfX} 都返回**新实例**,
 * 与 {@code java.time} 一致。
 *
 * <p>这一条是刻意强调的:Carbon 的实例是**可变**的,
 * 而那是 Carbon 最有名的坑 —— {@code $end = $start->addDays(7)}
 * 会把 {@code $start} 也改掉,于是两个变量指向同一天。
 * 照搬 Carbon 的 API 但**不照搬它的可变性**。
 *
 * <h2>逃生口</h2>
 * <p>{@link #toZonedDateTime()} 把底层对象交出去 —— 任何这里没包的
 * 能力都从那里拿,不需要等框架补。
 */
public final class DateX implements Comparable<DateX> {

    private final ZonedDateTime at;

    private DateX(ZonedDateTime at) {
        this.at = at;
    }

    // ================= 构造 =================

    public static DateX now() {
        return new DateX(ZonedDateTime.now());
    }

    public static DateX now(ZoneId zone) {
        return new DateX(ZonedDateTime.now(zone));
    }

    public static DateX of(ZonedDateTime at) {
        return new DateX(at);
    }

    public static DateX of(Instant instant) {
        return new DateX(instant.atZone(ZoneId.systemDefault()));
    }

    public static DateX of(LocalDateTime at) {
        return new DateX(at.atZone(ZoneId.systemDefault()));
    }

    public static DateX of(LocalDate date) {
        return new DateX(date.atStartOfDay(ZoneId.systemDefault()));
    }

    /**
     * 宽松解析 —— 按顺序试几种常见格式。
     *
     * <p>⚠ **不猜**歧义的格式:{@code 01/02/2026} 在美式是 1 月 2 日、
     * 在欧式是 2 月 1 日,而猜错一个日期不会报错,只会让数据悄悄错掉。
     * 这类格式一律拒绝,让调用方用 {@link #parse(String, String)}
     * 显式给出模式。
     *
     * @throws IllegalArgumentException 认不出来时 —— 带上试过哪些格式
     */
    public static DateX parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("DateX.parse 的入参是空的");
        }
        String s = text.trim();

        // 带时区偏移的 ISO:2026-10-28T10:15:30+08:00[Asia/Shanghai]
        try {
            return new DateX(ZonedDateTime.parse(s));
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        // 不带时区的 ISO:2026-10-28T10:15:30
        try {
            return of(LocalDateTime.parse(s));
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        // 纯日期:2026-10-28
        try {
            return of(LocalDate.parse(s));
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        // 「2026-10-28 10:15:30」—— 数据库里最常见的那一种,
        // 它不是 ISO(中间是空格而不是 T),但到处都是
        try {
            return of(LocalDateTime.parse(s,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return of(LocalDateTime.parse(s,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        } catch (DateTimeParseException ignored) {
            // 没有下一种了
        }

        throw new IllegalArgumentException("""
                认不出这个时间:"%s"

                  试过:ISO 带时区、ISO 不带时区、纯日期、
                        "yyyy-MM-dd HH:mm:ss"、"yyyy-MM-dd HH:mm"

                  ⚠ 像 01/02/2026 这样**有歧义**的格式是刻意不支持的 ——
                    美式是 1 月 2 日,欧式是 2 月 1 日,而猜错不会报错,
                    只会让数据悄悄错掉。
                    那种格式请显式给模式:DateX.parse(text, "MM/dd/yyyy")"""
                .formatted(text));
    }

    /** 按给定模式解析 */
    public static DateX parse(String text, String pattern) {
        var formatter = DateTimeFormatter.ofPattern(pattern);
        try {
            return of(LocalDateTime.parse(text, formatter));
        } catch (DateTimeParseException e) {
            // 模式里只有日期没有时间时,LocalDateTime 解不了
            return of(LocalDate.parse(text, formatter));
        }
    }

    // ================= 加减 =================

    public DateX addSeconds(long n) { return new DateX(at.plusSeconds(n)); }
    public DateX addMinutes(long n) { return new DateX(at.plusMinutes(n)); }
    public DateX addHours(long n)   { return new DateX(at.plusHours(n)); }
    public DateX addDays(long n)    { return new DateX(at.plusDays(n)); }
    public DateX addWeeks(long n)   { return new DateX(at.plusWeeks(n)); }
    public DateX addMonths(long n)  { return new DateX(at.plusMonths(n)); }
    public DateX addYears(long n)   { return new DateX(at.plusYears(n)); }

    public DateX subSeconds(long n) { return addSeconds(-n); }
    public DateX subMinutes(long n) { return addMinutes(-n); }
    public DateX subHours(long n)   { return addHours(-n); }
    public DateX subDays(long n)    { return addDays(-n); }
    public DateX subWeeks(long n)   { return addWeeks(-n); }
    public DateX subMonths(long n)  { return addMonths(-n); }
    public DateX subYears(long n)   { return addYears(-n); }

    // ================= 对齐 =================

    public DateX startOfDay()   { return new DateX(at.with(LocalTime.MIN)); }
    public DateX endOfDay()     { return new DateX(at.with(LocalTime.MAX)); }
    public DateX startOfMonth() { return new DateX(at.withDayOfMonth(1).with(LocalTime.MIN)); }

    public DateX endOfMonth() {
        return new DateX(at.withDayOfMonth(at.toLocalDate().lengthOfMonth())
                           .with(LocalTime.MAX));
    }

    /**
     * 本周的开始 —— **周一**。
     *
     * <p>ISO-8601 规定一周从周一开始,而 Carbon 默认也是周一
     * (PHP 的 {@code strtotime('this week')} 同样)。
     * 美式习惯的周日开始没有跟 —— 两种都对,但必须选一个并说清,
     * 否则「本周的订单」在两个报表里会差一天。
     */
    public DateX startOfWeek() {
        return new DateX(at.with(DayOfWeek.MONDAY).with(LocalTime.MIN));
    }

    public DateX endOfWeek() {
        return new DateX(at.with(DayOfWeek.SUNDAY).with(LocalTime.MAX));
    }

    public DateX startOfYear() {
        return new DateX(at.withDayOfYear(1).with(LocalTime.MIN));
    }

    public DateX endOfYear() {
        return new DateX(at.withDayOfYear(at.toLocalDate().lengthOfYear())
                           .with(LocalTime.MAX));
    }

    // ================= 时区 =================

    /** 换时区 —— **同一个时刻**的另一种表示,不是改时间 */
    public DateX timezone(String zone) {
        return new DateX(at.withZoneSameInstant(ZoneId.of(zone)));
    }

    public DateX timezone(ZoneId zone) {
        return new DateX(at.withZoneSameInstant(zone));
    }

    public ZoneId zone() {
        return at.getZone();
    }

    // ================= 判断 =================

    public boolean isWeekend() {
        DayOfWeek day = at.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public boolean isWeekday() {
        return !isWeekend();
    }

    public boolean isPast()   { return at.toInstant().isBefore(Instant.now()); }
    public boolean isFuture() { return at.toInstant().isAfter(Instant.now()); }

    public boolean isToday() {
        return at.toLocalDate().equals(LocalDate.now(at.getZone()));
    }

    public boolean isBefore(DateX other) { return at.toInstant().isBefore(other.at.toInstant()); }
    public boolean isAfter(DateX other)  { return at.toInstant().isAfter(other.at.toInstant()); }

    /** 同一天吗 —— 按**这个实例的时区**判断 */
    public boolean isSameDay(DateX other) {
        return at.toLocalDate().equals(other.at.withZoneSameInstant(at.getZone())
                                               .toLocalDate());
    }

    // ================= 差值 =================

    public long diffInSeconds(DateX other) { return diff(other, ChronoUnit.SECONDS); }
    public long diffInMinutes(DateX other) { return diff(other, ChronoUnit.MINUTES); }
    public long diffInHours(DateX other)   { return diff(other, ChronoUnit.HOURS); }
    public long diffInDays(DateX other)    { return diff(other, ChronoUnit.DAYS); }

    /**
     * 绝对差值。
     *
     * <p>取绝对值与 Carbon 一致:{@code diffInDays} 回答
     * 「相差几天」,而方向由 {@link #isBefore} 回答。
     * 带符号的差值会让一半的调用点忘记处理负数。
     */
    private long diff(DateX other, ChronoUnit unit) {
        return Math.abs(unit.between(at.toInstant(), other.at.toInstant()));
    }

    /**
     * 人类可读的相对时间 —— {@code "3 天前"} / {@code "2 小时后"}。
     *
     * <p>这是整个类里最常被用到的方法,也是 {@code java.time}
     * **完全不提供**的那一个。
     *
     * <h2>⚠ 只有中文</h2>
     * <p>本地化需要一个消息目录,而这个框架**还没有本地化模块**
     * (docs/11 §1.3 那条 {@code Mail.to(user).locale(...)} 同样因此
     * 是空的)。
     *
     * <p>硬编码中文而不是英文:这个项目的文档与注释都是中文,
     * 假装支持多语言反而更误导。本地化模块落地后这里换成查目录。
     *
     * <p>阈值的选择与 Carbon 一致:小于一分钟说「刚刚」,
     * 小于一小时说分钟,以此类推。月与年按 30 / 365 天近似 ——
     * 「3 个月前」不需要日历级的精确,而精确计算会让
     * 「2 月 29 日的一年前」变成一个要讨论的问题。
     */
    public String diffForHumans() {
        return diffForHumans(now());
    }

    public String diffForHumans(DateX reference) {
        long seconds = ChronoUnit.SECONDS.between(at.toInstant(),
                                                  reference.at.toInstant());
        boolean past = seconds >= 0;
        long abs = Math.abs(seconds);
        String suffix = past ? "前" : "后";

        if (abs < 60) return past ? "刚刚" : "马上";
        if (abs < 3600)        return (abs / 60) + " 分钟" + suffix;
        if (abs < 86_400)      return (abs / 3600) + " 小时" + suffix;
        if (abs < 2_592_000)   return (abs / 86_400) + " 天" + suffix;
        if (abs < 31_536_000)  return (abs / 2_592_000) + " 个月" + suffix;
        return (abs / 31_536_000) + " 年" + suffix;
    }

    // ================= 格式化 =================

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public String toDateTimeString() { return at.format(DATE_TIME); }
    public String toDateString()     { return at.format(DATE); }
    public String toTimeString()     { return at.format(TIME); }
    public String toIsoString()      { return at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); }

    public String format(String pattern) {
        return at.format(DateTimeFormatter.ofPattern(pattern));
    }

    public String format(String pattern, Locale locale) {
        return at.format(DateTimeFormatter.ofPattern(pattern, locale));
    }

    // ================= 取值 =================

    public int year()       { return at.getYear(); }
    public int month()      { return at.getMonthValue(); }
    public int dayOfMonth() { return at.getDayOfMonth(); }
    public int hour()       { return at.getHour(); }
    public int minute()     { return at.getMinute(); }
    public int second()     { return at.getSecond(); }
    public DayOfWeek dayOfWeek() { return at.getDayOfWeek(); }

    public long epochSecond() { return at.toEpochSecond(); }
    public long epochMilli()  { return at.toInstant().toEpochMilli(); }

    // ================= 逃生口与互操作 =================

    /** 底层对象 —— 这里没包的能力从它拿 */
    public ZonedDateTime toZonedDateTime() { return at; }

    public Instant toInstant()             { return at.toInstant(); }
    public LocalDate toLocalDate()         { return at.toLocalDate(); }
    public LocalDateTime toLocalDateTime() { return at.toLocalDateTime(); }

    public Duration until(DateX other) {
        return Duration.between(at.toInstant(), other.at.toInstant());
    }

    // ================= 相等与排序 =================

    /**
     * 按**时刻**比较,不按表示。
     *
     * <p>于是 {@code 10:00+08:00} 与 {@code 02:00Z} 相等 ——
     * 它们是同一个时刻。按 {@code ZonedDateTime.equals} 会判不等
     * (它连时区一起比),而那几乎从来不是调用方想问的问题。
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof DateX d && at.toInstant().equals(d.at.toInstant());
    }

    @Override
    public int hashCode() {
        return at.toInstant().hashCode();
    }

    @Override
    public int compareTo(DateX other) {
        return at.toInstant().compareTo(other.at.toInstant());
    }

    /** {@code toString} 走 {@code toDateTimeString} —— 模板里直接输出就对 */
    @Override
    public String toString() {
        return toDateTimeString();
    }
}
