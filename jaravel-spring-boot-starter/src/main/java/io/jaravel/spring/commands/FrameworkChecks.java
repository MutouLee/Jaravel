package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.crypt.Encrypter;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;
import io.jaravel.scheduler.Scheduler;
import io.jaravel.scheduler.SchedulerConfigCheck;

/**
 * 框架自带的检查项(docs/13 §2.6)。
 *
 * <p>每一项都对着一个**这个项目里真实存在**的陷阱 ——
 * 下面每条的注释里写了它是什么、为什么难查。
 *
 * <p>一张显式的表而不是扫包,理由同 {@code Stub.bundled()}:
 * 运行时扫 classpath 在可执行 jar 与 native 镜像里都不可靠。
 */
public final class FrameworkChecks {

    private FrameworkChecks() {
    }

    public static List<DoctorCheck> all(ConfigRepository config,
                                 Supplier<Connection> connection,
                                 Supplier<List<Migration>> migrations,
                                 Supplier<Scheduler> scheduler) {
        return List.of(
                appKey(config),
                debugInProduction(),
                database(connection),
                migrations(connection, migrations),
                scheduleLocks(config, scheduler),
                syncQueueOutsideDevelopment(config),
                lazyLoadingDetection());
    }

    /**
     * APP_KEY 有没有、合不合法。
     *
     * <p>没有的话会话与敏感列加密都不能用,而那个失败发生在**第一次用到
     * 加密的时候** —— 可能是上线后第一个登录请求。
     */
    private static DoctorCheck appKey(ConfigRepository config) {
        return check("APP_KEY", () -> {
            String key = config.string("app.key", null);
            if (key == null || key.isBlank()) {
                return DoctorCheck.Result
                        .fail("没有设置",
                              "会话与敏感列加密都不能用 —— 失败会发生在第一次用到加密的时候",
                              "修复:jaravel key:generate")
                        .fixableWith("key:generate");
            }
            try {
                Encrypter.fromBase64(key);
                return DoctorCheck.Result.ok("已设置,格式正确");
            } catch (RuntimeException e) {
                return DoctorCheck.Result.fail("格式不合法",
                        e.getMessage().lines().findFirst().orElse(""),
                        "修复:jaravel key:generate --force(⚠ 已加密的数据会解不开)");
            }
        });
    }

    /**
     * 生产环境开着 debug。
     *
     * <p>后果很具体:开发期错误页会把**堆栈、SQL、配置值**打到响应里。
     * 它不会让任何功能失效,所以没人会注意到 —— 直到一个 500 页面
     * 把数据库连接串展示给了用户。
     */
    private static DoctorCheck debugInProduction() {
        return check("debug 模式", () -> {
            if (!Application.isProduction()) {
                return DoctorCheck.Result.ok("非生产环境,debug="
                        + Application.isDebug());
            }
            if (!Application.isDebug()) {
                return DoctorCheck.Result.ok("生产环境,debug 已关");
            }
            return DoctorCheck.Result.fail("生产环境开着 debug",
                    "开发期错误页会把堆栈、SQL 与配置值打进响应 —— 一个 500 页面就会泄露连接串",
                    "修复:在 config/app.yml 里设 app.debug: false,或 .env 里 APP_DEBUG=false");
        });
    }

    /** 数据库连得上吗 —— 一条 select 1 */
    private static DoctorCheck database(Supplier<Connection> connection) {
        return check("数据库连接", () -> {
            try {
                Connection active = connection.get();
                active.select("select 1");
                return DoctorCheck.Result.ok("连接正常("
                        + active.grammar().getClass().getSimpleName().replace("Grammar", "")
                                .toLowerCase() + ")");
            } catch (RuntimeException e) {
                return DoctorCheck.Result.fail("连不上数据库",
                        e.getMessage() == null ? e.getClass().getName()
                                : e.getMessage().lines().findFirst().orElse(""),
                        "修复:检查 spring.datasource.url 与数据库是否在跑");
            }
        });
    }

    /**
     * 迁移:有没有没跑的、有没有跑过之后被改的。
     *
     * <h2>★ checksum 不符是这里最重要的一项</h2>
     * <p>一个已经执行过的迁移被改了之后,{@code migrate} 会说
     * 「没有待执行的迁移」—— 因为它只看名字。于是那个改动**在本机永远不会
     * 生效**,而在同事新建的库上会生效。两边的表结构从此不同,
     * 而两边的 migrate 都说一切正常。
     */
    private static DoctorCheck migrations(Supplier<Connection> connection,
                                          Supplier<List<Migration>> migrations) {
        return check("迁移", () -> {
            Migrator migrator = new Migrator(connection.get());
            if (!migrator.repositoryExists()) {
                return DoctorCheck.Result.warn("一次 migrate 都没跑过",
                        "修复:jaravel migrate");
            }

            var ran = migrator.ranMigrations();
            List<Migration> found = migrations.get();
            var pending = new ArrayList<String>();
            var drifted = new ArrayList<String>();

            for (Migration migration : found) {
                String name = migrator.nameOf(migration);
                var record = ran.get(name);
                if (record == null) {
                    pending.add(name);
                    continue;
                }
                if (record.checksum() != null && !record.checksum().isBlank()
                        && !record.checksum().equals(migrator.checksumOf(migration))) {
                    drifted.add(name);
                }
            }

            if (!drifted.isEmpty()) {
                return DoctorCheck.Result.fail(
                        drifted.size() + " 个迁移执行后被改过:" + drifted,
                        "migrate 只看名字,所以它会说「没有待执行的迁移」—— 那个改动在本机永远不会生效",
                        "而在同事新建的库上会生效,于是两边表结构不同,且两边的 migrate 都说一切正常",
                        "修复:把改动挪到一个新迁移里;确实要放过用 migrate --allow-drift");
            }
            if (!pending.isEmpty()) {
                return DoctorCheck.Result.warn(
                        pending.size() + " 个迁移还没执行(共 " + found.size() + ")",
                        "修复:jaravel migrate");
            }
            return DoctorCheck.Result.ok("全部已执行(" + found.size() + "/" + found.size() + ")");
        });
    }

    /**
     * {@code onOneServer()} + 非共享缓存驱动 —— docs/13 §2.6 举的那个例子。
     *
     * <p>判定复用 {@link SchedulerConfigCheck#offendingTasks}:那份
     * 「哪些驱动不是共享存储」的名单只能有一处,复抄一遍的后果是
     * 加新驱动时只有一边被想起来。
     */
    private static DoctorCheck scheduleLocks(ConfigRepository config,
                                             Supplier<Scheduler> scheduler) {
        return check("计划任务的防重锁", () -> {
            String driver = config.string("cache.default", null);
            List<String> offenders;
            try {
                offenders = SchedulerConfigCheck.offendingTasks(
                        scheduler.get().schedule().tasks(), driver);
            } catch (RuntimeException e) {
                return DoctorCheck.Result.warn("没有调度器,跳过");
            }

            if (offenders.isEmpty()) {
                return DoctorCheck.Result.ok("没问题(缓存驱动 " + driver + ")");
            }
            if (config.integer("schedule.instances", 0) == 1) {
                return DoctorCheck.Result.warn(
                        offenders.size() + " 个任务用了 onOneServer(),而缓存驱动是 " + driver,
                        "schedule.instances=1 所以现在是安全的 —— 单实例下那是个无害的声明",
                        "⚠ 扩到多实例之前必须换成 redis 或 database 驱动");
            }
            return DoctorCheck.Result.fail(
                    offenders.size() + " 个任务的 onOneServer() 会失效:" + offenders,
                    "缓存驱动 " + driver + " 不是共享存储 —— 每个实例各有一份锁",
                    "多实例下这些任务会在每个实例上各跑一遍,而症状可能是「客户收到两封一样的邮件」",
                    "修复:把 cache.default 换成 redis 或 database;确实单实例则显式写 schedule.instances: 1");
        });
    }

    /**
     * 非开发环境用了 sync 队列驱动。
     *
     * <p>sync 的任务在**投递时就同步执行完**,于是队列的全部意义
     * (削峰、重试、失败归档)都没有了 —— 而接口会变慢,
     * 因为那些本该异步的活儿现在算在请求时间里。
     */
    private static DoctorCheck syncQueueOutsideDevelopment(ConfigRepository config) {
        return check("队列驱动", () -> {
            String driver = config.string("queue.default", "sync");
            if (!"sync".equalsIgnoreCase(driver)) {
                return DoctorCheck.Result.ok(driver);
            }
            if (Application.isLocal() || Application.isTesting()) {
                return DoctorCheck.Result.ok("sync(" + Application.environment() + " 环境下合理)");
            }
            return DoctorCheck.Result.fail("queue.default = sync,而当前是 "
                            + Application.environment(),
                    "sync 驱动在投递时就同步执行完 —— 削峰、重试、失败归档全都没有了",
                    "而接口会变慢:那些本该异步的活儿现在算在请求时间里",
                    "修复:把 queue.default 换成 database 或 redis,并起一个 worker");
        });
    }

    /**
     * 懒加载检测(N+1)。
     *
     * <p>这是 docs/13 §2.6 原文里的那条 ⚠:它不是错误,
     * 而是一个**开发期该开着**的开关 —— 关着的话 N+1 要等到生产的慢查询
     * 日志里才会被发现。
     */
    private static DoctorCheck lazyLoadingDetection() {
        return check("懒加载检测", () -> {
            if (Application.isProduction()) {
                return DoctorCheck.Result.ok("生产环境不需要");
            }
            if (lazyLoadingPrevented()) {
                return DoctorCheck.Result.ok("已开启");
            }
            return DoctorCheck.Result.warn("非生产环境但没开启",
                    "开着的话访问未预加载的关联会直接报错,于是 N+1 在开发期就被发现",
                    "关着的话它要等到生产的慢查询日志里才会出现",
                    "修复:Eloquent.preventLazyLoading(true),或打开 app.debug");
        });
    }

    /**
     * 反射去问 Eloquent —— starter 对 jaravel-eloquent 是可选依赖。
     *
     * <p>不在 classpath 上时当成「不需要检查」:一个不用 ORM 的项目
     * 不该看到一条关于懒加载的警告。
     */
    private static boolean lazyLoadingPrevented() {
        try {
            Class<?> eloquent = Class.forName("io.jaravel.eloquent.Eloquent");
            return (boolean) eloquent.getMethod("isLazyLoadingPrevented").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return true;   // 没有 ORM → 没有这个问题
        }
    }

    private static DoctorCheck check(String name, Supplier<DoctorCheck.Result> body) {
        return new DoctorCheck() {
            @Override public String name() { return name; }
            @Override public Result run() { return body.get(); }
        };
    }
}
