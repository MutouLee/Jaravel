package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 全局查询作用域:该模型的<b>每次</b>查询都自动套上。
 *
 * <p>典型用途是多租户隔离:
 * <pre>{@code
 * @GlobalScope public static Query<Post> tenant(Query<Post> q) {
 *     return Tenancy.current().map(t -> q.where(Post_.tenantId, t.id())).orElse(q);
 * }
 * }</pre>
 *
 * <p>绕过:{@code withoutGlobalScope("tenant")} / {@code withoutGlobalScopes()}。
 * 签名要求同 {@link Scope},但不接受额外参数。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface GlobalScope {
    /** 作用域名,用于 withoutGlobalScope("..")。留空则用方法名。 */
    String value() default "";
}
