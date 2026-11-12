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
 */
public record ResourceSchema(String className, List<ResourceProperty> properties) {

    public ResourceSchema {
        properties = List.copyOf(properties);
    }

    /** 产出 OpenAPI 的 Schema Object(还没有 {@code $ref} 包裹) */
    public Map<String, Object> toOpenApiSchema() {
        var propertySchemas = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        for (ResourceProperty property : properties) {
            var schema = new LinkedHashMap<String, Object>();
            if (property.type() != null && !property.type().isBlank()) {
                schema.put("type", property.type());
            }
            propertySchemas.put(property.name(), schema);
            if (!property.optional()) required.add(property.name());
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", propertySchemas);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
