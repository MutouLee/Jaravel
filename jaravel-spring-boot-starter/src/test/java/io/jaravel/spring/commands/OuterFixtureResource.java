package io.jaravel.spring.commands;

import java.util.Map;

import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/**
 * 一个带 {@code with()} 根字段的测试资源 —— 索引里的 {@code O} 行
 * 模拟 APT 从 {@code return Map.of("meta", ...)} 提取出来的结果。
 */
public class OuterFixtureResource extends JsonResource<Object> {

    public OuterFixtureResource(Object model) {
        super(model);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).build();
    }
}
