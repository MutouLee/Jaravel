package net.dobeen.jaravel.broadcasting;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 广播 payload 的序列化(docs/19 §2.1 的「全字段」默认值)。
 *
 * <p>与 {@code jaravel-queue} 的 JobSerializer 同一套可见性配置:
 * 直接看字段(事件是 record,字段是 private final 且**没有** getter)。
 * 一个字段是 Eloquent 模型时,序列化会把它摊成模型的字段 ——
 * 那是「payload 里放了模型」的错误姿势,报错与修复指引见
 * {@link #toMap}。
 */
public final class PayloadJson {

    private final ObjectMapper mapper;

    public PayloadJson() {
        this(buildMapper());
    }

    public PayloadJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 广播专用的 mapper,不复用应用的:见 JobSerializer 的同一理由 */
    private static ObjectMapper buildMapper() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));

        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 广播只序列化不反序列化 —— 未知字段开关无关紧要,
        // 但关掉它能让「自定义类型被摊平」这类问题在序列化时就响
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * 把事件摊成 map —— {@code ShouldBroadcast.broadcastWith()} 返回
     * {@code null} 时提取全部字段。
     *
     * <p>序列化不了时**在这里**就抛 —— 调用栈在 {@code Event.dispatch}
     * 那一行,而不是某个 WS 连接的发送线程里。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object value) {
        try {
            return mapper.convertValue(value, Map.class);
        } catch (RuntimeException e) {
            throw new UnserializablePayloadException("""
                    事件 %s 无法序列化成 JSON:%s
                      payload 必须是 JSON 可表示的。常见原因:
                        · 字段是 Eloquent 模型或其它实体 —— 改成只存主键,
                          在 broadcastWith() 里手工挑字段(见 ShouldBroadcast);
                        · 字段是 Lambda、输入流之类不可序列化的东西。
                      这里在**分发时**就失败,而不是等某个连接发送时 ——
                      那时调用栈离原因很远。"""
                    .formatted(value.getClass().getName(), e.getMessage()), e);
        }
    }

    /** Map → JSON 字符串(驱动写帧用) */
    public String encode(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (Exception e) {
            throw new UnserializablePayloadException(
                    "payload 无法序列化成 JSON:" + e.getMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** 序列化失败。**不可重试** —— 再试一百次结果一样 */
    public static class UnserializablePayloadException extends RuntimeException {
        public UnserializablePayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
