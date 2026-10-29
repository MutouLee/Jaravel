package io.jaravel.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.database.grammar.Grammar;
import io.jaravel.database.grammar.MySqlGrammar;
import io.jaravel.database.grammar.PostgresGrammar;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.database.query.QueryBuilder;
import io.jaravel.database.query.QueryState.Op;

class DatabaseTest {

    // ================= SQL 编译(不连库) =================

    @Nested
    @DisplayName("SQL 编译")
    class Compilation {

        private QueryBuilder q(Grammar g) {
            return new QueryBuilder(null, g).from("users");
        }

        private QueryBuilder mysql()  { return q(new MySqlGrammar()); }
        private QueryBuilder sqlite() { return q(new SqliteGrammar()); }
        private QueryBuilder pg()     { return q(new PostgresGrammar()); }

        @Test void basicSelect() {
            assertThat(mysql().toSql()).isEqualTo("select * from `users`");
            assertThat(sqlite().toSql()).isEqualTo("select * from \"users\"");
        }

        @Test void selectColumnsAndAlias() {
            assertThat(mysql().select("id", "name as display").toSql())
                    .isEqualTo("select `id`, `name` as `display` from `users`");
        }

        @Test void tableQualifiedColumns() {
            assertThat(mysql().select("users.id").toSql())
                    .isEqualTo("select `users`.`id` from `users`");
        }

        @Test void whereClauses() {
            var c = mysql().where("status", "active").where("age", ">", 18).compile();
            assertThat(c.sql()).isEqualTo(
                    "select * from `users` where `status` = ? and `age` > ?");
            assertThat(c.bindings()).containsExactly("active", 18);
        }

        @Test void orWhere() {
            assertThat(mysql().where("a", 1).orWhere("b", 2).toSql())
                    .isEqualTo("select * from `users` where `a` = ? or `b` = ?");
        }

        @Test
        @DisplayName("嵌套分组产生括号 —— 没有它,and/or 优先级会与直觉不符")
        void nestedGrouping() {
            var c = mysql().where("x", 1)
                           .where(g -> g.where("a", 1).orWhere("b", 2))
                           .compile();
            assertThat(c.sql()).isEqualTo(
                    "select * from `users` where `x` = ? and (`a` = ? or `b` = ?)");
            assertThat(c.bindings()).containsExactly(1, 1, 2);
        }

        @Test
        @DisplayName("where(col, null) 编译为 is null,而不是 = null(后者恒为 unknown)")
        void nullBecomesIsNull() {
            assertThat(mysql().where("deleted_at", null).toSql())
                    .isEqualTo("select * from `users` where `deleted_at` is null");
            assertThat(mysql().where("deleted_at", Op.NEQ, null).toSql())
                    .isEqualTo("select * from `users` where `deleted_at` is not null");
        }

        @Test void whereInAndNotIn() {
            var c = mysql().whereIn("id", List.of(1, 2, 3)).compile();
            assertThat(c.sql()).isEqualTo("select * from `users` where `id` in (?, ?, ?)");
            assertThat(c.bindings()).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("空 IN 不能编译成 in () —— 那是语法错误。空 IN 恒假,空 NOT IN 恒真。")
        void emptyInIsHandled() {
            assertThat(mysql().whereIn("id", List.of()).toSql())
                    .isEqualTo("select * from `users` where 0 = 1");
            assertThat(mysql().whereNotIn("id", List.of()).toSql())
                    .isEqualTo("select * from `users` where 1 = 1");
        }

        @Test void whereBetweenAndColumn() {
            assertThat(mysql().whereBetween("age", 18, 65).toSql())
                    .isEqualTo("select * from `users` where `age` between ? and ?");
            assertThat(mysql().whereColumn("updated_at", Op.GT, "created_at").toSql())
                    .isEqualTo("select * from `users` where `updated_at` > `created_at`");
        }

        @Test void joinsGroupsOrdersLimit() {
            var c = mysql()
                    .select("users.id", "posts.title")
                    .join("posts", "users.id", "=", "posts.user_id")
                    .where("users.status", "active")
                    .groupBy("users.id")
                    .having("count(*)", ">", 2)
                    .orderByDesc("users.created_at")
                    .limit(10).offset(20)
                    .compile();

            assertThat(c.sql()).isEqualTo(
                    "select `users`.`id`, `posts`.`title` from `users`"
                  + " inner join `posts` on `users`.`id` = `posts`.`user_id`"
                  + " where `users`.`status` = ?"
                  + " group by `users`.`id`"
                  + " having count(*) > ?"
                  + " order by `users`.`created_at` desc"
                  + " limit ? offset ?");
            assertThat(c.bindings()).containsExactly("active", 2, 10, 20);
        }

        @Test
        @DisplayName("只给 offset 不给 limit 时要补一个 limit(多数方言的语法要求)")
        void offsetWithoutLimit() {
            assertThat(mysql().offset(10).toSql())
                    .isEqualTo("select * from `users` limit ? offset ?");
        }

        @Test void whereExistsSubquery() {
            var c = mysql().whereExists(sub -> sub.from("orders")
                                                  .whereColumn("orders.user_id", "users.id")
                                                  .where("orders.paid", true))
                           .compile();
            assertThat(c.sql()).contains("exists (select * from `orders` where");
            assertThat(c.bindings()).containsExactly(true);
        }

        @Test
        @DisplayName("ILIKE 只有 PostgreSQL 原生支持,其余方言降级为 LIKE")
        void ilikeDegradesGracefully() {
            assertThat(pg().whereILike("name", "%tom%").toSql()).contains("ilike ?");
            assertThat(mysql().whereILike("name", "%tom%").toSql()).contains("like ?");
            assertThat(sqlite().whereILike("name", "%tom%").toSql()).contains("like ?");
        }

        @Test void whenUnlessDoNotBreakChaining() {
            String filtered = mysql().when(true, b -> b.where("status", "active")).toSql();
            String plain = mysql().when(false, b -> b.where("status", "active")).toSql();
            assertThat(filtered).contains("where");
            assertThat(plain).doesNotContain("where");
        }

        @Test void insertUpdateDelete() {
            var g = new MySqlGrammar();
            var qb = new QueryBuilder(null, g).from("users");

            assertThat(g.compileInsert(qb.state(), List.of(Map.of("name", "Tom"))).sql())
                    .isEqualTo("insert into `users` (`name`) values (?)");

            var upd = g.compileUpdate(new QueryBuilder(null, g).from("users")
                    .where("id", 1).state(), new java.util.LinkedHashMap<>(Map.of("name", "X")));
            assertThat(upd.sql()).isEqualTo("update `users` set `name` = ? where `id` = ?");
            assertThat(upd.bindings()).containsExactly("X", 1);
        }

        @Test
        @DisplayName("批量 insert 各行列不一致时报错并指出哪一行")
        void batchInsertColumnMismatch() {
            var g = new MySqlGrammar();
            var state = new QueryBuilder(null, g).from("users").state();
            assertThatThrownBy(() -> g.compileInsert(state,
                    List.of(Map.of("a", 1), Map.of("b", 2))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("相同的列")
                    .hasMessageContaining("第 2 行");
        }

        @Test void jsonPathPerDialect() {
            assertThat(mysql().select("meta->tags").toSql())
                    .contains("json_extract(`meta`, '$.tags')");
            assertThat(pg().select("meta->>tags").toSql())
                    .contains("\"meta\"->>'tags'");
            assertThat(sqlite().select("meta->tags").toSql())
                    .contains("json_extract(\"meta\", '$.tags')");
        }

        @Test
        @DisplayName("toRawSql 内联绑定值,便于复制到数据库客户端(仅调试用)")
        void toRawSqlInlinesBindings() {
            assertThat(mysql().where("name", "O'Brien").where("age", 30).toRawSql())
                    .isEqualTo("select * from `users` where `name` = 'O''Brien' and `age` = 30");
        }
    }

    // ================= 安全 =================

    @Nested
    @DisplayName("注入防护")
    class Security {

        private QueryBuilder q() { return new QueryBuilder(null, new MySqlGrammar()).from("users"); }

        @Test
        @DisplayName("列名含可疑字符时拒绝 —— 列名无法参数化,只能靠白名单")
        void maliciousColumnNameIsRejected() {
            assertThatThrownBy(() -> q().select("name; drop table users--").toSql())
                    .isInstanceOf(Grammar.UnsafeIdentifierException.class)
                    .hasMessageContaining("不允许的字符");

            assertThatThrownBy(() -> q().where("1=1 or ''='", "x").toSql())
                    .isInstanceOf(Grammar.UnsafeIdentifierException.class);
        }

        @Test
        @DisplayName("列名里的包裹符会被拒绝,不能用它逃出引号")
        void quoteCharInIdentifierIsRejected() {
            assertThatThrownBy(() -> q().select("na`me").toSql())
                    .isInstanceOf(Grammar.UnsafeIdentifierException.class);
        }

        @Test
        @DisplayName("值里的引号与分号完全无害 —— 它们走占位符")
        void valuesAreAlwaysParameterized() {
            var c = q().where("name", "'; drop table users; --").compile();
            assertThat(c.sql()).isEqualTo("select * from `users` where `name` = ?");
            assertThat(c.bindings()).containsExactly("'; drop table users; --");
        }

        @Test
        @DisplayName("★ 无 where 的 update/delete 默认被拦住 —— 这几乎总是 bug")
        void unconditionalWriteIsBlocked() {
            assertThatThrownBy(() -> q().delete())
                    .isInstanceOf(QueryBuilder.UnconditionalWriteException.class)
                    .hasMessageContaining("全部行")
                    .hasMessageContaining("allowUnconditional");

            assertThatThrownBy(() -> q().update(Map.of("x", 1)))
                    .isInstanceOf(QueryBuilder.UnconditionalWriteException.class);
        }
    }

    // ================= 真实 SQLite 执行 =================

    @Nested
    @DisplayName("真实 SQLite 执行(SQL 不能只是『看起来对』)")
    class RealExecution {

        private Connection conn;

        /**
         * 单连接 DataSource:SQLite 的内存库与连接绑定,连接一关数据就没了。
         * 因此持有一个连接,并用动态代理把 close() 变成空操作 ——
         * JdbcConnection 会归还借来的连接,不能让它真的关掉。
         */
        private DataSource singleConnectionDataSource() {
            return new DataSource() {
                private java.sql.Connection real;
                private java.sql.Connection shielded;

                @Override public synchronized java.sql.Connection getConnection() throws SQLException {
                    if (real == null || real.isClosed()) {
                        real = DriverManager.getConnection("jdbc:sqlite::memory:");
                        shielded = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.Connection.class.getClassLoader(),
                                new Class<?>[]{java.sql.Connection.class},
                                (proxy, method, args) -> {
                                    if ("close".equals(method.getName())) return null;   // 不真关
                                    if ("isClosed".equals(method.getName())) return false;
                                    try {
                                        return method.invoke(real, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    return shielded;
                }
                @Override public java.sql.Connection getConnection(String u, String p) throws SQLException {
                    return getConnection();
                }
                @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
                @Override public boolean isWrapperFor(Class<?> i) { return false; }
                @Override public java.io.PrintWriter getLogWriter() { return null; }
                @Override public void setLogWriter(java.io.PrintWriter o) {}
                @Override public void setLoginTimeout(int s) {}
                @Override public int getLoginTimeout() { return 0; }
                @Override public Logger getParentLogger() { return null; }
            };
        }

        @BeforeEach void setUp() {
            conn = new JdbcConnection(singleConnectionDataSource(), new SqliteGrammar());
            conn.statement("""
                create table users (
                  id integer primary key autoincrement,
                  name text not null,
                  email text,
                  age integer,
                  balance numeric,
                  active integer default 1,
                  created_at text
                )
                """);
        }

        @Test void insertAndSelect() {
            conn.table("users").insert(Map.of("name", "Tom", "email", "t@a.com", "age", 30));
            conn.table("users").insert(Map.of("name", "Amy", "email", "a@a.com", "age", 25));

            var all = conn.table("users").orderBy("id").get();
            assertThat(all).hasSize(2);
            assertThat(all.first().orElseThrow().string("name")).isEqualTo("Tom");
        }

        @Test void insertGetIdReturnsAutoIncrement() {
            var id = conn.table("users").insertGetId(Map.of("name", "Tom"));
            assertThat(id).isPresent();
            assertThat(id.get()).isPositive();
        }

        @Test void whereFilteringWorksEndToEnd() {
            conn.table("users").insert(List.of(
                    new java.util.LinkedHashMap<>(Map.of("name", "Tom", "age", 30)),
                    new java.util.LinkedHashMap<>(Map.of("name", "Amy", "age", 25)),
                    new java.util.LinkedHashMap<>(Map.of("name", "Bob", "age", 35))));

            assertThat(conn.table("users").where("age", ">", 28).get()).hasSize(2);
            assertThat(conn.table("users").whereIn("name", "Tom", "Bob").get()).hasSize(2);
            assertThat(conn.table("users").whereBetween("age", 26, 31).get()).hasSize(1);
            assertThat(conn.table("users").whereNull("email").get()).hasSize(3);
        }

        @Test void aggregatesReturnRealNumbers() {
            conn.table("users").insert(List.of(
                    new java.util.LinkedHashMap<>(Map.of("name", "A", "age", 10, "balance", 1.5)),
                    new java.util.LinkedHashMap<>(Map.of("name", "B", "age", 20, "balance", 2.5))));

            assertThat(conn.table("users").count()).isEqualTo(2);
            assertThat(conn.table("users").sum("age")).contains(new BigDecimal("30"));
            assertThat(conn.table("users").max("age")).contains(20);
            assertThat(conn.table("users").min("age")).contains(10);
            assertThat(conn.table("users").where("age", ">", 15).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("聚合时自动去掉 order by 与 limit,且之后链式状态可复用")
        void aggregateRestoresState() {
            conn.table("users").insert(Map.of("name", "A", "age", 1));
            var q = conn.table("users").orderByDesc("id").limit(1);
            assertThat(q.count()).isEqualTo(1);
            assertThat(q.toSql()).contains("order by").contains("limit");   // 状态已还原
        }

        @Test void updateAndDelete() {
            conn.table("users").insert(Map.of("name", "Tom", "age", 30));

            int updated = conn.table("users").where("name", "Tom")
                              .update(Map.of("age", 31));
            assertThat(updated).isEqualTo(1);
            assertThat(conn.table("users").where("name", "Tom").first()
                           .orElseThrow().intValue("age")).isEqualTo(31);

            assertThat(conn.table("users").where("name", "Tom").delete()).isEqualTo(1);
            assertThat(conn.table("users").count()).isZero();
        }

        @Test void incrementAvoidsReadModifyWrite() {
            conn.table("users").insert(Map.of("name", "Tom", "age", 30));
            conn.table("users").where("name", "Tom").increment("age", 5);
            assertThat(conn.table("users").first().orElseThrow().intValue("age")).isEqualTo(35);

            conn.table("users").where("name", "Tom").decrement("age", 10);
            assertThat(conn.table("users").first().orElseThrow().intValue("age")).isEqualTo(25);
        }

        @Test void pluckAndValue() {
            conn.table("users").insert(List.of(
                    new java.util.LinkedHashMap<>(Map.of("name", "A")),
                    new java.util.LinkedHashMap<>(Map.of("name", "B"))));
            assertThat(conn.table("users").orderBy("id").pluck("name", String.class).toList())
                    .containsExactly("A", "B");
            assertThat(conn.table("users").orderBy("id").value("name", String.class)).contains("A");
        }

        @Test void existsAndDoesntExist() {
            assertThat(conn.table("users").exists()).isFalse();
            conn.table("users").insert(Map.of("name", "Tom"));
            assertThat(conn.table("users").where("name", "Tom").exists()).isTrue();
            assertThat(conn.table("users").where("name", "Nope").doesntExist()).isTrue();
        }

        @Test
        @DisplayName("SQLite 用 0/1 表示布尔,Row.bool 负责归一化")
        void booleanNormalizationAcrossDialects() {
            conn.table("users").insert(Map.of("name", "Tom", "active", 1));
            assertThat(conn.table("users").first().orElseThrow().bool("active")).isTrue();
            conn.table("users").where("name", "Tom").update(Map.of("active", 0));
            assertThat(conn.table("users").first().orElseThrow().bool("active")).isFalse();
        }

        @Test
        @DisplayName("列名拼错时报错给出『是否想找 X ?』")
        void unknownColumnSuggestion() {
            conn.table("users").insert(Map.of("name", "Tom"));
            var row = conn.table("users").first().orElseThrow();
            assertThatThrownBy(() -> row.get("nmae"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("是否想找 name");
        }

        // ---------- 事务 ----------

        @Test void transactionCommits() {
            conn.transaction(() -> {
                conn.table("users").insert(Map.of("name", "Tom"));
                conn.table("users").insert(Map.of("name", "Amy"));
            });
            assertThat(conn.table("users").count()).isEqualTo(2);
        }

        @Test void transactionRollsBackOnException() {
            assertThatThrownBy(() -> conn.transaction(() -> {
                conn.table("users").insert(Map.of("name", "Tom"));
                throw new RuntimeException("boom");
            })).hasMessage("boom");

            assertThat(conn.table("users").count()).isZero();
        }

        @Test
        @DisplayName("嵌套事务用保存点:内层回滚不影响外层已做的操作")
        void nestedTransactionUsesSavepoint() {
            conn.transaction(() -> {
                conn.table("users").insert(Map.of("name", "outer"));
                try {
                    conn.transaction(() -> {
                        conn.table("users").insert(Map.of("name", "inner"));
                        throw new RuntimeException("inner fails");
                    });
                } catch (RuntimeException ignored) {
                    // 内层失败不影响外层
                }
            });
            assertThat(conn.table("users").pluck("name", String.class).toList())
                    .containsExactly("outer");
        }

        @Test
        @DisplayName("afterCommit 在提交后才执行 —— 消除「事务未提交就发消息」的经典 bug")
        void afterCommitRunsAfterCommit() {
            var events = new java.util.ArrayList<String>();
            conn.transaction(() -> {
                conn.table("users").insert(Map.of("name", "Tom"));
                conn.afterCommit(() -> events.add("hook ran, rows=" + conn.table("users").count()));
                events.add("inside transaction");
            });
            assertThat(events).containsExactly("inside transaction", "hook ran, rows=1");
        }

        @Test
        @DisplayName("回滚时 afterCommit 回调不执行")
        void afterCommitSkippedOnRollback() {
            var events = new java.util.ArrayList<String>();
            assertThatThrownBy(() -> conn.transaction(() -> {
                conn.afterCommit(() -> events.add("should not run"));
                throw new RuntimeException("boom");
            })).hasMessage("boom");
            assertThat(events).isEmpty();
        }

        @Test
        @DisplayName("不在事务中调用 afterCommit 则立即执行")
        void afterCommitOutsideTransactionRunsImmediately() {
            var events = new java.util.ArrayList<String>();
            conn.afterCommit(() -> events.add("ran"));
            assertThat(events).containsExactly("ran");
        }

        // ---------- 映射与分块 ----------

        record UserDto(Long id, String name, Integer age) {}

        @Test
        @DisplayName("映射到 record,snake_case 列名自动匹配 camelCase 组件")
        void mapsToRecord() {
            conn.table("users").insert(Map.of("name", "Tom", "age", 30));
            var dto = conn.table("users").select("id", "name", "age").get(UserDto.class)
                          .firstOrFail();
            assertThat(dto.name()).isEqualTo("Tom");
            assertThat(dto.age()).isEqualTo(30);
        }

        /** 模拟 ADR-010 的模型形态:public 字段 */
        public static class UserModel {
            public Long id;
            public String name;
            public Integer age;
            public java.time.Instant createdAt;
        }

        @Test
        @DisplayName("映射到 public 字段的 POJO(ADR-010 的模型形态)")
        void mapsToPublicFieldPojo() {
            conn.table("users").insert(Map.of("name", "Tom", "age", 30));
            var m = conn.table("users").select("id", "name", "age").get(UserModel.class)
                        .firstOrFail();
            assertThat(m.name).isEqualTo("Tom");
            assertThat(m.age).isEqualTo(30);
        }

        @Test
        @DisplayName("缺列时报错指出需要哪列并给建议")
        void mappingMissingColumn() {
            conn.table("users").insert(Map.of("name", "Tom"));
            assertThatThrownBy(() -> conn.table("users").select("name").get(UserDto.class).firstOrFail())
                    .isInstanceOf(RowMapper.MappingException.class)
                    .hasMessageContaining("需要列 id");
        }

        @Test
        @DisplayName("chunkById 按主键游走,不受并发插入影响")
        void chunkById() {
            for (int i = 1; i <= 25; i++) {
                conn.table("users").insert(Map.of("name", "u" + i));
            }
            var seen = new java.util.ArrayList<String>();
            conn.table("users").chunkById(10, batch -> batch.each(r -> seen.add(r.string("name"))));
            assertThat(seen).hasSize(25);
            assertThat(seen).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("查询日志记录 SQL、绑定值、耗时与调用点(SQL 面板的数据源)")
        void queryLog() {
            conn.enableQueryLog();
            conn.table("users").where("name", "Tom").get();
            var log = conn.queryLog();
            assertThat(log).hasSize(1);
            assertThat(log.get(0).sql()).contains("where \"name\" = ?");
            assertThat(log.get(0).bindings()).containsExactly("Tom");
            assertThat(log.get(0).caller()).contains("DatabaseTest");
        }

        @Test
        @DisplayName("SQL 执行失败时,异常里带上 SQL 与绑定值")
        void failureIncludesSqlAndBindings() {
            assertThatThrownBy(() -> conn.select("select * from no_such_table", List.of()))
                    .isInstanceOf(Connection.QueryFailedException.class)
                    .hasMessageContaining("SQL: select * from no_such_table");
        }
    }

}
