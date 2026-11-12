package io.jaravel.spring.commands;

import java.util.Map;

import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/**
 * 一个包含嵌套资源与自引用的测试资源 —— 形状由测试资源里的
 * {@code api-resources.idx} 提供。
 */
public class NestedFixtureResource extends JsonResource<Object> {

    public NestedFixtureResource(Object model) {
        super(model);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).build();
    }
}
