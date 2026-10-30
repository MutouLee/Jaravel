package io.jaravel.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.jaravel.database.grammar.Grammar;
import io.jaravel.support.Coll;

/**
 * 基于纯 JDBC 的 {@link Connection} 实现。
 *
 * <p>只依赖 {@code javax.sql.DataSource}(JDK 标准),**不碰 Spring** ——
 * 因此 {@code jaravel-database} 保持零 Spring 依赖(ADR-001)。
 *
 * <p>starter 会用一个感知 Spring 事务的子类替换事务方法,
 * 使 {@code DB.transaction} 与 {@code @Transactional} 共享同一事务。
 */
public class JdbcConnection implements Connection {

    private final DataSource dataSource;
    private final Grammar grammar;

    /** 事务内的物理连接。非 null 表示当前线程处于事务中。 */
    private final ThreadLocal<java.sql.Connection> transactional = new ThreadLocal<>();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Deque<Runnable>> afterCommitHooks =
            ThreadLocal.withInitial(ArrayDeque::new);

    private volatile boolean logging = false;
    private final List<QueryLog> log = java.util.Collections.synchronizedList(new ArrayList<>());

    public JdbcConnection(DataSource dataSource, Grammar grammar) {
        this.dataSource = dataSource;
        this.grammar = grammar;
    }

    @Override public Grammar grammar() { return grammar; }

    // ================= 查询 =================

    @Override
    public Coll<Row> select(String sql, List<Object> bindings) {
        return run(sql, bindings, ps -> {
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();

                // 列标签在整个 ResultSet 内不变,所以**解析一次**。
                // 原先放在逐行循环里:1000 行 × 4 列 = 4000 次多余的 JDBC
                // 元数据调用,而且 toLowerCase 也跟着重复了 4000 次。
                String[] labels = new String[columns];
                for (int i = 0; i < columns; i++) {
                    String label = meta.getColumnLabel(i + 1);
                    if (label == null || label.isBlank()) label = meta.getColumnName(i + 1);
                    labels[i] = label.toLowerCase();
                }

                var out = new ArrayList<Row>();
                while (rs.next()) {
                    var values = new LinkedHashMap<String, Object>(columns * 2);
                    for (int i = 0; i < columns; i++) {
                        values.put(labels[i], rs.getObject(i + 1));
                    }
                    // adopt 而非 of:map 刚建好、无人持有,不必再拷一份
                    out.add(Row.adopt(values));
                }
                return Coll.wrap(java.util.Collections.unmodifiableList(out));
            }
        });
    }

    @Override
    public <T> Coll<T> select(String sql, List<Object> bindings, Class<T> type) {
        return select(sql, bindings).map(r -> RowMapper.map(r, type));
    }

    // ================= 写入 =================

    @Override
    public int update(String sql, List<Object> bindings) {
        return run(sql, bindings, PreparedStatement::executeUpdate);
    }

    @Override
    public Optional<Long> insertGetId(String sql, List<Object> bindings) {
        long start = System.nanoTime();
        java.sql.Connection conn = null;
        boolean borrowed = false;
        try {
            conn = acquire();
            borrowed = transactional.get() == null;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, bindings);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    Optional<Long> id = keys.next()
                            ? Optional.of(keys.getLong(1))
                            : Optional.empty();
                    record(sql, bindings, start);
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new QueryFailedException(sql, bindings, e);
        } finally {
            if (borrowed) close(conn);
        }
    }

    @Override
    public void statement(String sql) {
        long start = System.nanoTime();
        java.sql.Connection conn = null;
        boolean borrowed = false;
        try {
            conn = acquire();
            borrowed = transactional.get() == null;
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            record(sql, List.of(), start);
        } catch (SQLException e) {
            throw new QueryFailedException(sql, List.of(), e);
        } finally {
            if (borrowed) close(conn);
        }
    }

    // ================= 事务 =================

    @Override
    public <T> T transaction(Supplier<T> body) {
        boolean outermost = transactional.get() == null;

        if (outermost) {
            java.sql.Connection conn;
            try {
                conn = dataSource.getConnection();
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                throw new RuntimeException("开启事务失败:" + e.getMessage(), e);
            }
            transactional.set(conn);
            depth.set(1);
            emitBeginning(1);
            try {
                T result = body.get();
                conn.commit();

                // 事件在 commit **之后**、afterCommit 回调**之前**发。
                //
                // 之前发的话监听器看到的是「还没落库」的状态;
                // 回调之后发的话,一个 afterCommit 回调里抛异常会让
                // Committed 永远不发出 —— 而那个事件通常正是审计用的,
                // 「审计漏了一条」比「回调失败」更难发现
                emitCommitted(1);
                runAfterCommitHooks();
                return result;
            } catch (RuntimeException | Error e) {
                rollbackQuietly(conn, e);
                emitRolledBack(1, e);
                throw e;
            } catch (Throwable t) {
                rollbackQuietly(conn, t);
                emitRolledBack(1, t);
                throw new RuntimeException(t);
            } finally {
                transactional.remove();
                depth.remove();
                afterCommitHooks.remove();
                restoreAutoCommit(conn);
                close(conn);
            }
        }

        // 嵌套事务:用 SAVEPOINT,内层回滚不影响外层已做的操作
        java.sql.Connection conn = transactional.get();
        int level = depth.get() + 1;
        depth.set(level);
        java.sql.Savepoint savepoint;
        try {
            savepoint = conn.setSavepoint("jaravel_sp" + level);
        } catch (SQLException e) {
            throw new RuntimeException("创建保存点失败:" + e.getMessage(), e);
        }
        emitBeginning(level);
        try {
            T result = body.get();
            conn.releaseSavepoint(savepoint);

            // ⚠ level > 1 的 Committed 表示「保存点释放了」,数据**还没落库**。
            // 监听器要「真的提交了」就判 isOutermost()。
            // 框架不替它过滤 —— 过滤掉就没法做「内层回滚了但外层继续」的审计
            emitCommitted(level);
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException | Error e) {
            try { conn.rollback(savepoint); } catch (SQLException ignored) {}
            emitRolledBack(level, e);
            throw e;
        } finally {
            depth.set(level - 1);
        }
    }

    // ================= 事务事件 =================
    //
    // 三个小方法而不是在调用点直接写 Event.tryDispatch:
    // 事务那段代码的分支已经够多了(最外层/嵌套 × 成功/失败 × SQLException),
    // 在里面再塞五行事件构造会让「事务语义」这件事更难读。

    private void emitBeginning(int level) {
        io.jaravel.events.Event.tryDispatchIfListening(
                io.jaravel.database.events.TransactionEvents.Beginning.class,
                () -> new io.jaravel.database.events.TransactionEvents.Beginning(level));
    }

    private void emitCommitted(int level) {
        io.jaravel.events.Event.tryDispatchIfListening(
                io.jaravel.database.events.TransactionEvents.Committed.class,
                () -> new io.jaravel.database.events.TransactionEvents.Committed(level));
    }

    private void emitRolledBack(int level, Throwable cause) {
        io.jaravel.events.Event.tryDispatchIfListening(
                io.jaravel.database.events.TransactionEvents.RolledBack.class,
                () -> new io.jaravel.database.events.TransactionEvents.RolledBack(level, cause));
    }

    /**
     * 把 autoCommit 恢复成 true 再归还连接。
     *
     * <p>不能指望连接池来重置。HikariCP 默认会(可配置关掉),但
     * 「同一条物理连接复用」的 DataSource 不会 —— 那种情况下连接会**永久停在
     * autoCommit = false**,之后所有写入都不提交,直到下一次 rollback 把它们
     * 一起抹掉。症状是「数据写了又没了,而且没有任何报错」。
     *
     * <p>这个 bug 是 v0.2 的迁移测试发现的:一个迁移失败后,记账表里之前
     * 成功的记录全部消失,于是再次 migrate 会重跑已应用的迁移。
     */
    private void restoreAutoCommit(java.sql.Connection conn) {
        try {
            if (!conn.getAutoCommit()) conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接已经坏了,close 时会处理;这里不该掩盖原始业务异常
        }
    }

    private void rollbackQuietly(java.sql.Connection conn, Throwable original) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            original.addSuppressed(new RuntimeException("回滚失败:" + e.getMessage(), e));
        }
        afterCommitHooks.get().clear();   // 回滚了就不该执行提交后回调
    }

    private void runAfterCommitHooks() {
        Deque<Runnable> hooks = afterCommitHooks.get();
        RuntimeException failure = null;
        while (!hooks.isEmpty()) {
            try {
                hooks.pollFirst().run();
            } catch (RuntimeException e) {
                // 一个回调失败不应阻止其余回调(事务已提交,无法回退)
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void afterCommit(Runnable action) {
        if (!inTransaction()) {
            action.run();      // 不在事务里就立即执行,语义等价
            return;
        }
        afterCommitHooks.get().addLast(action);
    }

    @Override
    public boolean inTransaction() {
        return transactional.get() != null;
    }

    // ================= 内部 =================

    @FunctionalInterface
    private interface StatementAction<T> {
        T apply(PreparedStatement ps) throws SQLException;
    }

    private <T> T run(String sql, List<Object> bindings, StatementAction<T> action) {
        long start = System.nanoTime();
        java.sql.Connection conn = null;
        boolean borrowed = false;
        try {
            conn = acquire();
            borrowed = transactional.get() == null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, bindings);
                T result = action.apply(ps);
                record(sql, bindings, start);
                return result;
            }
        } catch (SQLException e) {
            throw new QueryFailedException(sql, bindings, e);
        } finally {
            if (borrowed) close(conn);
        }
    }

    private java.sql.Connection acquire() throws SQLException {
        java.sql.Connection tx = transactional.get();
        return tx != null ? tx : dataSource.getConnection();
    }

    private static void close(java.sql.Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 归还连接池失败无可挽回,也不该掩盖业务异常
        }
    }

    private void bind(PreparedStatement ps, List<Object> bindings) throws SQLException {
        boolean temporalAsString = grammar.bindsTemporalAsString();

        for (int i = 0; i < bindings.size(); i++) {
            Object v = bindings.get(i);
            // java.time 类型多数驱动不直接认,转成 JDBC 标准类型
            if (v instanceof java.time.Instant inst) {
                if (temporalAsString) {
                    ps.setString(i + 1, ISO_INSTANT.format(inst));
                } else {
                    ps.setTimestamp(i + 1, java.sql.Timestamp.from(inst));
                }
            } else if (v instanceof java.time.LocalDateTime ldt) {
                if (temporalAsString) {
                    ps.setString(i + 1, ldt.toString());
                } else {
                    ps.setTimestamp(i + 1, java.sql.Timestamp.valueOf(ldt));
                }
            } else if (v instanceof java.time.LocalDate ld) {
                if (temporalAsString) {
                    ps.setString(i + 1, ld.toString());
                } else {
                    ps.setDate(i + 1, java.sql.Date.valueOf(ld));
                }
            } else if (v instanceof java.time.LocalTime lt) {
                ps.setString(i + 1, lt.toString());
            } else if (v instanceof Enum<?> en) {
                ps.setString(i + 1, en.name());
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    /**
     * 固定毫秒精度的 ISO-8601 UTC。
     *
     * <p>不用 {@code DateTimeFormatter.ISO_INSTANT}:它的小数位是**可变**的
     * ({@code ...:00Z} / {@code ...:00.5Z} / {@code ...:00.123Z}),于是字典序
     * 不等于时间序,{@code order by created_at} 会在整秒和带毫秒的记录之间排错。
     * 定长三位小数才能让文本比较与时间比较一致。
     */
    private static final java.time.format.DateTimeFormatter ISO_INSTANT =
            new java.time.format.DateTimeFormatterBuilder()
                    .appendInstant(3)
                    .toFormatter();

    private void record(String sql, List<Object> bindings, long startNanos) {
        boolean listening = io.jaravel.events.Event.listening(
                io.jaravel.database.events.QueryExecuted.class);

        // ★ 查询日志与 QueryExecuted 事件是**两件事**,各有各的开关。
        //
        // 两者都关着时这里要尽早返回 —— 这是每条 SQL 都会走到的路径,
        // 为没人要的数据算一次耗时、复制一次 bindings、走一遍调用栈
        // (callerHint 要遍历栈帧)在高 QPS 下是真实的开销。
        if (!logging && !listening) return;

        long nanos = System.nanoTime() - startNanos;

        // 不能用 List.copyOf —— 它拒绝 null 元素,而 `set x = null` 是完全正常的绑定。
        // 开着查询日志时,第一次写 NULL 就会 NPE。
        var snapshot = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(bindings));
        String caller = callerHint();

        if (logging) {
            log.add(new QueryLog(sql, snapshot, nanos / 1000, caller));
        }
        if (listening) {
            io.jaravel.events.Event.tryDispatch(
                    new io.jaravel.database.events.QueryExecuted(
                            sql, snapshot, java.time.Duration.ofNanos(nanos), caller));
        }
    }

    /**
     * 找到调用栈里第一个非框架的帧,便于 SQL 面板「定位调用点」。
     *
     * <p>按**具体实现类**过滤而非包前缀 —— 包前缀会连带滤掉同包下的用户代码
     * (框架自己的测试就在 io.jaravel.database 包里)。
     */
    private static final List<String> FRAMEWORK_FRAMES = List.of(
            "io.jaravel.database.JdbcConnection",
            "io.jaravel.database.RowMapper",
            "io.jaravel.database.Row",
            "io.jaravel.database.Connection",
            "io.jaravel.database.query.",
            "io.jaravel.database.grammar.",
            "io.jaravel.support.",
            "java.", "jdk.", "sun.");

    private static String callerHint() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getClassName)
                        .filter(c -> FRAMEWORK_FRAMES.stream().noneMatch(c::startsWith))
                        .findFirst()
                        .orElse("unknown"));
    }

    @Override public void enableQueryLog()  { logging = true; }
    @Override public void disableQueryLog() { logging = false; }
    @Override public List<QueryLog> queryLog() { return List.copyOf(log); }
    @Override public void flushQueryLog()   { log.clear(); }

    @Override public Optional<Object> raw() { return Optional.of(dataSource); }
}
