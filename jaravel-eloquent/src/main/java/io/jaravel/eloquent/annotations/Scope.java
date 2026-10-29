package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 局部查询作用域。APT 在生成的 {@code <Name>Query} 上加同名方法,可链式调用。
 *
 * <pre>{@code
 * @Scope public static Query<Post> published(Query<Post> q) {
 *     return q.whereNotNull(Post_.publishedAt);
 * }
 * // 使用:Post.query().published().get()
 *
 * @Scope public static Query<Post> olderThan(Query<Post> q, int days) {
 *     return q.where(Post_.createdAt, Op.LT, Instant.now().minus(days, DAYS));
 * }
 * // 使用:Post.query().olderThan(7).get()
 * }</pre>
 *
 * <p>方法必须是 {@code public static},第一个参数是 {@code Query<自身类型>},
 * 返回同类型。不符合则编译报错并指出正确签名。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Scope {
}
