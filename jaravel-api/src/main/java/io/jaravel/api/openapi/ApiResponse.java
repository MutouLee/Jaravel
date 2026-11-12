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
 * <h2>★ {@code resource} 指向的是**已经知道**的形状</h2>
 * <p>两种来源:
 * <ul>
 *   <li>{@code JsonResource} 子类 —— 字段来自 {@code jaravel-api-processor}
 *       在编译期从 {@code toArray} 写下的索引。生成器不会实例化它、
 *       也不会调用 {@code toArray};</li>
 *   <li>record —— 字段由运行时反射组件得到,不需要 APT。</li>
 * </ul>
 * 前一种情况下,没把处理器接进 {@code annotationProcessorPaths}、或者
 * 处理器提取失败({@code merge}、多个 return…)时,这个元素填了也不会
 * 出现 schema —— {@code openapi:generate} 会在输出里明确说明。
 * 宁可少一个 schema,也不编一个字段名可能错的。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ApiResponse {

    /** HTTP 状态码 */
    int code();

    /** 说明。留空时生成器按状态码给一句默认说明(201 → 创建成功) */
    String description() default "";

    /**
     * 响应体的形状。默认 {@code Void.class} 表示没有。
     *
     * <p>支持 {@code JsonResource} 子类与 record。填别的类型会被忽略
     * 并由命令报出来。
     */
    Class<?> resource() default Void.class;
}
