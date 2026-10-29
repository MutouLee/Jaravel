package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Eloquent 模型。APT 据此生成 {@code <Name>Base}、{@code <Name>_}、
 * {@code <Name>Query} 三个产物。
 *
 * <pre>{@code
 * @Model(table = "posts")
 * @SoftDeletes
 * @HasMany(name = "comments", target = Comment.class)
 * public class Post extends PostBase {
 *     @Id public Long id;
 *     public String title;
 *     public String body;
 * }
 * }</pre>
 *
 * <p>标量列是 <b>public 字段</b>(ADR-010 方案 E'):读写就是 {@code post.title}
 * 与 {@code post.title = "x"},与 PHP 的 {@code $post->title} 等长。
 * 脏跟踪靠快照比对,不需要拦截写入。
 *
 * <p>关联用<b>类级注解</b>声明而非字段 —— V1 验证发现 public 字段既无法拦截懒加载,
 * 又会被用户的显式初始化器静默覆盖(父类构造器初始化子类字段的陷阱)。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Model {

    /** 表名。留空则按约定推导:类名 snake_case 复数({@code Post} → {@code posts})。 */
    String table() default "";

    /** 连接名。留空用默认连接。 */
    String connection() default "";

    /** 是否自动维护 {@code created_at} / {@code updated_at} */
    boolean timestamps() default true;

    /**
     * 生成类型化的便捷查询方法({@code whereTitle} / {@code whereViewsGt} …)。
     *
     * <p>字段多的模型会生成上百个方法,IDE 自动完成列表可能过长 ——
     * 关掉后只保留元模型({@code Post_.title}),用通用的
     * {@code where(Post_.title, v)} 形式。
     */
    boolean generateHelpers() default true;

    /**
     * 身份映射:同一次执行内,同一主键的同一模型只实例化一次。
     *
     * <p>好处是预加载回填后 {@code post.author()} 与外部拿到的 {@code user}
     * 是同一对象,且避免同一行被查多次。
     */
    boolean identityMap() default true;

    /**
     * 保存时与原始快照做<b>全字段</b>比较。
     *
     * <p>默认 true(方案 E' 的基础)。置 false 则只写回通过生成的 setter
     * 修改过的字段 —— 需要配合 {@code accessors = true}。
     */
    boolean fullDirtyCheck() default true;

    /**
     * 额外生成 getter / setter。
     *
     * <p>默认不生成:public 字段已经够用,且信息密度更高。
     * 有硬性编码规范(禁止 public 字段)的团队可以打开 —— 这是方案 E' 的逃生口。
     */
    boolean accessors() default false;
}
