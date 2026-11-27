package net.dobeen.jaravel.spring.commands;

import java.util.Map;

import net.dobeen.jaravel.api.JsonResource;
import net.dobeen.jaravel.http.Request;

/**
 * 测试用的资源 —— 它的响应形状由 {@code src/test/resources} 里的
 * {@code api-resources.idx} 提供,模拟 APT 在真实项目里的产出。
 */
public class FixtureResource extends JsonResource<Object> {

    public FixtureResource(Object model) {
        super(model);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).build();
    }
}
