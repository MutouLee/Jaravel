package io.jaravel.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;

import io.jaravel.database.Connection;
import io.jaravel.eloquent.Model;

/**
 * 数据库断言(docs/14 §2.3)。
 *
 * <p>实现上**直接走查询构造器**而不是 Eloquent:断言要能对任何表生效,
 * 包括没有模型的中间表({@code post_tag})与框架自己的表
 * ({@code migrations} / {@code password_reset_tokens})。
 *
 * <h2>失败消息要能回答「那库里到底有什么」</h2>
 * <p>{@code assertDatabaseHas("posts", {"title": "X"})} 失败时,
 * 光说「没找到」没用 —— 可能是标题带了空格、可能是外键没对上、
 * 也可能整张表是空的。所以消息里带上**表里实际有的前几行**。
 */
final class DatabaseAssertions {

    /** 失败消息里最多列几行 */
    private static final int SAMPLE_ROWS = 5;

    private DatabaseAssertions() {
    }

    static void has(Connection connection, String table, Map<String, ?> attributes) {
        if (matchCount(connection, table, attributes) > 0) return;

        Assertions.fail("""
                表 %s 里没有匹配 %s 的行。
                %s"""
                .formatted(table, attributes, sample(connection, table)));
    }

    static void missing(Connection connection, String table, Map<String, ?> attributes) {
        long found = matchCount(connection, table, attributes);
        if (found == 0) return;

        Assertions.fail("""
                表 %s 里不该有匹配 %s 的行,实际找到 %d 行。
                %s"""
                .formatted(table, attributes, found, sample(connection, table)));
    }

    static void count(Connection connection, String table, long expected) {
        long actual = connection.table(table).count();
        if (actual == expected) return;

        Assertions.fail("""
                表 %s 期望 %d 行,实际 %d 行。
                %s"""
                .formatted(table, expected, actual, sample(connection, table)));
    }

    // ================= 模型层 =================

    static void modelExists(Connection connection, Model<?> model, boolean expected) {
        var meta = model.meta();
        long found = connection.table(meta.table())
                .where(meta.keyName(), model.keyValue())
                .count();

        if ((found > 0) == expected) return;

        Assertions.fail("期望 %s#%s %s,实际 %s".formatted(
                meta.type().getSimpleName(), model.keyValue(),
                expected ? "存在" : "不存在",
                found > 0 ? "存在" : "不存在"));
    }

    /**
     * 软删除断言。
     *
     * <p>**查的是原始行**而不是模型:软删除的模型默认被全局作用域过滤掉,
     * 用 Eloquent 查会得到「不存在」,于是断言「已软删除」永远失败 ——
     * 而失败原因与被测代码无关。
     */
    static void softDeleted(Connection connection, Model<?> model, boolean expected) {
        var meta = model.meta();
        String column = meta.softDeleteColumn();

        if (column == null) {
            Assertions.fail("""
                    %s 没有软删除列,断言「已软删除」没有意义。
                      要支持的话给模型加 @SoftDeletes,并在迁移里加
                      t.softDeletes();"""
                    .formatted(meta.type().getSimpleName()));
        }

        var row = connection.table(meta.table())
                .where(meta.keyName(), model.keyValue())
                .get().first();

        if (row.isEmpty()) {
            Assertions.fail("""
                    表 %s 里没有 %s = %s 这一行 —— 它可能被**硬删除**了。
                      软删除应该保留行并写 %s。"""
                    .formatted(meta.table(), meta.keyName(), model.keyValue(), column));
        }

        boolean deleted = row.get().get(column) != null;
        if (deleted == expected) return;

        Assertions.fail("期望 %s#%s %s,实际 %s = %s".formatted(
                meta.type().getSimpleName(), model.keyValue(),
                expected ? "已软删除" : "未软删除",
                column, row.get().get(column)));
    }

    // ================= 内部 =================

    private static long matchCount(Connection connection, String table,
                                   Map<String, ?> attributes) {
        var query = connection.table(table);
        attributes.forEach((column, value) -> {
            if (value == null) {
                query.whereNull(column);
            } else {
                query.where(column, value);
            }
        });
        return query.count();
    }

    /**
     * 表里实际有什么。
     *
     * <p>这段是失败消息里最有用的部分 —— 它直接回答了
     * 「是数据不对,还是整张表都空的」。
     */
    private static String sample(Connection connection, String table) {
        try {
            long total = connection.table(table).count();
            if (total == 0) return "  表是空的。";

            List<Map<String, Object>> rows = connection.table(table)
                    .limit(SAMPLE_ROWS).get().toList().stream()
                    .map(row -> new LinkedHashMap<>(row.toMap()))
                    .map(m -> (Map<String, Object>) m)
                    .toList();

            var sb = new StringBuilder("  表里共 %d 行,前 %d 行:%n"
                    .formatted(total, Math.min(total, SAMPLE_ROWS)));
            rows.forEach(row -> sb.append("    ").append(row).append(System.lineSeparator()));
            if (total > SAMPLE_ROWS) sb.append("    …");
            return sb.toString();
        } catch (RuntimeException e) {
            // 表不存在(迁移没跑)是个很常见的情形,这条信息比
            // 一个 SQL 异常有用得多
            return "  无法读取表内容:" + e.getMessage()
                 + System.lineSeparator()
                 + "  表不存在的话,检查迁移有没有跑(jaravel.migrate.on-boot)。";
        }
    }
}
