package io.jaravel.core.container;

import java.util.Optional;
import java.util.function.Function;

/**
 * 服务容器契约。
 *
 * <p>ADR-002:不自研 IoC。Spring 实现(在 starter 里)直接委托 {@code BeanFactory} ——
 * 自研容器会立刻失去 Spring 的全部 starter 生态、AOP、事务传播语义、Actuator。
 *
 * <p>本接口存在的理由不是「替代 Spring」,而是让 {@code jaravel-core} 及所有功能模块
 * **不 import Spring 类型**(ADR-001),从而把 Spring 的版本漂移收窄到 starter 一个模块。
 */
public interface Container {

    <T> T make(Class<T> type);

    <T> T make(Class<T> type, String name);

    <T> Optional<T> tryMake(Class<T> type);

    boolean bound(Class<?> type);

    <T> void bind(Class<T> type, Function<Container, T> factory);

    <T> void bind(Class<T> type, String name, Function<Container, T> factory);

    <T> void singleton(Class<T> type, Function<Container, T> factory);

    <T> void instance(Class<T> type, T instance);

    /**
     * 测试期替换绑定。与 {@code Facade.swap} 配合,
     * 不走 Spring 的 bean 覆盖机制 —— 后者在运行时替换单例非常别扭。
     */
    <T> void override(Class<T> type, T instance);

    void clearOverrides();

    /** 解析失败时抛出,消息中列出相似的已注册类型 */
    class BindingResolutionException extends RuntimeException {
        public BindingResolutionException(String message) { super(message); }
        public BindingResolutionException(String message, Throwable cause) { super(message, cause); }
    }
}
