package io.jaravel.migration.schema;

import java.util.List;
import java.util.function.Consumer;

import io.jaravel.database.Connection;

/**
 * Schema 操作的入口。
 *
 * <pre>{@code
 * Schema.create("posts", t -> {
 *     t.id();
 *     t.string("title", 200);
 *     t.timestamps();
 * });
 * }</pre>
 *
 * <p>是实例 + 静态门面两套:静态方法用默认连接(迁移里的常态),
 * {@code Schema.on(connection)} 返回实例用于多库场景。全静态会让
 * 多数据源无从下手,全实例又让每个迁移都要先拿连接 —— 两者都要。
 */
public final class Schema {

    private final Connection connection;
    private final SchemaGrammar grammar;

    private Schema(Connection connection, SchemaGrammar grammar) {
        this.connection = connection;
        this.grammar = grammar;
    }

    // ================= 连接选择 =================

    /**
     * 默认连接。
     *
     * <p>migration 模块自己持有,**不去读 Eloquent 的连接** —— 对 eloquent 的
     * 依赖是 optional 的(只用 Schema 和迁移、不用 ORM 是正当用法),
     * 跨过去取会在运行时变成 NoClassDefFoundError。
     * Spring starter 与测试基类负责把两边都设上。
     */
    private static volatile Connection defaultConnection;

    public static void useConnection(Connection connection) {
        defaultConnection = connection;
    }

    /** 当前默认连接,未设置时为 null。供需要临时替换再还原的场景使用。 */
    public static Connection currentConnection() {
        return defaultConnection;
    }

    /** 当前默认连接上的 Schema */
    public static Schema instance() {
        Connection c = defaultConnection;
        if (c == null) {
            throw new IllegalStateException("""
                    Schema 还没有数据库连接。
                      在 Spring 环境下由 starter 自动注入;脱离框架使用时请先调用
                        Schema.useConnection(new JdbcConnection(dataSource, new MySqlGrammar()));
                      测试里通常在 @BeforeEach 设置。""");
        }
        return new Schema(c, grammarFor(c));
    }

    public static Schema on(Connection connection) {
        return new Schema(connection, grammarFor(connection));
    }

    public static Schema connection(String name) {
        throw new UnsupportedOperationException(
                "命名连接要等 v0.3 的多数据源配置落地。"
              + "\n  当前可用:Schema.on(connection) 直接传连接对象。");
    }

    /**
     * 按查询语法的方言名挑 DDL 语法。
     *
     * <p>两套 Grammar 是刻意分开的:DML 的方言差异(引号、LIMIT)和 DDL 的
     * 方言差异(类型映射、ALTER 能力)几乎不重叠,合成一个类会变成
     * 一个什么都管的大类。这里只用名字把它们对上。
     */
    static SchemaGrammar grammarFor(Connection connection) {
        String dialect = connection.grammar().name();
        return switch (dialect) {
            case "sqlite"   -> new SqliteSchemaGrammar();
            case "mysql"    -> new MySqlSchemaGrammar();
            case "postgres" -> new PostgresSchemaGrammar();
            default -> throw new UnsupportedOperationException(
                    "没有对应 " + dialect + " 的 Schema 语法。"
                  + "\n  已支持:sqlite / mysql / postgres"
                  + "\n  逃生口:用 DB.statement(\"create table ...\") 直接写 DDL。");
        };
    }

    // ================= 静态门面 =================

    public static void create(String table, Consumer<Blueprint> definition) {
        instance().createTable(table, definition);
    }

    public static void table(String table, Consumer<Blueprint> definition) {
        instance().alterTable(table, definition);
    }

    public static void drop(String table)          { instance().dropTable(table); }
    public static void dropIfExists(String table)  { instance().dropTableIfExists(table); }
    public static void rename(String from, String to) { instance().renameTable(from, to); }

    public static boolean hasTable(String table)   { return instance().tableExists(table); }

    public static boolean hasColumn(String table, String column) {
        return instance().columnListing(table).stream().anyMatch(c -> c.equalsIgnoreCase(column));
    }

    public static boolean hasColumns(String table, List<String> columns) {
        var existing = instance().columnListing(table).stream()
                .map(String::toLowerCase).toList();
        return columns.stream().allMatch(c -> existing.contains(c.toLowerCase()));
    }

    public static List<String> getColumnListing(String table) {
        return instance().columnListing(table);
    }

    public static void disableForeignKeyConstraints() {
        instance().setForeignKeyConstraints(false);
    }

    public static void enableForeignKeyConstraints() {
        instance().setForeignKeyConstraints(true);
    }

    /**
     * 临时关闭外键约束执行一段操作。
     *
     * <p>用 try/finally 保证恢复 —— 忘了开回来的后果是**之后所有迁移和
     * Seeder 都不再校验外键**,而且不会报错,等到线上数据对不上才发现。
     */
    public static void withoutForeignKeyConstraints(Runnable body) {
        Schema schema = instance();
        schema.setForeignKeyConstraints(false);
        try {
            body.run();
        } finally {
            schema.setForeignKeyConstraints(true);
        }
    }

    /**
     * 只在指定方言上执行。
     *
     * <p>有些 DDL 本质上是方言特定的(MySQL 的 ALTER COLUMN、PG 的 GIN 索引)。
     * 与其让迁移在别的库上崩,不如显式圈出来。
     */
    public static void onlyOn(String dialect, Runnable body) {
        if (instance().grammar.name().equals(dialect)) body.run();
    }

    // ================= 实例方法 =================

    public void createTable(String table, Consumer<Blueprint> definition) {
        var blueprint = new Blueprint(table);
        definition.accept(blueprint);
        run(grammar.compileCreate(blueprint));
    }

    public void alterTable(String table, Consumer<Blueprint> definition) {
        var blueprint = new Blueprint(table);
        definition.accept(blueprint);
        run(grammar.compileAlter(blueprint));
    }

    public void dropTable(String table) {
        connection.statement(grammar.compileDrop(table));
    }

    public void dropTableIfExists(String table) {
        connection.statement(grammar.compileDropIfExists(table));
    }

    public void renameTable(String from, String to) {
        connection.statement(grammar.compileRename(from, to));
    }

    public boolean tableExists(String table) {
        return !connection.select(grammar.compileTableExists(table)).isEmpty();
    }

    public List<String> columnListing(String table) {
        return connection.select(grammar.compileColumnListing(table))
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    public void setForeignKeyConstraints(boolean enabled) {
        connection.statement(enabled
                ? grammar.compileEnableForeignKeys()
                : grammar.compileDisableForeignKeys());
    }

    /** 生成但不执行 —— 供 {@code schema:dump} 与测试使用 */
    public List<String> toSql(String table, Consumer<Blueprint> definition) {
        var blueprint = new Blueprint(table);
        definition.accept(blueprint);
        return grammar.compileCreate(blueprint);
    }

    public SchemaGrammar grammar() {
        return grammar;
    }

    private void run(List<String> statements) {
        for (String sql : statements) {
            connection.statement(sql);
        }
    }
}
