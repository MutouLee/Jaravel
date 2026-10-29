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
                var out = new ArrayList<Row>();
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                while (rs.next()) {
                    var values = new LinkedHashMap<String, Object>(columns);
                    for (int i = 1; i <= columns; i++) {
                        // 优先用别名(label),没有别名时才用列名
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isBlank()) label = meta.getColumnName(i);
                        values.put(label, rs.getObject(i));
                    }
                    out.add(Row.of(values));
                }
                return Coll.wrap(List.copyOf(out));
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
            try {
                T result = body.get();
                conn.commit();
                runAfterCommitHooks();
                return result;
            } catch (RuntimeException | Error e) {
                rollbackQuietly(conn, e);
                throw e;
            } catch (Throwable t) {
                rollbackQuietly(conn, t);
                throw new RuntimeException(t);
            } finally {
                transactional.remove();
                depth.remove();
                afterCommitHooks.remove();
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
        try {
            T result = body.get();
            conn.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException | Error e) {
            try { conn.rollback(savepoint); } catch (SQLException ignored) {}
            throw e;
        } finally {
            depth.set(level - 1);
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

    private static void bind(PreparedStatement ps, List<Object> bindings) throws SQLException {
        for (int i = 0; i < bindings.size(); i++) {
            Object v = bindings.get(i);
            // java.time 类型多数驱动不直接认,转成 JDBC 标准类型
            if (v instanceof java.time.Instant inst) {
                ps.setTimestamp(i + 1, java.sql.Timestamp.from(inst));
            } else if (v instanceof java.time.LocalDateTime ldt) {
                ps.setTimestamp(i + 1, java.sql.Timestamp.valueOf(ldt));
            } else if (v instanceof java.time.LocalDate ld) {
                ps.setDate(i + 1, java.sql.Date.valueOf(ld));
            } else if (v instanceof Enum<?> en) {
                ps.setString(i + 1, en.name());
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    private void record(String sql, List<Object> bindings, long startNanos) {
        if (!logging) return;
        long micros = (System.nanoTime() - startNanos) / 1000;
        log.add(new QueryLog(sql, List.copyOf(bindings), micros, callerHint()));
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
