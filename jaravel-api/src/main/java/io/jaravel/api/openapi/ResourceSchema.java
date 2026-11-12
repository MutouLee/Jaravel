package io.jaravel.api.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 {@code JsonResource} 的响应形状(docs/12 §5)。
 *
 * <p>由注解处理器从 {@code toArray} 的 {@code map(request).put(...)} 链里
 * 静态提取,经 {@link ResourceSchemaIndex} 在运行时读回。
 *
 * <h2>★ 提取不到的类型不写,而不是猜</h2>
 * <p>{@link ResourceProperty#type()} 为 null 时,生成的 schema 只有字段名,
 * 没有 {@code type}。那表示「这个字段存在,但静态分析不知道它是什么」——
 * 比写一个 string 诚实,也不会让前端生成出错误的类型。
 *
 * <p>同理,{@code optional} 为 true 的字段不进 {@code required}:
 * 条件字段真的可能不出现。把不确定的字段标成必填,
 * 会让客户端按「它一定在」写代码,然后在某个分支上读到 null。
 *
 * @param className       资源类的二进制名
 * @param properties      {@code toArray} 的字段
 * @param outerProperties {@code with(request)} 加在**根位置**的字段
 *                        (比如 {@code meta});嵌套使用时不出现
 */
public record ResourceSchema(String className, List<ResourceProperty> properties,
                             List<ResourceProperty> outerProperties) {

    public ResourceSchema {
        properties = List.copyOf(properties);
        outerProperties = List.copyOf(outerProperties);
    }

    public ResourceSchema(String className, List<ResourceProperty> properties) {
        this(className, properties, List.of());
    }

    /**
     * 把嵌套资源的类名解析成真正的 schema。
     *
     * <p>为什么不在这个模块里直接写 {@code $ref}:component 名由
     * 生成器按「简单名 / 撞车时限定名」分配,而那是运行期才定下来的。
     * 这里只给出资源类名,由调用方决定它叫 {@code AuthorResource}
     * 还是 {@code app.http.resources.AuthorResource}。
     */
    @FunctionalInterface
    public interface RefSchema {

        /**
         * @param className 资源类的二进制名
         * @param array     true 表示这个字段是这些资源的数组
         * @return 完整的字段 schema;返回 null 表示解析不了,
         *         调用方会退回按 {@link ResourceProperty#type()} 写
         */
        Map<String, Object> schemaFor(String className, boolean array);
    }

    /** 一个字段的 OpenAPI schema(ref 交给 {@code refs},其余按 type 写) */
    public static Map<String, Object> openApiSchemaOf(ResourceProperty property, RefSchema refs) {
        Map<String, Object> schema = null;

        if (property.ref() != null) {
            schema = refs.schemaFor(property.ref(), "array".equals(property.type()));
        }

        if (schema == null) {
            var fallback = new LinkedHashMap<String, Object>();
            if (property.type() != null && !property.type().isBlank()) {
                fallback.put("type", property.type());
            }
            schema = fallback;
        }
        return schema;
    }

    /** 不解析嵌套资源 —— 有 {@code ref} 的字段退化成 object/array */
    public Map<String, Object> toOpenApiSchema() {
        return toOpenApiSchema((className, array) -> null);
    }

    /** 资源本体的 schema(不含 {@code with} 的根字段) */
    public Map<String, Object> toOpenApiSchema(RefSchema refs) {
        var propertySchemas = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        for (ResourceProperty property : properties) {
            propertySchemas.put(property.name(), openApiSchemaOf(property, refs));
            if (!property.optional()) required.add(property.name());
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", propertySchemas);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
