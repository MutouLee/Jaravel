package io.jaravel.eloquent.cast;

import java.util.Map;

import io.jaravel.eloquent.meta.Cast;

/**
 * {@code Map<String,Object>} ↔ JSON 文本。
 *
 * <p>Jackson 是 optional 依赖 —— 不用 JSON 列就不需要它。因此这里延迟到
 * 首次使用才碰 Jackson,并在缺失时给出可操作的报错而非 NoClassDefFoundError。
 */
public class Json implements Cast<Map<String, Object>, String> {

    private static Object mapper;

    private static Object mapper() {
        if (mapper == null) {
            try {
                mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException(
                        "@Cast(Json.class) 需要 Jackson,但 classpath 上没有。"
                      + "\n  修复:加上依赖 com.fasterxml.jackson.core:jackson-databind"
                      + "\n        (jaravel-spring-boot-starter 已经带了它,"
                      + "独立使用 jaravel-eloquent 时需显式添加)", e);
            }
        }
        return mapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> fromDatabase(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var m = mapper();
            var readValue = m.getClass().getMethod("readValue", String.class, Class.class);
            return (Map<String, Object>) readValue.invoke(m, raw, Map.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "JSON 列的内容无法解析为 Map:" + preview(raw)
                  + "\n  原因:" + rootMessage(e), e);
        }
    }

    @Override
    public String toDatabase(Map<String, Object> value) {
        if (value == null) return null;
        try {
            var m = mapper();
            var writeValueAsString = m.getClass().getMethod("writeValueAsString", Object.class);
            return (String) writeValueAsString.invoke(m, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "无法把该值序列化为 JSON:" + rootMessage(e), e);
        }
    }

    private static String preview(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage();
    }
}
