package io.jaravel.spring.commands;

import java.util.Map;

import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/** 一个**没有**索引条目的资源 —— 用来验证「缺 schema 时要报出来」。 */
public class UnindexedResource extends JsonResource<Object> {

    public UnindexedResource(Object model) {
        super(model);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).build();
    }
}
