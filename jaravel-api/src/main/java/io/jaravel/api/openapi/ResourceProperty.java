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
 */
public record ResourceProperty(String name, String type, boolean optional) {
}
