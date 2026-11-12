package io.jaravel.api.openapi;

/**
 * 响应里的一个字段(docs/12 §5)。
 *
 * @param name     字段名
 * @param type     OpenAPI 的 {@code type};null 表示**不知道** ——
 *                 不知道就不写,而不是猜一个 string 出来
 * @param optional 是否一定存在。{@code put} 是无条件的;
 *                 {@code putWhen} / {@code putWhenLoaded*} / {@code putIfAbsent}
 *                 都可能缺席
 * @param ref      嵌套资源类的二进制名;null 表示这个字段不是另一个
 *                 {@code JsonResource}。{@code type=object} 时是单个,
 *                 {@code type=array} 时是数组元素 —— 生成器会把它解析成
 *                 {@code $ref}
 *
 * <p>⚠ {@code optional=false} 只表示 {@code toArray} 一定会 put 这个键。
 * 应用若把 Jackson 配成「不输出 null 字段」,值为 null 时那个键仍然会从
 * 响应里消失 —— 这一点还没有在 schema 里表达。要精确的话得按值类型
 * (原始类型 / 非 null 字面量)而不是按构建器方法判,那是后续的事。
 */
public record ResourceProperty(String name, String type, boolean optional, String ref) {

    public ResourceProperty(String name, String type, boolean optional) {
        this(name, type, optional, null);
    }
}
