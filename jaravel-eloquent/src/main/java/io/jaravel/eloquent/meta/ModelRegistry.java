package io.jaravel.eloquent.meta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.support.Arr;

/**
 * 模型类型 → {@link ModelMeta} 的注册表。关联解析需要「给我 Post 的元信息」。
 *
 * <p>两条填充路径:
 * <ol>
 *   <li><b>主路径</b>:APT 生成 {@code META-INF/jaravel/models.idx},
 *       starter 启动时读取并触发各 {@code <Name>_} 的静态初始化 ——
 *       零类路径扫描;
 *   <li><b>回落</b>:按约定 {@code Class.forName(name + "_")} 惰性解析。
 *       单元测试与脱离容器的脚本靠这条路跑,无需任何启动装配。
 * </ol>
 *
 * <p>回落只在**首次**解析某类型时反射一次,结果缓存。
 */
public final class ModelRegistry {

    private ModelRegistry() {}

    private static final Map<Class<?>, ModelMeta<?>> METAS = new ConcurrentHashMap<>();
    /** 已尝试过回落解析但失败的类型,避免反复反射 */
    private static final Map<Class<?>, Boolean> FAILED = new ConcurrentHashMap<>();

    /** 由生成的 {@code <Name>_} 静态初始化块调用 */
    public static <M> void register(ModelMeta<M> meta) {
        METAS.put(meta.type(), meta);
        FAILED.remove(meta.type());
    }

    @SuppressWarnings("unchecked")
    public static <M> ModelMeta<M> of(Class<M> modelType) {
        ModelMeta<?> meta = METAS.get(modelType);
        if (meta != null) return (ModelMeta<M>) meta;

        meta = resolveByConvention(modelType);
        if (meta != null) return (ModelMeta<M>) meta;

        throw new UnknownModelException(buildUnknownMessage(modelType));
    }

    @SuppressWarnings("unchecked")
    public static <M> Optional<ModelMeta<M>> tryOf(Class<M> modelType) {
        try {
            return Optional.of(of(modelType));
        } catch (UnknownModelException e) {
            return Optional.empty();
        }
    }

    /**
     * 回落:从生成的 {@code <Name>_} 上直接读 {@code META} 字段。
     *
     * <p>**不靠静态初始化的副作用**。早先的实现是 {@code Class.forName(name + "_")},
     * 指望静态块里的 {@code register()} 把 meta 填进来。那条路有个隐蔽的坑:
     * JVM 对每个类只跑一次静态初始化,所以一旦 {@link #clear()} 被调用过
     * (测试之间清状态是很自然的事),再 {@code forName} 不会重新注册,
     * 注册表就**永久空了**。直接读字段是幂等的,与初始化顺序无关。
     *
     * <p>静态块里的 {@code register()} 仍然保留 —— 它让常规路径一次反射都不用。
     */
    private static ModelMeta<?> resolveByConvention(Class<?> modelType) {
        if (FAILED.containsKey(modelType)) return null;
        try {
            Class<?> metamodel = Class.forName(
                    modelType.getName() + "_", true, modelType.getClassLoader());
            Object meta = metamodel.getField("META").get(null);
            if (meta instanceof ModelMeta<?> m) {
                METAS.putIfAbsent(modelType, m);
                return m;
            }
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException
                 | LinkageError ignored) {
            // 不是模型,或 APT 没跑
        }
        FAILED.put(modelType, Boolean.TRUE);
        return null;
    }

    private static String buildUnknownMessage(Class<?> modelType) {
        List<String> known = METAS.keySet().stream().map(Class::getSimpleName).sorted().toList();
        String hint = Arr.closest(modelType.getSimpleName(), known)
                .map(c -> "\n  是否想用 " + c + " ?").orElse("");

        return modelType.getName() + " 不是已注册的 Eloquent 模型。" + hint
             + "\n  已注册:" + (known.isEmpty() ? "(无)" : known)
             + "\n  常见原因:"
             + "\n    · 类上少了 @Model 注解;"
             + "\n    · jaravel-eloquent-processor 没在 classpath 上"
             + "      (pom 里需要它,scope=provided);"
             + "\n    · 增量编译没重跑 APT —— 试试 mvn clean compile。";
    }

    public static boolean isRegistered(Class<?> modelType) {
        return METAS.containsKey(modelType);
    }

    public static List<Class<?>> registered() {
        return METAS.keySet().stream().sorted(java.util.Comparator.comparing(Class::getName)).toList();
    }

    /** 测试用 */
    public static void clear() {
        METAS.clear();
        FAILED.clear();
    }

    public static class UnknownModelException extends IllegalArgumentException {
        public UnknownModelException(String message) { super(message); }
    }
}
