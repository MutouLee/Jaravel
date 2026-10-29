package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 批量赋值白名单。与 {@link Guarded} 二选一 —— 同时标注会编译报错,
 * 因为两者的语义冲突且优先级不直观。
 *
 * <p>白名单比黑名单安全:新增字段时默认不可批量赋值。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Fillable {
    String[] value();
}
