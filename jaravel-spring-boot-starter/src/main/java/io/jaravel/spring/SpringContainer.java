package io.jaravel.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;

import io.jaravel.core.container.Container;
import io.jaravel.support.Arr;

/**
 * {@link Container} 的 Spring 实现:**委托** {@code BeanFactory}(ADR-002)。
 *
 * <p>自研 IoC 会立刻失去 Spring 的全部 starter 生态、AOP、事务传播语义、Actuator ——
 * 是本项目性价比最低的自研项。这个类的存在只是为了让 {@code jaravel-core}
 * 及所有功能模块不 import Spring 类型(ADR-001)。
 *
 * <p>{@code override} 不走 Spring 的 bean 覆盖机制:在运行时替换单例非常别扭,
 * 自己维护一张覆盖表更干净,也让测试的 {@code Facade.swap} 有统一落点。
 */
public class SpringContainer implements Container {

    private final ConfigurableApplicationContext context;
    private final ConcurrentHashMap<String, Object> overrides = new ConcurrentHashMap<>();

    public SpringContainer(ConfigurableApplicationContext context) {
        this.context = context;
    }

    private static String key(Class<?> type, String name) {
        return name == null ? type.getName() : type.getName() + "#" + name;
    }

    @Override
    public <T> T make(Class<T> type) {
        return make(type, null);
    }

    @Override
    public <T> T make(Class<T> type, String name) {
        Object o = overrides.get(key(type, name));
        if (o != null) return type.cast(o);

        try {
            return name == null ? context.getBean(type) : context.getBean(name, type);
        } catch (NoSuchBeanDefinitionException e) {
            throw unresolved(type, name, e);
        }
    }

    @Override
    public <T> Optional<T> tryMake(Class<T> type) {
        if (overrides.containsKey(key(type, null))) return Optional.of(make(type));
        try {
            return Optional.of(context.getBean(type));
        } catch (NoSuchBeanDefinitionException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean bound(Class<?> type) {
        return overrides.containsKey(key(type, null))
            || context.getBeanNamesForType(type).length > 0;
    }

    @Override
    public <T> void bind(Class<T> type, Function<Container, T> factory) {
        // Spring 的 bean 定义在 refresh 后不宜再加,运行时注册的绑定走覆盖表
        overrides.put(key(type, null), factory.apply(this));
    }

    @Override
    public <T> void bind(Class<T> type, String name, Function<Container, T> factory) {
        overrides.put(key(type, name), factory.apply(this));
    }

    @Override
    public <T> void singleton(Class<T> type, Function<Container, T> factory) {
        T instance = factory.apply(this);
        context.getBeanFactory().registerSingleton(type.getName(), instance);
    }

    @Override
    public <T> void instance(Class<T> type, T instance) {
        context.getBeanFactory().registerSingleton(type.getName(), instance);
    }

    @Override
    public <T> void override(Class<T> type, T instance) {
        overrides.put(key(type, null), instance);
    }

    @Override
    public void clearOverrides() {
        overrides.clear();
    }

    /**
     * 「找不到 Bean」是 Spring 最让人头疼的报错之一。
     * 这里列出相似的已注册类型,把排查时间从几分钟压到几秒。
     */
    private BindingResolutionException unresolved(Class<?> type, String name,
                                                  NoSuchBeanDefinitionException cause) {
        List<String> registered = new ArrayList<>(List.of(context.getBeanDefinitionNames()));

        String hint = Arr.closest(type.getSimpleName(), registered)
                .map(c -> "\n  是否想要 " + c + " ?").orElse("");

        List<String> candidates = registered.stream()
                .filter(n -> !n.startsWith("org.springframework"))
                .sorted().limit(30).toList();

        return new BindingResolutionException(
                "容器中没有 " + type.getName() + (name == null ? "" : "(名称 " + name + ")")
                + " 的绑定。" + hint
                + "\n  修复:给实现类加 @Component / @Service,"
                + "或在 @Configuration 里提供 @Bean。"
                + "\n  已注册的应用 bean(前 30 个):" + candidates,
                cause);
    }

    public ConfigurableApplicationContext context() {
        return context;
    }
}
