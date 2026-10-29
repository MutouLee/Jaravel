package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 软删除:{@code delete()} 只写 {@code deleted_at},查询自动排除已删行。
 *
 * <p>绕过:{@code withTrashed()} / {@code onlyTrashed()};彻底删除用 {@code forceDelete()}。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SoftDeletes {
    /** 软删除时间列名 */
    String column() default "deleted_at";
}
