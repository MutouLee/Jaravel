package io.jaravel.api.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个响应状态码(docs/12 §5)。
 *
 * <p>只能作为 {@link ApiDoc#responses()} 的元素使用,不能单独标在方法上 ——
 * 所以 {@code @Target({})}。
 *
 * <h2>★ 这里没有 {@code resource} / {@code schema},是有意的</h2>
 * <p>响应 schema 要从 {@code JsonResource.toArray} 提取,而那是 APT 那一刀。
 * 先声明状态码与说明,比编一个假 schema 诚实:后者会让前端生成出
 * 字段名全错的模型类,而那种错要到联调才会发现。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ApiResponse {

    /** HTTP 状态码 */
    int code();

    /** 说明。留空时生成器按状态码给一句默认说明(201 → 创建成功) */
    String description() default "";
}
