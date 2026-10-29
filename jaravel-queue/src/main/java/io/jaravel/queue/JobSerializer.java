package io.jaravel.queue;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * 任务 ↔ JSON 信封。
 *
 * <h2>字段怎么被看见</h2>
 * <p>任务的字段通常是 {@code private final},而且没有 getter ——
 * 那是对的写法(任务是一个带参数的动作,不是一个数据对象)。
 * 所以这里显式打开**字段可见性**,不走 getter。
 *
 * <h2>反序列化怎么还原</h2>
 * <p>靠 {@code ParameterNamesModule} + 编译时的 {@code -parameters}:
 * Jackson 用构造函数的参数名对上 JSON 的键。
 *
 * <p>{@code -parameters} 是 Jaravel **已有的**要求 ——
 * 路由的参数绑定也依赖它({@code ArgumentBinder} 用
 * {@code Parameter.isNamePresent()})。所以这不是队列新增的约束。
 * 但没开时的报错必须说清楚,否则「任务入队成功、取出来就炸」
 * 会被归因到队列实现上。
 *
 * <h2>序列化不了就在**投递时**失败</h2>
 * <p>而不是等 worker 取出来才发现。投递时失败的话,调用栈就在
 * 写出这个 bug 的那行代码上;worker 侧失败只能看到一个
 * 「某个类反序列化不了」,离原因很远。
 */
public final class JobSerializer {

    private final ObjectMapper mapper;

    public JobSerializer() {
        this(defaultMapper());
    }

    public JobSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 队列专用的 mapper。
     *
     * <p>不复用应用的那个:应用的 ObjectMapper 常被定制成
     * 「忽略未知字段、蛇形命名、日期格式化成字符串」之类 ——
     * 那些都是为 HTTP API 服务的,而队列 payload 要的是**可往返**。
     * 共用一个会让「改了 API 的 JSON 配置,队列里积压的任务全反序列化失败」。
     */
    private static ObjectMapper defaultMapper() {
        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());

        // 任务字段是 private final 且没有 getter —— 直接看字段
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));

        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 未知字段**报错**而不是忽略。
        //
        // 忽略看起来更宽容,实际很糟:删掉一个任务字段之后,队列里
        // 积压的旧任务会被静默地用默认值(null / 0)执行 ——
        // 一个 amount 字段没了就变成了「转账 0 元」。
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    // ================= 序列化 =================

    /**
     * 任务字段 → Map。
     *
     * <p>**没有字段的任务特殊处理。**
     * {@code class CleanupTempFiles implements ShouldQueue} 是完全合理
     * 且常见的 —— 一个不带参数的动作。而 Jackson 把「没有任何属性的 bean」
     * 当成错误({@code FAIL_ON_EMPTY_BEANS}),报的是
     * 「No serializer found」,与真正的原因毫无关系。
     *
     * <p>这里**不**关掉那个开关,而是直接短路:关掉它的副作用是
     * 「字段是个不可序列化的对象」会静默变成 {@code {}} ——
     * 一个带着空参数执行的任务,比投递时报错糟得多。
     * 精确处理无字段的情形,保留对坏字段的检测。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toData(ShouldQueue job) {
        if (!hasSerializableFields(job.getClass())) return Map.of();

        try {
            return mapper.convertValue(job, Map.class);
        } catch (RuntimeException e) {
            throw new UnserializableJobException("""
                    任务 %s 无法序列化成 JSON:%s
                      payload 必须是 JSON 可表示的。常见原因:
                        · 字段是 Eloquent 模型或其它实体 —— 改成只存主键,
                          在 handle() 里重新查询(见 ShouldQueue 的类注释);
                        · 字段是接口、Lambda、输入流之类不可序列化的东西 ——
                          那些应该从容器注入到 handle(...) 的参数里;
                        · 字段是自定义类型但没有可序列化的结构。
                      这里在**投递时**就失败,而不是等 worker 取出来 ——
                      那时调用栈离原因很远。"""
                    .formatted(job.getClass().getName(), e.getMessage()), e);
        }
    }

    /**
     * 这个类(含父类)有没有需要序列化的实例字段。
     *
     * <p>跳过 {@code static} 与 {@code synthetic}(内部类持有的
     * {@code this$0} 就是 synthetic —— 把它算进来会让所有非静态内部类
     * 的任务都被判定为「有字段」,然后在序列化外层实例时炸)。
     */
    private static boolean hasSerializableFields(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;
                return true;
            }
        }
        return false;
    }

    public String encode(QueuedJob envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new UnserializableJobException(
                    "无法序列化任务信封:" + e.getMessage(), e);
        }
    }

    public QueuedJob decode(String json) {
        try {
            return mapper.readValue(json, QueuedJob.class);
        } catch (Exception e) {
            throw new UnserializableJobException("""
                    无法解析任务信封:%s
                      payload 可能来自另一个版本的 Jaravel,或被手工改过。
                      这条任务不会被执行 —— 用 queue:forget 删掉它。"""
                    .formatted(e.getMessage()), e);
        }
    }

    // ================= 反序列化 =================

    /** Map → 任务实例 */
    public ShouldQueue toJob(QueuedJob envelope) {
        Class<?> type = loadClass(envelope.jobClass());

        if (!ShouldQueue.class.isAssignableFrom(type)) {
            throw new UnserializableJobException("""
                    %s 不是 ShouldQueue 的实现,无法作为任务执行。
                      payload 里的 jobClass 可能是手工改的。"""
                    .formatted(envelope.jobClass()), null);
        }

        try {
            return (ShouldQueue) mapper.convertValue(
                    new LinkedHashMap<>(envelope.data()), type);
        } catch (RuntimeException e) {
            throw new UnserializableJobException("""
                    无法把 payload 还原成 %s:%s
                      payload:%s
                      最常见的两个原因:
                        1. 编译时没开 -parameters。Jackson 靠构造函数的**参数名**
                           把 JSON 的键对上构造参数,没有参数名就对不上。
                           在 pom 的 maven-compiler-plugin 里加
                             <compilerArgs><arg>-parameters</arg></compilerArgs>
                           (Jaravel 的路由参数绑定也依赖这个,不是队列新增的要求);
                        2. 任务的字段改过,而队列里还有旧 payload。
                           加字段是安全的(旧 payload 缺它会报错,说明需要兼容处理);
                           删字段会让旧 payload 报「未知字段」—— 这是刻意的,
                           静默忽略会让旧任务带着 null 执行。"""
                    .formatted(envelope.jobClass(), e.getMessage(), envelope.data()), e);
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, true,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new UnserializableJobException("""
                    找不到任务类 %s。
                      可能是:任务类被删了或改名了,而队列里还有它的任务。
                      处理方式:queue:retry 之前先把类补回来,或 queue:forget 丢弃。"""
                    .formatted(name), e);
        }
    }

    /**
     * 把一个 JSON 往返后的 map 转回具体类型。
     *
     * <p>用于信封上下文({@link JobContext}):{@code ChainFailure} 经过
     * JSON 往返之后是一个 Map,手工取字段的版本会在那个 record
     * 加字段时**静默少一项**,而少掉的那一项通常正是新加的诊断信息。
     */
    public <T> T convert(Object value, Class<T> type) {
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            throw new UnserializableJobException("""
                    无法把信封上下文还原成 %s:%s
                      上下文:%s
                      它可能来自另一个版本的 Jaravel。"""
                    .formatted(type.getSimpleName(), e.getMessage(), value), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** 序列化/反序列化失败。**不可重试** —— 再试一百次结果一样。 */
    public static class UnserializableJobException extends RuntimeException {
        public UnserializableJobException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
