package io.jaravel.scheduler;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 五段式 cron 表达式:{@code 分 时 日 月 周}。
 *
 * <pre>{@code
 * CronExpression.parse("0 3 * * *").matches(now);     // 每天 03:00
 * CronExpression.parse("*​/5 * * * 1-5").matches(now);  // 工作日每 5 分钟
 * }</pre>
 *
 * <h2>为什么自己写而不是引一个库</h2>
 * <p>五段式 cron 的语义**不多**(数字、{@code *}、{@code a-b}、
 * {@code a,b}、{@code *}{@code /n}),而引入一个库要付的代价是:
 * 它的扩展语法(Quartz 的 {@code L} / {@code W} / {@code #}、
 * 六段/七段式)会悄悄被用户用上,然后 Jaravel 的频率方法
 * 与那些语法之间出现行为差异,而文档里说的是「五段式」。
 *
 * <p>写在这里也让**错误消息**能说清是哪一段的哪个值出了问题 ——
 * 一个 {@code IllegalArgumentException: invalid cron} 对用户毫无帮助。
 *
 * <h2>⚠ 日与周同时限制时是「或」而不是「且」</h2>
 * <p>这是 cron 最有名的坑,POSIX 的规定是:
 *
 * <blockquote>
 * 当「日」与「周」都不是 {@code *} 时,两者取**或**。
 * </blockquote>
 *
 * <p>所以 {@code 0 0 13 * 5} 是「每月 13 号**或**每周五」,
 * 不是「13 号恰好是周五」。
 *
 * <p>Jaravel 保留这个行为(与所有 cron 实现一致),但在
 * {@link #describeDayLogic()} 里把它说出来 ——
 * {@code schedule:list} 会显示这句话。
 *
 * <p>不「修正」它的理由:一个与系统 cron 行为不同的 cron 实现
 * 会让从 crontab 迁移过来的表达式静默改变含义,而那比坑本身更糟。
 */
public final class CronExpression {

    private final String expression;

    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);     // 1..31
    private final BitSet months = new BitSet(13);          // 1..12
    private final BitSet daysOfWeek = new BitSet(7);       // 0..6,0 = 周日

    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronExpression(String expression, String[] fields) {
        this.expression = expression;

        parseField(fields[0], minutes, 0, 59, "分", null);
        parseField(fields[1], hours, 0, 23, "时", null);
        parseField(fields[2], daysOfMonth, 1, 31, "日", null);
        parseField(fields[3], months, 1, 12, "月", MONTH_NAMES);
        parseField(fields[4], daysOfWeek, 0, 6, "周", DAY_NAMES);

        this.dayOfMonthRestricted = !isWildcard(fields[2]);
        this.dayOfWeekRestricted = !isWildcard(fields[4]);
    }

    private static final String[] MONTH_NAMES = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};

    private static final String[] DAY_NAMES = {
            "sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new InvalidCronException("cron 表达式不能为空。格式是「分 时 日 月 周」,"
                    + "例如 \"0 3 * * *\" 表示每天 03:00。");
        }

        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new InvalidCronException("""
                    cron 表达式要有 5 段(分 时 日 月 周),"%s" 有 %d 段。
                      Jaravel 只支持**标准五段式** —— 不支持 Quartz 的
                      六段/七段式(带秒或年)。
                      需要「每 N 秒」的话调度本身就不是合适的工具:
                      内置调度器每分钟对齐唤醒一次,
                      秒级任务应该用一个常驻的循环或队列 worker。"""
                    .formatted(expression, fields.length));
        }
        return new CronExpression(expression.trim(), fields);
    }

    /** 这个表达式语法对吗。用于配置校验,不想要异常时用 */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (InvalidCronException e) {
            return false;
        }
    }

    // ================= 解析 =================

    private static boolean isWildcard(String field) {
        return field.equals("*") || field.equals("?");
    }

    private void parseField(String field, BitSet target, int min, int max,
                            String label, String[] names) {
        for (String part : field.split(",")) {
            parsePart(part.trim(), target, min, max, label, names);
        }
        if (target.isEmpty()) {
            throw new InvalidCronException(
                    "cron 的「%s」段 \"%s\" 没有匹配任何值 —— 这个任务永远不会运行。"
                            .formatted(label, field));
        }
    }

    private void parsePart(String part, BitSet target, int min, int max,
                           String label, String[] names) {
        // 步长:`*/5` 或 `10-30/5`
        int slash = part.indexOf('/');
        int step = 1;
        String range = part;

        if (slash >= 0) {
            range = part.substring(0, slash);
            String stepText = part.substring(slash + 1);
            step = parseNumber(stepText, label, names, min, max, true);
            if (step <= 0) {
                throw new InvalidCronException(
                        "cron 的「%s」段步长必须是正数,得到 \"%s\"。".formatted(label, stepText));
            }
        }

        int from;
        int to;
        if (isWildcard(range)) {
            from = min;
            to = max;
        } else {
            int dash = range.indexOf('-', range.startsWith("-") ? 1 : 0);
            if (dash > 0) {
                from = parseNumber(range.substring(0, dash), label, names, min, max, false);
                to = parseNumber(range.substring(dash + 1), label, names, min, max, false);
            } else {
                from = parseNumber(range, label, names, min, max, false);
                // 有步长时单个数字表示「从它开始到上界」—— 与 crontab 一致:
                // `5/10` = 5,15,25,…。没有步长时就是它自己
                to = slash >= 0 ? max : from;
            }
        }

        if (from > to) {
            // 跨界的范围(`22-2`)—— crontab 支持它,表示绕一圈
            for (int v = from; v <= max; v += step) target.set(v);
            for (int v = min; v <= to; v += step) target.set(v);
            return;
        }
        for (int v = from; v <= to; v += step) target.set(v);
    }

    private int parseNumber(String text, String label, String[] names,
                            int min, int max, boolean isStep) {
        String trimmed = text.trim();

        if (names != null) {
            String lower = trimmed.toLowerCase();
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(lower)) {
                    // 月份名从 1 开始,星期名从 0 开始 —— 用 min 偏移
                    return i + min;
                }
            }
        }

        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new InvalidCronException("""
                    cron 的「%s」段里 "%s" 不是一个合法的值。
                      允许:数字、*、a-b、a,b,c、*%s/n%s
                      %s"""
                    .formatted(label, trimmed, "", "",
                               names == null ? "" : "也允许名字:" + String.join(",", names)));
        }

        // 周日既是 0 也是 7 —— crontab 的惯例。不接受 7 的话
        // 从 crontab 复制过来的表达式会报错,而那个报错很让人困惑
        if (names == DAY_NAMES && value == 7) return 0;

        if (!isStep && (value < min || value > max)) {
            throw new InvalidCronException(
                    "cron 的「%s」段的值 %d 超出范围 %d-%d。".formatted(label, value, min, max));
        }
        return value;
    }

    // ================= 匹配 =================

    /**
     * 这个时刻该运行吗。
     *
     * <p>**秒被忽略** —— cron 的精度是分钟。调度器每分钟对齐唤醒一次,
     * 所以同一分钟内多次调用会得到同一个答案。
     */
    public boolean matches(ZonedDateTime time) {
        if (!minutes.get(time.getMinute())) return false;
        if (!hours.get(time.getHour())) return false;
        if (!months.get(time.getMonthValue())) return false;

        return matchesDay(time);
    }

    /**
     * 日/周的匹配 —— 那个「或」的规则就在这里。
     *
     * <p>四种情况:
     * <ul>
     *   <li>两者都是 {@code *} → 任何天都匹配;</li>
     *   <li>只限制了日 → 按日匹配;</li>
     *   <li>只限制了周 → 按周匹配;</li>
     *   <li>**两者都限制了 → 或**(POSIX 规定)。</li>
     * </ul>
     */
    private boolean matchesDay(ZonedDateTime time) {
        int dom = time.getDayOfMonth();
        // DayOfWeek 的 MONDAY=1 … SUNDAY=7;cron 的周日是 0
        int dow = time.getDayOfWeek().getValue() % 7;

        if (!dayOfMonthRestricted && !dayOfWeekRestricted) return true;
        if (dayOfMonthRestricted && !dayOfWeekRestricted) return daysOfMonth.get(dom);
        if (!dayOfMonthRestricted) return daysOfWeek.get(dow);

        return daysOfMonth.get(dom) || daysOfWeek.get(dow);
    }

    /**
     * 下一次运行时刻(不含 {@code from} 本身)。
     *
     * <p>实现是**逐分钟试**,上限四年。看起来笨,但:
     * <ul>
     *   <li>四年覆盖了闰年与所有「2 月 29 日」的情形;</li>
     *   <li>它只在 {@code schedule:list} 这类场合调用,不在热路径上;</li>
     *   <li>按字段反推的版本要处理「日/周取或」「月末天数不同」
     *       这些交互,而那是 cron 实现里最容易写错的部分 ——
     *       而写错的症状是「某个任务在某些月份不跑」,极难发现。</li>
     * </ul>
     *
     * @return 下一次时刻;四年内都不匹配时返回 empty
     *         (例如 {@code 0 0 30 2 *} —— 2 月 30 日不存在)
     */
    public java.util.Optional<ZonedDateTime> nextRun(ZonedDateTime from) {
        ZonedDateTime candidate = from.withSecond(0).withNano(0).plusMinutes(1);

        // 4 年的分钟数
        int limit = 4 * 366 * 24 * 60;
        for (int i = 0; i < limit; i++) {
            if (matches(candidate)) return java.util.Optional.of(candidate);
            candidate = candidate.plusMinutes(1);
        }
        return java.util.Optional.empty();
    }

    /** 接下来 n 次运行时刻 —— {@code schedule:list} 用 */
    public List<ZonedDateTime> nextRuns(ZonedDateTime from, int n) {
        var out = new ArrayList<ZonedDateTime>(n);
        ZonedDateTime cursor = from;
        for (int i = 0; i < n; i++) {
            var next = nextRun(cursor);
            if (next.isEmpty()) break;
            out.add(next.get());
            cursor = next.get();
        }
        return out;
    }

    /**
     * 日/周规则的人话说明。
     *
     * <p>{@code schedule:list} 会在「日与周都被限制」时显示它 ——
     * 那个「或」的语义是 cron 最有名的坑,而一个只在列表里
     * 显示 cron 字符串的工具帮不上忙。
     */
    public String describeDayLogic() {
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return "⚠ 日与周都被限制 —— 按 POSIX 规定两者取**或**,"
                 + "即「符合日 或 符合周」都会运行,不是「同时符合」。";
        }
        return "";
    }

    public String expression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CronExpression c && expression.equals(c.expression);
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }

    /** cron 语法错误。**不可恢复** —— 表达式是代码里写死的 */
    public static class InvalidCronException extends IllegalArgumentException {
        public InvalidCronException(String message) {
            super(message);
        }
    }
}
