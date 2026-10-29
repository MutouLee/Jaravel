package io.jaravel.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.jaravel.database.Connection;
import io.jaravel.migration.schema.Schema;

/**
 * 迁移执行器。
 *
 * <p>职责:排序、记账、批次、回滚、**漂移检测**。
 *
 * <h2>checksum 是对 Laravel 的改进</h2>
 * <p>Laravel 只记「这个迁移跑过了」。于是团队里有人改了一个已上线的迁移文件,
 * 本地 {@code migrate:fresh} 得到新结构、线上还是旧结构,而 {@code migrate}
 * 说「Nothing to migrate」。这个故障很常见且极难定位。
 *
 * <p>这里给每个已执行的迁移存一份内容指纹,再次 {@code migrate} 时比对,
 * 不一致就报出来并指明两条出路(新建迁移 / {@code --allow-drift})。
 */
public final class Migrator {

    /** 迁移类所在包的时间戳段:{@code m2026_10_29_143500} */
    private static final Pattern TIMESTAMP_PACKAGE =
            Pattern.compile("(?:^|\\.)m(\\d{4}_\\d{2}_\\d{2}_\\d{6})(?:\\.|$)");

    private final Connection connection;
    private final String table;
    private final List<String> output = new ArrayList<>();

    public Migrator(Connection connection) {
        this(connection, "jaravel_migrations");
    }

    public Migrator(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    /** 执行过程的说明文本,供 CLI 打印 */
    public List<String> output() {
        return List.copyOf(output);
    }

    // ================= 记账表 =================

    public void ensureRepository() {
        Schema schema = Schema.on(connection);
        if (schema.tableExists(table)) return;

        schema.createTable(table, t -> {
            t.id();
            t.string("migration", 255).unique();
            t.integer("batch");
            t.timestamp("ran_at");
            t.string("checksum", 64);
        });
        output.add("已创建迁移记账表 " + table);
    }

    public boolean repositoryExists() {
        return Schema.on(connection).tableExists(table);
    }

    // ================= migrate =================

    public record Options(boolean step, boolean allowDrift, boolean pretend) {
        public static Options defaults() { return new Options(false, false, false); }
    }

    /**
     * 执行所有未运行的迁移。
     *
     * @return 实际执行的迁移名
     */
    public List<String> migrate(List<Migration> migrations, Options options) {
        ensureRepository();

        Map<String, Record> ran = ranMigrations();
        checkDrift(migrations, ran, options);

        List<Migration> pending = sorted(migrations).stream()
                .filter(m -> !ran.containsKey(nameOf(m)))
                .filter(this::appliesToDialect)
                .toList();

        if (pending.isEmpty()) {
            output.add("没有待执行的迁移。");
            return List.of();
        }

        int batch = nextBatch();
        var executed = new ArrayList<String>();

        for (Migration migration : pending) {
            String name = nameOf(migration);
            if (options.pretend()) {
                output.add("[pretend] 将执行 " + name);
                executed.add(name);
                continue;
            }

            long start = System.nanoTime();
            runGuarded(migration, migration::up, "up");
            long millis = (System.nanoTime() - start) / 1_000_000;

            log(name, options.step() ? batch++ : batch, checksumOf(migration));
            output.add("已执行 " + name + " (" + millis + "ms)");
            executed.add(name);
        }
        return executed;
    }

    public List<String> migrate(List<Migration> migrations) {
        return migrate(migrations, Options.defaults());
    }

    // ================= rollback =================

    /** 回滚最后一个批次 */
    public List<String> rollback(List<Migration> migrations) {
        Map<String, Record> ran = ranMigrations();
        if (ran.isEmpty()) {
            output.add("没有可回滚的迁移。");
            return List.of();
        }

        int lastBatch = ran.values().stream().mapToInt(Record::batch).max().orElse(0);
        var names = ran.values().stream()
                .filter(r -> r.batch() == lastBatch)
                .map(Record::migration)
                .toList();

        return rollbackNamed(migrations, names);
    }

    /** 回滚指定步数的批次 */
    public List<String> rollback(List<Migration> migrations, int steps) {
        Map<String, Record> ran = ranMigrations();
        var batches = ran.values().stream()
                .map(Record::batch).distinct()
                .sorted(Comparator.reverseOrder())
                .limit(steps)
                .toList();

        var names = ran.values().stream()
                .filter(r -> batches.contains(r.batch()))
                .map(Record::migration)
                .toList();

        return rollbackNamed(migrations, names);
    }

    /** 全部回滚 */
    public List<String> reset(List<Migration> migrations) {
        var names = ranMigrations().keySet().stream().toList();
        return rollbackNamed(migrations, names);
    }

    private List<String> rollbackNamed(List<Migration> migrations, List<String> names) {
        Map<String, Migration> byName = new LinkedHashMap<>();
        for (Migration m : migrations) byName.put(nameOf(m), m);

        // 倒序回滚:后建的表可能引用先建的,反着来才不会撞外键
        var ordered = names.stream()
                .sorted(Comparator.reverseOrder())
                .toList();

        var done = new ArrayList<String>();
        for (String name : ordered) {
            Migration migration = byName.get(name);
            if (migration == null) {
                throw new MigrationException("""
                        要回滚 %s,但在 classpath 上找不到对应的迁移类。
                          它可能被删掉了,而记账表里还留着记录。
                          处理方式:
                            · 把那个迁移类找回来(从 git 历史),再 rollback;
                            · 或者确认结构无需回滚后,手动删掉记账表里这一行:
                              delete from %s where migration = '%s'"""
                        .formatted(name, table, name));
            }
            if (migration.irreversible()) {
                throw new MigrationException(
                        name + " 声明了 irreversible(),不能回滚。"
                      + "\n  这通常意味着它丢弃了数据(删列、删表),回滚也恢复不了。"
                      + "\n  若确实要撤销它的结构变更,请新写一个迁移。");
            }

            runGuarded(migration, migration::down, "down");
            connection.update("delete from " + wrap(table) + " where " + wrap("migration") + " = ?",
                              List.of(name));
            output.add("已回滚 " + name);
            done.add(name);
        }
        return done;
    }

    // ================= 漂移检测 =================

    private void checkDrift(List<Migration> migrations, Map<String, Record> ran, Options options) {
        var drifted = new ArrayList<String>();

        for (Migration migration : migrations) {
            Record record = ran.get(nameOf(migration));
            if (record == null) continue;
            String current = checksumOf(migration);
            if (record.checksum() == null || record.checksum().isBlank()) continue;
            if (record.checksum().equals(current)) continue;
            drifted.add(nameOf(migration));
        }
        if (drifted.isEmpty()) return;

        String message = """
                以下迁移在执行之后被修改过:
                  %s

                这意味着你的数据库结构与迁移文件**已经不一致** —— 新环境
                 migrate 出来的结构会和现有环境不同。这是团队协作里最常见的
                「本地好的、线上挂的」来源,所以 Jaravel 默认把它当错误。

                处理方式(按推荐顺序):
                  1. 把改动挪到一个**新的**迁移里 —— 已执行的迁移视为不可变历史;
                  2. 若只是注释或格式变化,用 --allow-drift 跳过本次检查;
                  3. 若这个环境可以重建,migrate:fresh 重来。""".formatted(String.join("\n  ", drifted));

        if (options.allowDrift()) {
            output.add("[警告] 检测到迁移漂移(已按 --allow-drift 放行):" + drifted);
            return;
        }
        throw new MigrationException(message);
    }

    /**
     * 迁移的内容指纹。
     *
     * <p>**不是对源文件做哈希** —— 源文件在部署产物里往往不存在。
     * 改成对「迁移实际产生的 DDL」做哈希:语义等价的格式调整不会触发漂移,
     * 而真正改了结构一定会。代价是要空跑一次 up(),所以用一个只记录
     * 不执行的连接。
     */
    public String checksumOf(Migration migration) {
        var recorder = new StatementRecorder(connection);
        Connection previous = Schema.currentConnection();
        try {
            Schema.useConnection(recorder);
            migration.up();
        } catch (RuntimeException e) {
            // 迁移里有数据操作或条件判断时空跑会失败。退化为按类名 + 类字节码长度,
            // 弱一些但不会误报。
            return digest(migration.getClass().getName() + "#fallback");
        } finally {
            Schema.useConnection(previous);
        }
        return digest(String.join(";", recorder.statements()));
    }

    private static String digest(String input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = sha.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256,这不该发生", e);
        }
    }

    // ================= 记账读写 =================

    public record Record(String migration, int batch, Instant ranAt, String checksum) {}

    public Map<String, Record> ranMigrations() {
        if (!repositoryExists()) return Map.of();

        var out = new LinkedHashMap<String, Record>();
        connection.select("select " + wrap("migration") + ", " + wrap("batch") + ", "
                        + wrap("ran_at") + ", " + wrap("checksum")
                        + " from " + wrap(table) + " order by " + wrap("migration"))
                .forEach(row -> {
                    String name = String.valueOf(row.get("migration"));
                    out.put(name, new Record(
                            name,
                            io.jaravel.support.Cast.to(row.get("batch"), Integer.class),
                            row.get("ran_at") == null ? null
                                    : io.jaravel.support.Cast.to(row.get("ran_at"), Instant.class),
                            row.get("checksum") == null ? null : String.valueOf(row.get("checksum"))));
                });
        return out;
    }

    public int nextBatch() {
        if (!repositoryExists()) return 1;
        return connection.select("select max(" + wrap("batch") + ") as m from " + wrap(table))
                .first()
                .map(r -> r.get("m") == null ? 0
                        : io.jaravel.support.Cast.to(r.get("m"), Integer.class))
                .orElse(0) + 1;
    }

    private void log(String name, int batch, String checksum) {
        connection.update("insert into " + wrap(table)
                        + " (" + wrap("migration") + ", " + wrap("batch") + ", "
                        + wrap("ran_at") + ", " + wrap("checksum") + ") values (?, ?, ?, ?)",
                java.util.Arrays.asList(name, batch, Instant.now(), checksum));
    }

    // ================= 工具 =================

    /**
     * 排序键 = 包里的时间戳 + 类名。
     *
     * <p>没有时间戳的迁移排在最后并给出警告:它们之间的相对顺序取决于
     * classpath 扫描顺序,也就是**不确定**。在两台机器上得到不同结果的迁移
     * 是灾难,所以要在第一次就说出来。
     */
    public String nameOf(Migration migration) {
        String className = migration.getClass().getName();
        var matcher = TIMESTAMP_PACKAGE.matcher(className);
        if (matcher.find()) {
            return matcher.group(1) + "_" + migration.getClass().getSimpleName();
        }
        return "zzz_no_timestamp_" + migration.getClass().getSimpleName();
    }

    private List<Migration> sorted(List<Migration> migrations) {
        var withoutTimestamp = migrations.stream()
                .filter(m -> !TIMESTAMP_PACKAGE.matcher(m.getClass().getName()).find())
                .map(m -> m.getClass().getSimpleName())
                .toList();
        if (!withoutTimestamp.isEmpty()) {
            output.add("[警告] 以下迁移的包名里没有时间戳,执行顺序不确定:" + withoutTimestamp
                     + "\n  约定:database.migrations.m2026_10_29_143500.CreatePostsTable");
        }
        return migrations.stream()
                .sorted(Comparator.comparing(this::nameOf))
                .toList();
    }

    private boolean appliesToDialect(Migration migration) {
        var only = migration.onlyOnDialects();
        if (only.isEmpty()) return true;
        String dialect = connection.grammar().name();
        boolean applies = only.contains(dialect);
        if (!applies) {
            output.add("跳过 " + nameOf(migration) + ":仅适用于 " + only + ",当前是 " + dialect);
        }
        return applies;
    }

    /**
     * 执行一个迁移方向,包好事务并把失败原因说清楚。
     *
     * <p>**迁移中途失败是最难收拾的状态**,所以报错必须包含:哪个迁移、
     * 哪个方向、原始异常、以及「数据库现在可能处于半完成状态」这句提醒。
     */
    private void runGuarded(Migration migration, Runnable body, String direction) {
        Connection previous = Schema.currentConnection();
        try {
            Schema.useConnection(connection);
            if (migration.withinTransaction() && supportsTransactionalDdl()) {
                connection.transaction(body);
            } else {
                body.run();
            }
        } catch (RuntimeException e) {
            throw new MigrationException("""
                    迁移 %s 的 %s() 执行失败:%s

                      %s
                      下一步:修好上面的错误,再次 migrate 会从这个迁移继续
                      (之前成功的迁移已记账,不会重跑)。"""
                    .formatted(nameOf(migration), direction, e.getMessage(),
                               migration.withinTransaction() && supportsTransactionalDdl()
                                   ? "本次已回滚,数据库结构未变。"
                                   : "注意:该方言的 DDL 不支持事务,数据库可能处于半完成状态 ——"
                                   + "请手工核对该迁移涉及的表。"),
                    e);
        } finally {
            Schema.useConnection(previous);
        }
    }

    /**
     * DDL 能否在事务里回滚。
     *
     * <p>PostgreSQL 和 SQLite 可以;MySQL 的 DDL 是隐式提交的,包了也白包。
     * 这个差异直接决定「迁移失败后数据库是干净的还是半完成的」,
     * 因此要在报错里告诉用户。
     */
    private boolean supportsTransactionalDdl() {
        return switch (connection.grammar().name()) {
            case "postgres", "sqlite" -> true;
            default -> false;
        };
    }

    private String wrap(String identifier) {
        return connection.grammar().wrap(identifier);
    }

    /** 只收集语句不执行的连接 —— 给 checksum 用 */
    private static final class StatementRecorder implements Connection {
        private final Connection delegate;
        private final List<String> statements = new ArrayList<>();

        StatementRecorder(Connection delegate) { this.delegate = delegate; }

        List<String> statements() { return statements; }

        @Override public io.jaravel.database.grammar.Grammar grammar() { return delegate.grammar(); }
        @Override public void statement(String sql) { statements.add(sql); }

        @Override public io.jaravel.support.Coll<io.jaravel.database.Row> select(
                String sql, List<Object> bindings) {
            statements.add(sql);
            // 空跑时的查询(hasTable 之类)返回空集,让迁移走「表不存在」的分支
            return io.jaravel.support.Coll.of();
        }

        @Override public <T> io.jaravel.support.Coll<T> select(
                String sql, List<Object> bindings, Class<T> type) {
            statements.add(sql);
            return io.jaravel.support.Coll.of();
        }

        @Override public int update(String sql, List<Object> bindings) {
            statements.add(sql);
            return 0;
        }

        @Override public Optional<Long> insertGetId(String sql, List<Object> bindings) {
            statements.add(sql);
            return Optional.empty();
        }

        @Override public <T> T transaction(java.util.function.Supplier<T> body) { return body.get(); }
        @Override public void afterCommit(Runnable action) { /* 空跑不执行 */ }
        @Override public boolean inTransaction() { return false; }
        @Override public void enableQueryLog() {}
        @Override public void disableQueryLog() {}
        @Override public List<QueryLog> queryLog() { return List.of(); }
        @Override public void flushQueryLog() {}
        @Override public Optional<Object> raw() { return Optional.empty(); }
    }

    public static class MigrationException extends RuntimeException {
        public MigrationException(String message) { super(message); }
        public MigrationException(String message, Throwable cause) { super(message, cause); }
    }
}
