package io.jaravel.scheduler;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一个计划任务:**做什么** + **什么时候** + **什么条件下**(docs/10 §3)。
 *
 * <pre>{@code
 * s.job(SyncInventory.class)
 *  .everyTenMinutes()
 *  .between("08:00", "22:00")
 *  .weekdays()
 *  .timezone("Asia/Shanghai")
 *  .withoutOverlapping(Duration.ofMinutes(10))
 *  .onOneServer()
 *  .when(() -> Config.bool("features.inventory-sync"))
 *  .description("库存同步");
 * }</pre>
 *
 * <h2>频率方法都落到一个 cron 表达式上</h2>
 * <p>{@code dailyAt("03:00")} 就是 {@code 0 3 * * *}。这样做的好处是
 * 「下次什么时候跑」只有一套实现({@link CronExpression#nextRun}),
 * 而 {@code schedule:list} 能把每个任务的 cron 原样显示出来 ——
 * 于是「我以为它每天跑」这类误解能在列表里当场发现。
 *
 * <h2>约束不是 cron,是过滤器</h2>
 * <p>{@code between("08:30", "22:15")} 没法用 cron 表达(cron 的时间
 * 粒度在「时」与「分」上是独立的,表达不了「08:30 到 22:15 之间」)。
 * 所以约束是**叠加在 cron 之上的谓词**。
 *
 * <p>这个分工要说清,因为它有一个可见的后果:{@code schedule:list}
 * 显示的 cron 不包含约束,所以「下次运行时间」是**乐观估计** ——
 * 真正运行时还要过一遍约束。列表里会标出这一点。
 *
 * <h2>链式方法返回 this,但频率方法会**覆盖**而不是叠加</h2>
 * <p>{@code s.job(X.class).daily().hourly()} 的结果是 hourly ——
 * 后者覆盖前者。这与约束方法({@code weekdays()} 等)不同,
 * 后者是叠加的。
 *
 * <p>覆盖而不是报错的理由:它让「改一个任务的频率」变成删一个方法调用,
 * 而报错会让人以为自己写错了语法。但**连写两个频率几乎总是笔误**,
 * 所以 debug 模式下会警告一次。
 */
public final class ScheduledTask {

    /** 做什么 */
    private final TaskAction action;

    private CronExpression cron = CronExpression.parse("* * * * *");
    private boolean frequencySet;

    private ZoneId timezone = ZoneId.systemDefault();

    /** 叠加在 cron 之上的约束 */
    private final List<Filter> filters = new ArrayList<>();

    private String description;
    private Duration withoutOverlappingFor;
    private boolean onOneServer;
    private boolean runInBackground;

    private final List<Runnable> beforeHooks = new ArrayList<>();
    private final List<Runnable> afterHooks = new ArrayList<>();
    private final List<Consumer<TaskOutcome>> successHooks = new ArrayList<>();
    private final List<Consumer<TaskOutcome>> failureHooks = new ArrayList<>();

    private String pingOnSuccess;
    private String pingOnFailure;

    ScheduledTask(TaskAction action) {
        this.action = action;
    }

    // ================= 频率 =================

    /**
     * 直接给 cron 表达式。
     *
     * <p>语法错误**在注册时**就抛 —— 不是等到第一次该运行的时候。
     * 后者意味着一个写错的 cron 要等几小时才暴露,而那时调用栈
     * 在调度线程里,离写错的那行代码很远。
     */
    public ScheduledTask cron(String expression) {
        return setFrequency(CronExpression.parse(expression));
    }

    private ScheduledTask setFrequency(CronExpression next) {
        if (frequencySet && io.jaravel.core.Application.isDebug()) {
            System.err.printf("""
                    [jaravel] 警告:计划任务 %s 设了两次频率
                              (%s 被 %s 覆盖)。
                              后者生效。连写两个频率几乎总是笔误。
                    %n""", label(), cron.expression(), next.expression());
        }
        this.cron = next;
        this.frequencySet = true;
        return this;
    }

    public ScheduledTask everyMinute()          { return cron("* * * * *"); }
    public ScheduledTask everyTwoMinutes()      { return cron("*/2 * * * *"); }
    public ScheduledTask everyFiveMinutes()     { return cron("*/5 * * * *"); }
    public ScheduledTask everyTenMinutes()      { return cron("*/10 * * * *"); }
    public ScheduledTask everyFifteenMinutes()  { return cron("*/15 * * * *"); }
    public ScheduledTask everyThirtyMinutes()   { return cron("*/30 * * * *"); }

    public ScheduledTask hourly()               { return cron("0 * * * *"); }

    /** 每小时的第 n 分钟 */
    public ScheduledTask hourlyAt(int minute) {
        requireRange(minute, 0, 59, "hourlyAt 的分钟");
        return cron(minute + " * * * *");
    }

    public ScheduledTask everyTwoHours()        { return cron("0 */2 * * *"); }
    public ScheduledTask everyThreeHours()      { return cron("0 */3 * * *"); }
    public ScheduledTask everySixHours()        { return cron("0 */6 * * *"); }

    public ScheduledTask daily()                { return cron("0 0 * * *"); }

    /** 每天的某个时刻,{@code "13:00"} 或 {@code "13"} */
    public ScheduledTask dailyAt(String time) {
        LocalTime at = parseTime(time, "dailyAt");
        return cron("%d %d * * *".formatted(at.getMinute(), at.getHour()));
    }

    /** 每天两次,参数是小时 */
    public ScheduledTask twiceDaily(int firstHour, int secondHour) {
        requireRange(firstHour, 0, 23, "twiceDaily 的第一个小时");
        requireRange(secondHour, 0, 23, "twiceDaily 的第二个小时");
        return cron("0 %d,%d * * *".formatted(firstHour, secondHour));
    }

    public ScheduledTask weekly()               { return cron("0 0 * * 0"); }

    /**
     * 每周的某天某时刻。
     *
     * @param dayOfWeek 0 = 周日 … 6 = 周六(与 cron 一致)
     */
    public ScheduledTask weeklyOn(int dayOfWeek, String time) {
        requireRange(dayOfWeek, 0, 7, "weeklyOn 的星期");
        LocalTime at = parseTime(time, "weeklyOn");
        return cron("%d %d * * %d".formatted(at.getMinute(), at.getHour(), dayOfWeek));
    }

    public ScheduledTask monthly()              { return cron("0 0 1 * *"); }

    public ScheduledTask monthlyOn(int dayOfMonth, String time) {
        requireRange(dayOfMonth, 1, 31, "monthlyOn 的日");
        LocalTime at = parseTime(time, "monthlyOn");
        return cron("%d %d %d * *".formatted(at.getMinute(), at.getHour(), dayOfMonth));
    }

    public ScheduledTask twiceMonthly(int firstDay, int secondDay) {
        requireRange(firstDay, 1, 31, "twiceMonthly 的第一天");
        requireRange(secondDay, 1, 31, "twiceMonthly 的第二天");
        return cron("0 0 %d,%d * *".formatted(firstDay, secondDay));
    }

    /**
     * 每月最后一天。
     *
     * <p>**标准 cron 表达不了这个** —— 它没有 Quartz 的 {@code L}。
     * 所以实现是「每天 00:00 的 cron + 一个『今天是本月最后一天吗』
     * 的过滤器」。
     *
     * <p>可见的后果:{@code schedule:list} 里它的 cron 显示为
     * {@code 0 0 * * *},而「下次运行」是乐观估计。
     * 列表会标注它有约束 —— 见类注释。
     */
    public ScheduledTask lastDayOfMonth() {
        return lastDayOfMonth("00:00");
    }

    public ScheduledTask lastDayOfMonth(String time) {
        LocalTime at = parseTime(time, "lastDayOfMonth");
        cron("%d %d * * *".formatted(at.getMinute(), at.getHour()));
        return filter("每月最后一天",
                now -> now.getDayOfMonth() == now.toLocalDate().lengthOfMonth());
    }

    public ScheduledTask quarterly()            { return cron("0 0 1 1,4,7,10 *"); }
    public ScheduledTask yearly()               { return cron("0 0 1 1 *"); }

    // ================= 约束 =================

    /**
     * 加一个约束。
     *
     * <p>约束是**叠加**的:多个 {@code filter} 全部满足才运行。
     * 这与频率方法的「覆盖」相反 —— 见类注释。
     */
    public ScheduledTask filter(String reason, java.util.function.Predicate<ZonedDateTime> test) {
        filters.add(new Filter(reason, test));
        return this;
    }

    public ScheduledTask weekdays() {
        return filter("仅工作日",
                now -> now.getDayOfWeek().getValue() <= 5);
    }

    public ScheduledTask weekends() {
        return filter("仅周末",
                now -> now.getDayOfWeek().getValue() >= 6);
    }

    public ScheduledTask mondays()    { return onDays("仅周一", 1); }
    public ScheduledTask tuesdays()   { return onDays("仅周二", 2); }
    public ScheduledTask wednesdays() { return onDays("仅周三", 3); }
    public ScheduledTask thursdays()  { return onDays("仅周四", 4); }
    public ScheduledTask fridays()    { return onDays("仅周五", 5); }
    public ScheduledTask saturdays()  { return onDays("仅周六", 6); }
    public ScheduledTask sundays()    { return onDays("仅周日", 0); }

    /**
     * 只在这些星期运行。
     *
     * @param days 0 = 周日 … 6 = 周六
     */
    public ScheduledTask days(int... days) {
        return onDays("仅星期 " + java.util.Arrays.toString(days), days);
    }

    private ScheduledTask onDays(String reason, int... days) {
        var allowed = new java.util.HashSet<Integer>();
        for (int day : days) {
            requireRange(day, 0, 7, "星期");
            allowed.add(day == 7 ? 0 : day);
        }
        return filter(reason, now -> allowed.contains(now.getDayOfWeek().getValue() % 7));
    }

    /**
     * 只在一天中的某个时间段内运行。
     *
     * <p>**包含两端**。{@code start} 晚于 {@code end} 时表示跨午夜
     * ({@code between("22:00", "02:00")})。
     */
    public ScheduledTask between(String start, String end) {
        LocalTime from = parseTime(start, "between");
        LocalTime to = parseTime(end, "between");
        return filter("仅 %s–%s".formatted(start, end),
                now -> inWindow(now.toLocalTime(), from, to));
    }

    /** 这个时间段内**不**运行 */
    public ScheduledTask unlessBetween(String start, String end) {
        LocalTime from = parseTime(start, "unlessBetween");
        LocalTime to = parseTime(end, "unlessBetween");
        return filter("避开 %s–%s".formatted(start, end),
                now -> !inWindow(now.toLocalTime(), from, to));
    }

    private static boolean inWindow(LocalTime now, LocalTime from, LocalTime to) {
        if (from.isAfter(to)) {
            // 跨午夜:22:00–02:00
            return !now.isBefore(from) || !now.isAfter(to);
        }
        return !now.isBefore(from) && !now.isAfter(to);
    }

    /** 条件为真才运行 */
    public ScheduledTask when(BooleanSupplier condition) {
        return filter("when(...) 返回 false", now -> condition.getAsBoolean());
    }

    /** 条件为真就跳过 */
    public ScheduledTask skip(BooleanSupplier condition) {
        return filter("skip(...) 返回 true", now -> !condition.getAsBoolean());
    }

    /**
     * 时区。
     *
     * <p>**默认是 JVM 的默认时区**,不是 UTC。
     *
     * <p>这个选择值得解释:UTC 对「每天 03:00 做日终结算」这类任务是
     * 错的 —— 业务说的 03:00 是本地时间。而默认 UTC 会让一个在
     * UTC+8 机器上开发、部署到 UTC 容器里的任务**悄悄改变运行时刻**。
     *
     * <p>代价是 JVM 默认时区本身会随部署环境变。所以生产环境应当
     * 显式 {@code timezone(...)},而 {@code schedule:list}
     * 会把每个任务的实际时区显示出来。
     */
    public ScheduledTask timezone(String zone) {
        this.timezone = ZoneId.of(zone);
        return this;
    }

    public ScheduledTask timezone(ZoneId zone) {
        this.timezone = zone;
        return this;
    }

    // ================= 并发与多实例 =================

    /**
     * 同一个任务不并发 —— 上一轮还没跑完就跳过这一轮。
     *
     * <p>**TTL 是必须的**(没有无参版本)。理由与队列的
     * {@code WithoutOverlapping} 相同:持锁的进程崩掉时,
     * 没有 TTL 的锁会**永久**挡住这个任务,而症状是
     * 「这个任务不知道从什么时候起不跑了」。
     *
     * <p>TTL 要大于任务的最长执行时间,否则第二轮会在第一轮还在跑时启动。
     */
    public ScheduledTask withoutOverlapping(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("""
                    withoutOverlapping 的 TTL 必须是正数。
                      没有 TTL 的锁在持锁进程崩掉后会**永久**挡住这个任务,
                      而症状是「这个任务不知道从什么时候起不跑了」。
                      取一个大于任务最长执行时间的值,比如 ofMinutes(10)。""");
        }
        this.withoutOverlappingFor = ttl;
        return this;
    }

    /**
     * 多实例部署下只有一个实例运行它。
     *
     * <p>⚠ **依赖共享的锁存储**(Redis / 数据库)。
     * 用 {@code caffeine} 或 {@code file} 驱动时每个实例各有一份锁,
     * 于是这个声明**完全无效** —— 每个实例都会跑。
     *
     * <p>Jaravel 在**启动时**检测这个组合并报错,而不是静默失效
     * (见 {@code SchedulerConfigCheck})。这是 docs/10 §3.4
     * 明确要求的一条。
     */
    public ScheduledTask onOneServer() {
        this.onOneServer = true;
        return this;
    }

    /**
     * 在后台跑,不阻塞调度线程。
     *
     * <p>⚠ 与 Laravel 的语义**不同**。Laravel 的 {@code runInBackground}
     * 是「fork 一个新进程」;Jaravel 是「在一个虚拟线程里跑」。
     *
     * <p>后果上的差异:
     * <ul>
     *   <li>Jaravel 的后台任务**与应用共享进程** —— 它崩掉不会连带应用,
     *       但它占的内存算在应用头上;</li>
     *   <li>Jaravel 的后台任务在应用停机时会被打断,而 Laravel 的
     *       独立进程不会。</li>
     * </ul>
     *
     * <p>**默认不开**:一个任务阻塞调度线程一分钟以上才真的需要它,
     * 而默认开会让「任务抛的异常」变得更难观察(它在另一个线程里)。
     */
    public ScheduledTask runInBackground() {
        this.runInBackground = true;
        return this;
    }

    // ================= 钩子 =================

    public ScheduledTask before(Runnable hook) {
        beforeHooks.add(hook);
        return this;
    }

    public ScheduledTask after(Runnable hook) {
        afterHooks.add(hook);
        return this;
    }

    public ScheduledTask onSuccess(Consumer<TaskOutcome> hook) {
        successHooks.add(hook);
        return this;
    }

    public ScheduledTask onFailure(Consumer<TaskOutcome> hook) {
        failureHooks.add(hook);
        return this;
    }

    /**
     * 成功后 ping 一个 URL(Healthchecks.io 之类)。
     *
     * <p>存在的理由:**「任务没跑」是最难发现的故障** ——
     * 它不产生日志、不产生异常、不产生告警。外部的心跳监控是
     * 唯一能发现它的办法,而那需要任务主动报活。
     */
    public ScheduledTask pingOnSuccess(String url) {
        this.pingOnSuccess = url;
        return this;
    }

    public ScheduledTask pingOnFailure(String url) {
        this.pingOnFailure = url;
        return this;
    }

    /** 人话描述,{@code schedule:list} 显示它 */
    public ScheduledTask description(String text) {
        this.description = text;
        return this;
    }

    // ================= 判定 =================

    /** 这个时刻该跑吗 —— cron 匹配**且**所有约束通过 */
    public boolean isDue(ZonedDateTime now) {
        return cron.matches(now.withZoneSameInstant(timezone)) && passesFilters(now);
    }

    /** cron 匹配吗(不看约束)。调度器用它决定「要不要进一步判断」 */
    public boolean cronMatches(ZonedDateTime now) {
        return cron.matches(now.withZoneSameInstant(timezone));
    }

    /**
     * 哪个约束把它挡住了。
     *
     * <p>返回第一个不通过的约束的理由,全通过时返回 empty。
     *
     * <p>有这个方法是为了让 {@code ScheduledTaskSkipped} 事件与
     * {@code schedule:test} 能说出**为什么跳过** ——
     * 「任务没跑」而没有原因是最让人没办法的一种情形。
     */
    public java.util.Optional<String> skipReason(ZonedDateTime now) {
        var local = now.withZoneSameInstant(timezone);
        for (Filter f : filters) {
            if (!f.test().test(local)) return java.util.Optional.of(f.reason());
        }
        return java.util.Optional.empty();
    }

    private boolean passesFilters(ZonedDateTime now) {
        return skipReason(now).isEmpty();
    }

    public java.util.Optional<ZonedDateTime> nextRun(ZonedDateTime from) {
        return cron.nextRun(from.withZoneSameInstant(timezone));
    }

    // ================= 访问器 =================

    public TaskAction action()                 { return action; }
    public CronExpression cronExpression()     { return cron; }
    public ZoneId zone()                       { return timezone; }
    public boolean hasFilters()                { return !filters.isEmpty(); }
    public List<String> filterReasons()        { return filters.stream().map(Filter::reason).toList(); }
    public Duration overlapTtl()               { return withoutOverlappingFor; }
    public boolean preventsOverlap()           { return withoutOverlappingFor != null; }
    public boolean runsOnOneServer()           { return onOneServer; }
    public boolean runsInBackground()          { return runInBackground; }
    public String pingSuccessUrl()             { return pingOnSuccess; }
    public String pingFailureUrl()             { return pingOnFailure; }

    List<Runnable> before()                        { return beforeHooks; }
    List<Runnable> after()                         { return afterHooks; }
    List<Consumer<TaskOutcome>> onSuccessHooks()   { return successHooks; }
    List<Consumer<TaskOutcome>> onFailureHooks()   { return failureHooks; }

    /** 任务的名字 —— 描述优先,其次是动作自己的名字 */
    public String label() {
        return description != null ? description : action.name();
    }

    /**
     * 互斥锁的 key。
     *
     * <p>**基于动作的名字而不是描述** —— 描述是给人看的,改一句话
     * 不该让锁换一个 key(换了的话上一轮正在跑的那个就挡不住这一轮了)。
     */
    public String mutexKey() {
        return "jaravel:schedule:" + action.name();
    }

    @Override
    public String toString() {
        return "%s  [%s]%s".formatted(label(), cron.expression(),
                filters.isEmpty() ? "" : " +" + filters.size() + " 个约束");
    }

    // ================= 工具 =================

    private static LocalTime parseTime(String time, String method) {
        String text = time == null ? "" : time.trim();
        try {
            if (text.matches("\\d{1,2}")) {
                return LocalTime.of(Integer.parseInt(text), 0);
            }
            if (text.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = text.split(":");
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (RuntimeException e) {
            // 落到下面统一报错
        }
        throw new IllegalArgumentException("""
                %s 的时间 "%s" 格式不对。
                  支持 "HH:mm"(如 "03:30")或 "HH"(如 "3",表示 03:00)。
                  **不支持**秒 —— 调度的精度是分钟。"""
                .formatted(method, time));
    }

    private static void requireRange(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "%s 必须在 %d-%d 之间,得到 %d。".formatted(what, min, max, value));
        }
    }

    private record Filter(String reason, java.util.function.Predicate<ZonedDateTime> test) {
    }
}
