package io.jaravel.eloquent;

/**
 * 模型未找到。由 {@code findOrFail} / {@code firstOrFail} 抛出。
 *
 * <p>starter 的 ExceptionHandler 把它映射为 **404** —— 这是 Laravel 的约定,
 * 也让控制器可以直接写 {@code Post.findOrFail(id)} 而不必手写 404 分支。
 */
public class ModelNotFoundException extends RuntimeException {

    private final Class<?> modelType;
    private final Object key;

    public ModelNotFoundException(Class<?> modelType, Object key) {
        super(buildMessage(modelType, key));
        this.modelType = modelType;
        this.key = key;
    }

    private static String buildMessage(Class<?> modelType, Object key) {
        String name = modelType == null ? "模型" : modelType.getSimpleName();
        return key == null
                ? "没有符合条件的 " + name
                : name + " " + key + " 不存在";
    }

    public Class<?> modelType() { return modelType; }
    public Object key()         { return key; }
}
