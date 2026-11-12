package io.jaravel.spring.commands;

import java.util.Map;

import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/**
 * 索引里指向一个**不存在**的嵌套资源 —— 用来验证「加载不到时不写假
 * {@code $ref},而是退回 object 并报出来」。
 */
public class BrokenNestedResource extends JsonResource<Object> {

    public BrokenNestedResource(Object model) {
        super(model);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).build();
    }
}
