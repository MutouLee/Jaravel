package io.jaravel.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.jaravel.database.Connection;
import io.jaravel.database.query.QueryBuilder;

/**
 * {@code unique} 与 {@code exists} —— 唯一两条需要查库的规则。
 *
 * <p>单独成类而不是塞进 {@link Rules}:它们需要数据库连接,而
 * {@code jaravel-database} 在 validation 模块里是 **optional** 依赖。
 * 隔离在这里,不用这两条规则的人就不会在运行时遇到
 * {@code NoClassDefFoundError}。
 */
public final class DatabaseRule {

    private DatabaseRule() {}

    /** 校验用的连接。由 starter 或测试注入。 */
    private static volatile Connection connection;

    public static void useConnection(Connection c) {
        connection = c;
    }

    static Connection connection() {
        Connection c = connection;
        if (c == null) {
            throw new IllegalStateException("""
                    unique / exists 规则需要数据库连接,但还没有设置。
                      Spring 环境下由 starter 自动注入;脱离框架使用时请先调用
                        DatabaseRule.useConnection(connection);
                      若只是不想让这条规则查库,把它换成自定义规则:
                        Rule.of("unique", v -> myRepository.isFree(v))""");
        }
        return c;
    }

    // ================= unique =================

    /**
     * 字段值在表中唯一。
     *
     * <pre>{@code
     * Rule.unique("users", "email")                     // 新建
     * Rule.unique("users", "email").ignore(user.id)     // 更新:排除自己
     * Rule.unique("users", "email").where(q -> q.where("tenant_id", tid))
     * }</pre>
     *
     * <p>{@code ignore()} 不是可选的锦上添花:忘了它,用户「编辑资料但没改邮箱」
     * 就会被自己的邮箱判为重复。这是这条规则最常见的误用,所以文档和报错里都要点出来。
     */
    public static final class Unique implements Rule {

        private final String table;
        private final String column;
        private Object ignoreValue;
        private String ignoreColumn = "id";
        private Consumer<QueryBuilder> constraint;

        Unique(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public Unique ignore(Object keyValue) {
            this.ignoreValue = keyValue;
            return this;
        }

        public Unique ignore(Object keyValue, String keyColumn) {
            this.ignoreValue = keyValue;
            this.ignoreColumn = keyColumn;
            return this;
        }

        public Unique where(Consumer<QueryBuilder> constraint) {
            this.constraint = constraint;
            return this;
        }

        /** 常用于软删除表:只在未删除的行里查重 */
        public Unique whereNull(String nullColumn) {
            return where(q -> q.whereNull(nullColumn));
        }

        @Override public String name() { return "unique"; }

        @Override
        public boolean passes(Context c) {
            if (c.value() == null) return true;        // null 的唯一性交给 required 管

            QueryBuilder query = connection().table(table).where(column, c.value());
            if (ignoreValue != null) {
                query.where(ignoreColumn, io.jaravel.database.query.QueryState.Op.NEQ, ignoreValue);
            }
            if (constraint != null) constraint.accept(query);
            return !query.exists();
        }

        @Override
        public Map<String, String> replacements() {
            return Map.of("table", table, "column", column);
        }
    }

    // ================= exists =================

    /** 字段值必须在表中存在。数组字段会逐元素检查。 */
    public static final class Exists implements Rule {

        private final String table;
        private final String column;
        private final Map<String, Object> extraConditions = new LinkedHashMap<>();
        private Consumer<QueryBuilder> constraint;

        Exists(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public Exists where(String conditionColumn, Object value) {
            extraConditions.put(conditionColumn, value);
            return this;
        }

        public Exists where(Consumer<QueryBuilder> constraint) {
            this.constraint = constraint;
            return this;
        }

        @Override public String name() { return "exists"; }

        @Override
        public boolean passes(Context c) {
            Object value = c.value();
            if (value == null) return true;

            // 数组字段:一次 IN 查询核对全部,而不是每个元素一条
            if (value instanceof java.util.Collection<?> items) {
                if (items.isEmpty()) return true;
                QueryBuilder query = baseQuery().whereIn(column, items);
                long found = query.distinct().count(column);
                return found >= items.stream().distinct().count();
            }
            return baseQuery().where(column, value).exists();
        }

        private QueryBuilder baseQuery() {
            QueryBuilder query = connection().table(table);
            extraConditions.forEach(query::where);
            if (constraint != null) constraint.accept(query);
            return query;
        }

        @Override
        public Map<String, String> replacements() {
            return Map.of("table", table, "column", column);
        }
    }

    // ================= 字符串写法的解析 =================

    /** {@code unique:users,email[,except_id[,id_column]]} */
    static Rule parseUnique(List<String> args) {
        requireArgs("unique", args, 2, "unique:users,email");
        var rule = new Unique(args.get(0).trim(), args.get(1).trim());

        if (args.size() >= 3 && !args.get(2).isBlank() && !args.get(2).trim().equals("NULL")) {
            String ignoreColumn = args.size() >= 4 ? args.get(3).trim() : "id";
            rule.ignore(args.get(2).trim(), ignoreColumn);
        }
        return rule;
    }

    /** {@code exists:tags,id[,extra_column,extra_value]...} */
    static Rule parseExists(List<String> args) {
        requireArgs("exists", args, 2, "exists:tags,id");
        var rule = new Exists(args.get(0).trim(), args.get(1).trim());

        // 成对的附加条件
        for (int i = 2; i + 1 < args.size(); i += 2) {
            rule.where(args.get(i).trim(), args.get(i + 1).trim());
        }
        if ((args.size() - 2) % 2 != 0) {
            throw new IllegalArgumentException(
                    "exists 的附加条件必须成对出现(列,值),当前有 "
                  + (args.size() - 2) + " 个多余参数。"
                  + "\n  写法:exists:tags,id,status,active");
        }
        return rule;
    }

    private static void requireArgs(String rule, List<String> args, int min, String usage) {
        if (args.size() >= min) return;
        throw new IllegalArgumentException(
                "规则 " + rule + " 需要至少 " + min + " 个参数(表名、列名),实际给了 "
              + args.size() + "。\n  写法:" + usage);
    }
}
