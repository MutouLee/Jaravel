package io.jaravel.api.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个接口的文档信息(docs/12 §5 的「描述与示例」那一行)。
 *
 * <pre>{@code
 * @ApiDoc(summary = "创建文章", description = "需要 posts:write 权限",
 *         tags = "Posts",
 *         responses = {
 *             @ApiResponse(code = 201, description = "创建成功"),
 *             @ApiResponse(code = 422)
 *         })
 * public JsonResource store(StorePostRequest request) { ... }
 * }</pre>
 *
 * <h2>★ 为什么是运行时注解,而不是 javadoc</h2>
 * <p>{@code openapi:generate} 是一条**在线**命令,跑在应用进程里 ——
 * {@code RUNTIME} 保留策略让生成器直接反射读到这些值。javadoc 不在
 * 运行时的类信息里,要读它得再写一个注解处理器;那是另一刀。
 *
 * <h2>★ 显式 {@code responses} 会**替换**默认的 200,不是追加</h2>
 * <p>一个创建接口返回 201,自动补上的 200 是错的。所以只要写了
 * {@code responses},生成器就只写这些状态码。受保护的路由仍会自动补 401
 * (除非作者显式声明了 401,那时用作者的说明)。
 *
 * <p>⚠ 响应 **schema** 不在这里:它要从 {@code JsonResource.toArray}
 * 静态提取,属于 APT 那一刀。这里只声明状态码与说明 ——
 * 没填的字段不写进文档,也不编。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ApiDoc {

    /** 一句话说明。留空时退回路由名(如果有) */
    String summary() default "";

    /** 更长的说明,写进 OpenAPI 的 {@code description} */
    String description() default "";

    /** Swagger UI 的分组。留空时退回控制器名去掉 {@code Controller} 后缀 */
    String[] tags() default {};

    /**
     * 这个接口会返回的状态码。
     *
     * <p>留空 → 默认 200;写了 → 以这里为准(受保护的路由仍自动补 401)。
     * 同一个 code 写两次时以第一次为准。
     */
    ApiResponse[] responses() default {};
}
