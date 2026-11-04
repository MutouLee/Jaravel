package io.jaravel.spring.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.ShouldQueue;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;
import io.jaravel.scheduler.Schedule;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code queue:*} 与 {@code schedule:*} 的 CLI 入口(docs/13 §2.4)。
 *
 * <p>**真 SQLite、真 QueueWorker、真 Scheduler** —— 这些命令的能力全在模块里,
 * 它们的价值在「参数翻译对不对、输出有没有用」,而那两件事只有跑真的
 * 才看得出来(一个 {@code --once} 翻译错成「只限一个任务但不停」的 worker
 * 在替身上看不出区别,在真队列上会挂住)。
 */
class QueueScheduleCommandsTest {

    static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();

    public static class Succeeds implements ShouldQueue {
        private final int id;
        public Succeeds(int id) { this.id = id; }
        public void handle() { LOG.add("ok-" + id); }
    }

    public static class FailsAlways implements ShouldQueue {
        @Override public int tries() { return 1; }
        public void handle() { throw new IllegalStateException("这个任务总是失败"); }
    }

    private java.sql.Connection jdbc;
    private Connection connection;
    private QueueManager queues;
    private FailedJobStore failedJobs;
    private JobSerializer serializer;
    private Schedule schedule;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() throws SQLException {
        LOG.clear();
        Application.setEnvironment("local");

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique,
                        queue text not null,
                        payload text not null,
                        attempts integer not null default 0,
                        reserved_at text null,
                        available_at text not null,
                        created_at text not null)
                    """);
            st.execute("""
                    create table failed_jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique,
                        connection text not null,
                        queue text not null,
                        display_name text not null,
                        payload text not null,
                        exception text not null,
                        failed_at text not null)
                    """);
        }

        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        serializer = new JobSerializer();
        failedJobs = new DatabaseFailedJobStore(connection);

        var container = new SimpleContainer();
        container.instance(Connection.class, connection);
        Application.setContainer(container);

        queues = new QueueManager(serializer)
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .defaultConnection("database")
                .defaultQueue("default")
                .afterCommitByDefault(false);
        Queue.use(queues);

        schedule = new Schedule(null, null);
        scheduler = new Scheduler(schedule);

        var invoker = new JobInvoker(Application::make);
        Artisan.use(new CommandRegistry()
                .register(QueueWorkCommand.class,
                        () -> new QueueWorkCommand(() -> queues, () -> failedJobs, () -> invoker))
                .register(QueueFailedCommand.class, () -> new QueueFailedCommand(() -> failedJobs))
                .register(QueueRetryCommand.class,
                        () -> new QueueRetryCommand(() -> queues, () -> failedJobs, () -> serializer))
                .register(QueueFlushCommand.class, () -> new QueueFlushCommand(() -> failedJobs))
                .register(ScheduleListCommand.class, () -> new ScheduleListCommand(() -> scheduler))
                .register(ScheduleRunCommand.class, () -> new ScheduleRunCommand(() -> scheduler))
                .register(ScheduleTestCommand.class, () -> new ScheduleTestCommand(() -> scheduler))
                .register(ScheduleClearMutexesCommand.class,
                        () -> new ScheduleClearMutexesCommand(() -> scheduler)));
    }

    @AfterEach
    void tearDown() throws SQLException {
        Artisan.reset();
        Queue.reset();
        Application.setContainer(null);
        Application.setEnvironment("local");
        jdbc.close();
    }

    private static javax.sql.DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("isClosed".equals(method.getName())) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String pw) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private String run(String command, String... args) {
        Artisan.call(command, args);
        return Artisan.output();
    }

    // ================= queue:work =================

    @Nested
    @DisplayName("queue:work")
    class Work {

        @Test
        @DisplayName("★ --once 处理一个任务就退出,而不是空转等第二个")
        void once() {
            Queue.dispatch(new Succeeds(1));
            Queue.dispatch(new Succeeds(2));

            assertThat(Artisan.call("queue:work", "--once")).isZero();

            assertThat(LOG)
                    .as("""
                            --once 要同时意味着 stop-when-empty ——
                            只给 maxJobs=1 的话,一个空队列会让它一直歇着
                            等那一个任务,而那在测试里表现为「挂住了」。""")
                    .hasSize(1);
            assertThat(Artisan.output()).contains("处理 1 个").contains("成功 1");
        }

        @Test
        @DisplayName("--stop-when-empty 把队列清空后退出")
        void stopWhenEmpty() {
            Queue.dispatch(new Succeeds(1));
            Queue.dispatch(new Succeeds(2));
            Queue.dispatch(new Succeeds(3));

            assertThat(Artisan.call("queue:work", "--stop-when-empty")).isZero();
            assertThat(LOG).hasSize(3);
        }

        @Test
        @DisplayName("★ 有任务失败时退出码非 0 —— CI 与监控要能看出来")
        void failureMakesItNonZero() {
            Queue.dispatch(new FailsAlways());

            assertThat(Artisan.call("queue:work", "--stop-when-empty"))
                    .as("退出码 0 会让 failed 表没人去看")
                    .isEqualTo(1);
            assertThat(Artisan.output()).contains("失败 1");
        }

        @Test
        @DisplayName("★ --queue 的顺序就是优先级(单并发下才能断言顺序)")
        void queuePriority() {
            Queue.job(new Succeeds(1)).onQueue("low").dispatch();
            Queue.job(new Succeeds(2)).onQueue("high").dispatch();

            // --concurrency=1:默认并发是 10,两个任务会同时跑,
            // 那时 LOG 的顺序是竞态 —— 断言它会得到一个偶尔失败的测试,
            // 而那种测试最后总是被人加 @Disabled
            Artisan.call("queue:work", "--queue=high,low", "--concurrency=1",
                    "--stop-when-empty");

            assertThat(LOG).containsExactly("ok-2", "ok-1");
        }

        @Test
        @DisplayName("队列名与连接名都报在启动那一行 —— 「它到底在听哪个队列」")
        void reportsWhatItListensTo() {
            Queue.dispatch(new Succeeds(1));

            assertThat(run("queue:work", "--once"))
                    .contains("worker 启动").contains("database").contains("default");
        }
    }

    // ================= queue:failed / retry / flush =================

    @Nested
    @DisplayName("queue:failed / retry / flush")
    class Failed {

        private void produceOneFailure() {
            Queue.dispatch(new FailsAlways());
            Artisan.call("queue:work", "--stop-when-empty");
            assertThat(failedJobs.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("没有失败任务时说清,而不是画空表")
        void noFailures() {
            assertThat(run("queue:failed")).contains("没有失败的任务");
        }

        @Test
        @DisplayName("★ 表里带 id 与 uuid 前缀 —— 后者是与日志串起来的线索")
        void listsFailures() {
            produceOneFailure();

            String out = run("queue:failed");

            assertThat(out).contains("FailsAlways")
                           .as("""
                                   原因取的是**根因**而不是第一行:worker 会把任务的异常
                                   包一层(JobFailedException: 已尝试 N 次…),
                                   于是每一行都长得一样,而真正区分它们的那句话
                                   在最后一个 Caused by 上。""")
                           .contains("这个任务总是失败")
                           .contains("queue:retry");
        }

        @Test
        @DisplayName("★ retry 把任务放回原来的连接,并删掉失败记录")
        void retryPutsItBack() {
            produceOneFailure();
            long id = failedJobs.all().get(0).id();

            assertThat(Artisan.call("queue:retry", String.valueOf(id))).isZero();

            assertThat(failedJobs.count())
                    .as("""
                            不删的话下一次 retry all 会再放一遍 ——
                            那是「同一个任务跑了两遍」最常见的来源。""")
                    .isZero();
            assertThat(connection.select("select count(*) as c from jobs").first()
                    .map(r -> io.jaravel.support.Cast.to(r.get("c"), Integer.class)).orElse(0))
                    .as("任务回到了 jobs 表")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("retry all 放回全部")
        void retryAll() {
            produceOneFailure();
            Queue.dispatch(new FailsAlways());
            Artisan.call("queue:work", "--stop-when-empty");

            assertThat(Artisan.call("queue:retry", "all")).isZero();
            assertThat(failedJobs.count()).isZero();
        }

        @Test
        @DisplayName("retry 给了一个不存在的 id,或者给了别的东西 —— 都说清")
        void retryBadTarget() {
            assertThat(Artisan.call("queue:retry", "999")).isEqualTo(1);
            assertThat(Artisan.output()).contains("没有 id 为 999");

            assertThat(Artisan.call("queue:retry", "蓝色")).isEqualTo(1);
            assertThat(Artisan.output()).contains("既不是 id 也不是 all");
        }

        @Test
        @DisplayName("★ flush 要确认 —— 删了就不能重试了,而不可交互时默认答不")
        void flushAsksFirst() {
            produceOneFailure();

            assertThat(Artisan.call("queue:flush")).isEqualTo(1);
            assertThat(Artisan.output()).contains("已取消").contains("--older-than");
            assertThat(failedJobs.count())
                    .as("即便不在生产环境也先确认:失败记录是重试的唯一依据")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("--older-than 只清旧的")
        void pruneOld() {
            produceOneFailure();

            assertThat(Artisan.call("queue:flush", "--older-than=7")).isZero();
            assertThat(failedJobs.count())
                    .as("刚失败的那条不在 7 天前,所以留下")
                    .isEqualTo(1);
        }
    }

    // ================= schedule:* =================

    @Nested
    @DisplayName("schedule:*")
    class ScheduleCommands {

        @Test
        @DisplayName("没有计划任务时说清去哪登记")
        void noTasks() {
            assertThat(run("schedule:list")).contains("ScheduleDefinition");
        }

        @Test
        @DisplayName("★ 有约束的任务,下次时间标成乐观估计")
        void listMarksOptimisticTimes() {
            schedule.call("清缓存", () -> LOG.add("cache")).daily();
            schedule.call("只在工作日", () -> LOG.add("weekday")).daily().weekdays();

            String out = run("schedule:list");

            assertThat(out).contains("清缓存").contains("只在工作日");
            assertThat(out)
                    .as("""
                            不标出来的话一个 weekdays() 的任务会在列表里
                            显示一个周六的时间,而那会让人以为约束没生效。""")
                    .contains("*").contains("乐观估计");
        }

        @Test
        @DisplayName("schedule:run 执行到期的;一个都没有也是正常结果")
        void runDue() {
            schedule.call("每分钟", () -> LOG.add("tick")).everyMinute();

            assertThat(Artisan.call("schedule:run")).isZero();
            assertThat(LOG).contains("tick");

            LOG.clear();
            schedule = new Schedule(null, null);
            scheduler = new Scheduler(schedule);
            schedule.call("每年", () -> LOG.add("never")).yearly();

            assertThat(Artisan.call("schedule:run"))
                    .as("外部触发模式下这条命令每分钟跑一次,"
                            + "绝大多数分钟里什么都不该发生")
                    .isZero();
            assertThat(Artisan.output()).contains("没有到期");
        }

        @Test
        @DisplayName("★ schedule:test 绕过 cron 立即执行")
        void testRunsImmediately() {
            schedule.call("每年一次", () -> LOG.add("yearly")).yearly();

            assertThat(Artisan.call("schedule:test", "每年一次")).isZero();
            assertThat(LOG)
                    .as("""
                            「我写的这个任务到底能不能跑」不该靠等到下一个整点,
                            也不该靠临时把 cron 改成 everyMinute ——
                            后者改完经常忘了改回去。""")
                    .contains("yearly");
        }

        @Test
        @DisplayName("schedule:test 给了不存在的名字 —— 把现有的列出来")
        void testUnknownTask() {
            schedule.call("清缓存", () -> { }).daily();

            assertThat(Artisan.call("schedule:test", "清缓")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("名字写错时,下一步是「那到底叫什么」")
                    .contains("没有叫").contains("清缓存");
        }

        @Test
        @DisplayName("clear-mutexes 跑得通")
        void clearMutexes() {
            assertThat(Artisan.call("schedule:clear-mutexes")).isZero();
            assertThat(Artisan.output()).contains("已清掉");
        }
    }
}
