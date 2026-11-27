package net.dobeen.jaravel.spring.commands;

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

import net.dobeen.jaravel.console.Artisan;
import net.dobeen.jaravel.console.CommandRegistry;
import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.core.container.SimpleContainer;
import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.database.JdbcConnection;
import net.dobeen.jaravel.database.grammar.SqliteGrammar;
import net.dobeen.jaravel.queue.FailedJobStore;
import net.dobeen.jaravel.queue.JobInvoker;
import net.dobeen.jaravel.queue.JobSerializer;
import net.dobeen.jaravel.queue.Queue;
import net.dobeen.jaravel.queue.QueueManager;
import net.dobeen.jaravel.queue.ShouldQueue;
import net.dobeen.jaravel.queue.store.DatabaseFailedJobStore;
import net.dobeen.jaravel.queue.store.DatabaseQueue;
import net.dobeen.jaravel.scheduler.Schedule;
import net.dobeen.jaravel.scheduler.Scheduler;

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
    private net.dobeen.jaravel.cache.Repository cache;
    private net.dobeen.jaravel.queue.BatchRepository batches;
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
            st.execute("""
                    create table job_batches (
                        id text primary key, name text null,
                        total_jobs integer not null, pending_jobs integer not null,
                        failed_jobs integer not null default 0,
                        failed_job_ids text not null default '',
                        allows_failures integer not null default 0,
                        then_callback text null, catch_callback text null,
                        finally_callback text null,
                        created_at text not null, cancelled_at text null,
                        finished_at text null)
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
        // 共享缓存的替身:queue:restart 的信号与 worker 要看同一个 store
        cache = new net.dobeen.jaravel.cache.Repository(new net.dobeen.jaravel.cache.store.ArrayStore());
        batches = new net.dobeen.jaravel.queue.store.DatabaseBatchRepository(connection, serializer);

        Artisan.use(new CommandRegistry()
                .register(QueueWorkCommand.class,
                        () -> new QueueWorkCommand(() -> queues, () -> failedJobs, () -> invoker,
                                () -> cache))
                .register(QueueForgetCommand.class, () -> new QueueForgetCommand(() -> failedJobs))
                .register(QueueRestartCommand.class, () -> new QueueRestartCommand(() -> cache))
                .register(QueueMonitorCommand.class, () -> new QueueMonitorCommand(() -> queues))
                .register(QueuePruneBatchesCommand.class,
                        () -> new QueuePruneBatchesCommand(() -> batches))
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
            String id = failedJobs.all().get(0).id();

            assertThat(Artisan.call("queue:retry", id)).isZero();

            assertThat(failedJobs.count())
                    .as("""
                            不删的话下一次 retry all 会再放一遍 ——
                            那是「同一个任务跑了两遍」最常见的来源。""")
                    .isZero();
            assertThat(connection.select("select count(*) as c from jobs").first()
                    .map(r -> net.dobeen.jaravel.support.Cast.to(r.get("c"), Integer.class)).orElse(0))
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
        @DisplayName("retry 给了一个不存在的 id —— 说清并指路")
        void retryBadTarget() {
            assertThat(Artisan.call("queue:retry", "999")).isEqualTo(1);
            assertThat(Artisan.output()).contains("没有 id 为 999");

            // ★ 不校验 id 的「形状」。它是归档存储给出的句柄:
            // database / redis 是自增编号,KV 型存储(dynamodb)是任务 uuid。
            // 这里要求「必须是数字」会让 uuid 句柄得到一条
            // 指着用户输入、而原因在存储选型的报错
            assertThat(Artisan.call("queue:retry", "蓝色")).isEqualTo(1);
            assertThat(Artisan.output())
                    .contains("没有 id 为 蓝色")
                    .contains("queue:failed");
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

    // ================= queue:forget / restart / monitor / prune-batches =================

    @Nested
    @DisplayName("queue:forget")
    class Forget {

        private String recordOneFailure() {
            Queue.dispatch(new FailsAlways());
            Artisan.call("queue:work", "--stop-when-empty");
            return failedJobs.all().get(0).id();
        }

        @Test
        @DisplayName("删掉指定的那一条,并先把它是什么打出来")
        void forgetsOne() {
            String id = recordOneFailure();

            assertThat(Artisan.call("queue:forget", id)).isZero();
            assertThat(Artisan.output())
                    .as("id 是从表上抄的,抄错一位就删错一条 —— 所以先显示要删的是什么")
                    .contains("将删掉")
                    .contains("FailsAlways");
            assertThat(failedJobs.count()).isZero();
        }

        @Test
        @DisplayName("★ id 不存在时报错并指路,而不是静默成功")
        void unknownId() {
            assertThat(Artisan.call("queue:forget", "99999")).isEqualTo(1);
            assertThat(Artisan.output()).contains("没有 id 为 99999").contains("queue:failed");
        }

        @Test
        @DisplayName("★ 收到 all 时指向 queue:flush —— 而不是「没有 id 为 all 的记录」")
        void allIsNotAnId() {
            assertThat(Artisan.call("queue:forget", "all")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("queue:retry 收 all,queue:forget 不收 —— 那是 queue:flush")
                    .contains("queue:flush");
        }
    }

    @Nested
    @DisplayName("queue:restart")
    class Restart {

        @Test
        @DisplayName("写下信号,并说清「谁把 worker 拉起来」")
        void broadcasts() {
            assertThat(Artisan.call("queue:restart")).isZero();

            assertThat(new net.dobeen.jaravel.queue.RestartSignal(cache).lastBroadcastAt()).isPresent();
            assertThat(Artisan.output())
                    .as("""
                            这条命令只让 worker 退出。不说清重启是进程管理器的事,
                            一次部署就会把队列彻底停掉 —— 而监控上只看到积压在涨。""")
                    .contains("进程管理器");
        }

        @Test
        @DisplayName("★ 缓存驱动不共享时警告 —— 否则命令「成功」而什么都不会发生")
        void warnsOnLocalCache() {
            assertThat(Artisan.call("queue:restart")).isZero();

            assertThat(Artisan.output())
                    .as("array 驱动在进程内,信号传不到独立部署的 worker")
                    .contains("进程内");
        }

        @Test
        @DisplayName("★ worker 收到信号后在任务之间退出,不打断在跑的任务")
        void workerStopsBetweenJobs() throws Exception {
            for (int i = 0; i < 3; i++) Queue.dispatch(new Succeeds(i));

            // 不给 --stop-when-empty / --once:这个 worker 只能靠信号退出。
            // 退不出来的表现是线程 join 超时
            var exit = new java.util.concurrent.atomic.AtomicInteger(-1);
            // 输出要在**那个线程里**读:Artisan 的捕获是按线程的,
            // 从主线程读 output() 会拿到空串
            var out = new java.util.concurrent.atomic.AtomicReference<>("");
            var worker = new Thread(() -> {
                exit.set(Artisan.call("queue:work"));
                out.set(Artisan.output());
            }, "worker");
            worker.start();

            // 等它真的开始干活再发信号 —— 信号只认**比 worker 启动更新**的请求
            // (否则一个刚起来的 worker 会被上一次部署的旧信号立刻杀掉)
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (LOG.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(LOG).as("worker 没能在 15 秒内处理掉任何任务").isNotEmpty();

            // 直接写信号,不经过命令:在 worker 跑着的时候再调一条命令,
            // 两边的输出会搅在一起。命令本身的行为由上面两条测试覆盖
            new net.dobeen.jaravel.queue.RestartSignal(cache).broadcast();

            worker.join(Duration.ofSeconds(20).toMillis());
            assertThat(worker.isAlive())
                    .as("收到信号之后应当在下一次回到主循环时退出")
                    .isFalse();
            assertThat(exit.get()).isZero();
            assertThat(out.get()).contains("收到 queue:restart");
        }

        @Test
        @DisplayName("--without-restart-signal 的 worker 不理它")
        void optOut() {
            Queue.dispatch(new Succeeds(1));
            Artisan.call("queue:restart");

            assertThat(Artisan.call("queue:work", "--once", "--without-restart-signal")).isZero();
            assertThat(LOG).hasSize(1);
        }
    }

    @Nested
    @DisplayName("queue:monitor")
    class Monitor {

        @Test
        @DisplayName("列出长度,阈值之内退出码 0")
        void withinThreshold() {
            Queue.dispatch(new Succeeds(1));

            assertThat(Artisan.call("queue:monitor", "database:default", "--max=10")).isZero();
            assertThat(Artisan.output()).contains("database:default").contains("正常");
        }

        @Test
        @DisplayName("★ 超阈值时退出码 1 —— 能接告警的东西认的是退出码")
        void overThreshold() {
            Queue.dispatch(new Succeeds(1));
            Queue.dispatch(new Succeeds(2));

            assertThat(Artisan.call("queue:monitor", "database:default", "--max=1"))
                    .as("只打一行字的命令等于把判断留给人去看终端,而没人会一直看")
                    .isEqualTo(1);
            assertThat(Artisan.output()).contains("积压").contains("超过阈值");
        }

        @Test
        @DisplayName("省略连接名用默认连接;多个队列逗号分隔")
        void defaultsAndMultiple() {
            assertThat(Artisan.call("queue:monitor", "default,high")).isZero();
            assertThat(Artisan.output())
                    .contains("database:default")
                    .contains("database:high");
        }

        @Test
        @DisplayName("★ 连接名写错时报错并列出已注册的 —— 不能以「0 积压」结束")
        void unknownConnection() {
            assertThat(Artisan.call("queue:monitor", "redis:default")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("假阴性是监控最糟的结果")
                    .contains("已注册的连接");
        }
    }

    @Nested
    @DisplayName("queue:prune-batches")
    class PruneBatches {

        @Test
        @DisplayName("没有可清的批次时如实说")
        void nothingToPrune() {
            assertThat(Artisan.call("queue:prune-batches", "--hours=48")).isZero();
            assertThat(Artisan.output()).contains("没有可清的批次");
        }

        @Test
        @DisplayName("★ 未结束的批次不清 —— 删了它的回调就永远不执行")
        void keepsUnfinishedBatches() {
            batches.create("batch-1", "导入", 3, false, java.util.Map.of());

            assertThat(Artisan.call("queue:prune-batches", "--hours=0")).isZero();
            assertThat(batches.find("batch-1"))
                    .as("按创建时刻删会把一个跑了三天的长批次删掉,症状是「批次卡在 99%」")
                    .isPresent();
        }
    }

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
