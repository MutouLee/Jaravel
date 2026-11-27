package net.dobeen.jaravel.benchmark;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.database.Row;
import net.dobeen.jaravel.database.grammar.Grammar;
import net.dobeen.jaravel.database.grammar.SqliteGrammar;
import net.dobeen.jaravel.support.Coll;

/**
 * 查询构造器「不执行」的那一半:拼 SQL + 绑定值。
 *
 * <p>v0.2 的实测发现,按主键查询时这一段占框架附加开销的三成,
 * 当时给最简单的形态加了 SQL 缓存。这里把几种常见形态分开量,
 * 用来判断缓存该不该扩大、以及复杂 where 的成本在哪。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class QueryBuildBenchmark {

    private final Connection connection = new StubConnection(new SqliteGrammar());

    @Benchmark
    public String simpleSelect() {
        return connection.table("users")
                .select("id", "name", "email")
                .where("active", true)
                .orderBy("id")
                .limit(20)
                .toSql();
    }

    @Benchmark
    public String joinAndGroup() {
        return connection.table("orders", "o")
                .select("o.user_id", "SUM(o.total) AS total")
                .join("users", "users.id", "=", "o.user_id")
                .where("o.status", "paid")
                .whereBetween("o.created_at", "2026-01-01", "2026-12-31")
                .groupBy("o.user_id")
                .having("SUM(o.total)", ">", 1000)
                .toSql();
    }

    @Benchmark
    public String nestedWhere() {
        return connection.table("posts")
                .where("published", true)
                .where(w -> w.where("author_id", 7).orWhere("editor_id", 7))
                .whereIn("tag_id", List.of(1, 2, 3, 4, 5))
                .orderByDesc("published_at")
                .toSql();
    }

    /** 只给 compile 用的替身:这些方法不会被调用。 */
    private static final class StubConnection implements Connection {
        private final Grammar grammar;

        private StubConnection(Grammar grammar) {
            this.grammar = grammar;
        }

        @Override public Grammar grammar() { return grammar; }
        @Override public Coll<Row> select(String sql, List<Object> bindings) { throw unused(); }
        @Override public <T> Coll<T> select(String sql, List<Object> bindings, Class<T> type) { throw unused(); }
        @Override public int update(String sql, List<Object> bindings) { throw unused(); }
        @Override public Optional<Long> insertGetId(String sql, List<Object> bindings) { throw unused(); }
        @Override public void statement(String sql) { throw unused(); }
        @Override public <T> T transaction(Supplier<T> body) { throw unused(); }
        @Override public void afterCommit(Runnable action) { }
        @Override public boolean inTransaction() { return false; }
        @Override public void enableQueryLog() { }
        @Override public void disableQueryLog() { }
        @Override public List<QueryLog> queryLog() { return List.of(); }
        @Override public void flushQueryLog() { }
        @Override public Optional<Object> raw() { return Optional.empty(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("benchmark stub");
        }
    }
}
