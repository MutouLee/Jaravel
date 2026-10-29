package io.jaravel.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 会话序列化共用的 mapper */
final class Json {

    private Json() {}

    private static volatile ObjectMapper mapper = build();

    static ObjectMapper mapper() {
        return mapper;
    }

    public static void use(ObjectMapper configured) {
        mapper = configured;
    }

    static <T> T convert(Object value, Class<T> type) {
        try {
            return mapper.convertValue(value, type);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "会话里的值无法转成 " + type.getName() + ":" + e.getMessage()
                  + "\n  cookie / cache 驱动存的是 JSON,读回来是 Map。"
                  + "\n  目标类型需要有无参构造与可写属性,或改用基本类型。", e);
        }
    }

    private static ObjectMapper build() {
        var m = new ObjectMapper();
        m.findAndRegisterModules();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }
}
