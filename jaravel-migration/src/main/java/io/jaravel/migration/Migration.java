package io.jaravel.migration;

/**
 * 一个迁移。
 *
 * <pre>{@code
 * // database/migrations/m2026_10_29_143500/CreatePostsTable.java
 * public class CreatePostsTable implements Migration {
 *     public void up() {
 *         Schema.create("posts", t -> {
 *             t.id();
 *             t.string("title", 200);
 *             t.timestamps();
 *         });
 *     }
 *     public void down() { Schema.dropIfExists("posts"); }
 * }
 * }</pre>
 *
 * <h2>为什么时间戳在包名上</h2>
 * <p>Laravel 的文件名是 {@code 2026_10_29_143500_create_posts_table.php}。
 * Java 要求 public 类名与文件名一致,而 {@code 2026_10_29_...} 不是合法类名
 * (不能以数字开头)。两个选择:
 * <ul>
 *   <li>类名带时间戳:{@code M2026_10_29_143500_CreatePostsTable} —— 又长又难读;</li>
 *   <li><b>时间戳放包路径</b>:{@code database.migrations.m2026_10_29_143500.CreatePostsTable}
 *       —— 类名干净,排序信息仍在,IDE 里按包折叠也清楚。</li>
 * </ul>
 * 选了后者。这是 Java 的文件名约束逼出来的偏离,不是设计偏好。
 */
public interface Migration {

    /** 向前迁移 */
    void up();

    /**
     * 回滚。
     *
     * <p>默认抛异常而不是空实现:一个「什么都不做」的 down() 会让
     * {@code migrate:rollback} 静默成功、数据库却没变 —— 比直接报错危险得多。
     * 确实不可回滚(如删列后数据已丢)的迁移应显式声明 {@link #irreversible()}。
     */
    default void down() {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " 没有实现 down(),无法回滚。"
              + "\n  要么实现 down(),要么覆盖 irreversible() 返回 true 以明确声明"
              + "「这个迁移不可回滚」—— 后者会让 rollback 以清晰的错误停下,"
              + "而不是假装成功。");
    }

    /** 明确声明不可回滚。{@code migrate:rollback} 遇到它会停下并说明。 */
    default boolean irreversible() {
        return false;
    }

    /**
     * 是否在事务里执行。
     *
     * <p>默认 {@code true},但有些 DDL 在 MySQL 里是隐式提交的
     * (CREATE TABLE 等),包事务没有意义也不会出错。真正需要关掉的是
     * PostgreSQL 的 {@code CREATE INDEX CONCURRENTLY} —— 它**不能**在事务内运行。
     */
    default boolean withinTransaction() {
        return true;
    }

    /** 只在这些方言上执行;空表示全部。 */
    default java.util.List<String> onlyOnDialects() {
        return java.util.List.of();
    }
}
