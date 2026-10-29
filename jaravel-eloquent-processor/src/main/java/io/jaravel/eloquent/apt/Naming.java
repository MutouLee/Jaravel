package io.jaravel.eloquent.apt;

import io.jaravel.support.Str;

/**
 * 命名约定 —— 与 Laravel 保持一致,这样「不写配置也能跑」。
 *
 * <p>全部委托给 {@link Str},因为运行时的 {@code ModelRegistry} 要用**同一套**规则
 * 反推类名。两边各写一份是 bug 的温床。
 */
final class Naming {

    private Naming() {}

    /** {@code BlogPost} → {@code blog_posts} */
    static String table(String simpleName) {
        return Str.plural(Str.snake(simpleName));
    }

    /** {@code createdAt} → {@code created_at} */
    static String column(String fieldName) {
        return Str.snake(fieldName);
    }

    /** {@code User} → {@code user_id};HasMany/BelongsTo 的默认外键 */
    static String foreignKey(String ownerSimpleName) {
        return Str.snake(ownerSimpleName) + "_id";
    }

    /** {@code post} —— 多态列前缀,生成 {@code post_id}/{@code post_type} */
    static String morphPrefix(String relationName) {
        return Str.snake(relationName);
    }

    /**
     * 中间表名:两个表的单数名按**字典序**拼接。
     *
     * <p>字典序而非声明顺序:{@code Post}/{@code Tag} 两边都声明关联时,
     * 必须推出同一个 {@code post_tag},否则一边写一边读会指向两张表。
     */
    static String pivotTable(String a, String b) {
        String x = Str.snake(a);
        String y = Str.snake(b);
        return x.compareTo(y) <= 0 ? x + "_" + y : y + "_" + x;
    }

    /** 生成类名 */
    static String baseClass(String simpleName)      { return simpleName + "Base"; }
    static String metamodelClass(String simpleName) { return simpleName + "_"; }
    static String queryClass(String simpleName)     { return simpleName + "Query"; }

    /** {@code title} → {@code whereTitle} */
    static String whereHelper(String fieldName) {
        return "where" + Str.studly(fieldName);
    }
}
