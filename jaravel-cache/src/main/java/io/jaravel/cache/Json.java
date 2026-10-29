package io.jaravel.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 序列化驱动({@code file} / {@code database})共用的 ObjectMapper。
 *
 * <p>单独一个类是为了让「缓存怎么序列化」有唯一答案 —— 两个驱动各建一个
 * mapper 的话,同一个对象在 file 与 database 里会存成不同形状,
 * 而换驱动时的报错会指向取值点,离根因很远。
 */
final class Json {

    private Json() {}

    private static volatile ObjectMapper mapper;

    static ObjectMapper mapper() {
        ObjectMapper m = mapper;
        if (m == null) {
            synchronized (Json.class) {
                m = mapper;
                if (m == null) {
                    mapper = m = build();
                }
            }
        }
        return m;
    }

    /** 允许外部注入已配置好的 mapper(Spring 环境下复用应用的那一个) */
    public static void use(ObjectMapper configured) {
        mapper = configured;
    }

    private static ObjectMapper build() {
        var m = new ObjectMapper();
        m.findAndRegisterModules();   // java.time 等
        // 缓存里的旧数据可能缺少新加的字段。失败不如忽略:
        // 缓存是可重建的,为一个字段让整个读取抛异常得不偿失。
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }
}
