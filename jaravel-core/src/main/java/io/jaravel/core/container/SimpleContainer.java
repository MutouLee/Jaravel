package io.jaravel.core.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.jaravel.support.Arr;

/**
 * 不依赖 Spring 的容器实现。
 *
 * <p>用途有二:
 * <ol>
 *   <li>单元测试与 {@code jaravel-core} 自身的测试 —— 不必启动 Spring;
 *   <li>将来的 {@code jaravel-standalone}(纯 Netty 运行,无 Spring)留口子。
 * </ol>
 *
 * <p>生产路径走 starter 里的 {@code SpringContainer}(委托 {@code BeanFactory})。
 * 本实现**不做**构造注入的依赖图解析 —— 那是 Spring 的活。
 */
public class SimpleContainer implements Container {

    private record Binding<T>(Function<Container, T> factory, boolean shared) {}

    private final Map<String, Binding<?>> bindings = new ConcurrentHashMap<>();
    private final Map<String, Object> resolved = new ConcurrentHashMap<>();
    private final Map<String, Object> overrides = new ConcurrentHashMap<>();

    private static String key(Class<?> type, String name) {
        return name == null ? type.getName() : type.getName() + "#" + name;
    }

    @Override
    public <T> T make(Class<T> type) {
        return make(type, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T make(Class<T> type, String name) {
        String k = key(type, name);

        Object o = overrides.get(k);
        if (o != null) return type.cast(o);

        Object r = resolved.get(k);
        if (r != null) return type.cast(r);

        Binding<?> b = bindings.get(k);
        if (b == null) throw unresolved(type, name);

        Object value = b.factory().apply(this);
        if (b.shared()) resolved.put(k, value);
        return (T) value;
    }

    @Override
    public <T> Optional<T> tryMake(Class<T> type) {
        return bound(type) ? Optional.of(make(type)) : Optional.empty();
    }

    @Override
    public boolean bound(Class<?> type) {
        String k = key(type, null);
        return bindings.containsKey(k) || resolved.containsKey(k) || overrides.containsKey(k);
    }

    @Override
    public <T> void bind(Class<T> type, Function<Container, T> factory) {
        bindings.put(key(type, null), new Binding<>(factory, false));
    }

    @Override
    public <T> void bind(Class<T> type, String name, Function<Container, T> factory) {
        bindings.put(key(type, name), new Binding<>(factory, false));
    }

    @Override
    public <T> void singleton(Class<T> type, Function<Container, T> factory) {
        bindings.put(key(type, null), new Binding<>(factory, true));
    }

    @Override
    public <T> void instance(Class<T> type, T instance) {
        resolved.put(key(type, null), instance);
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
     * 解析失败时列出已注册的相似类型。
     * 「找不到 Bean」是 Spring 最让人头疼的报错之一,这里尽量给出可操作的线索。
     */
    private BindingResolutionException unresolved(Class<?> type, String name) {
        List<String> registered = new ArrayList<>();
        bindings.keySet().forEach(registered::add);
        resolved.keySet().forEach(registered::add);

        String hint = Arr.closest(type.getName(), registered)
                .map(c -> "\n  是否想要 " + c + " ?")
                .orElse("");

        List<String> simple = registered.stream()
                .map(s -> s.substring(s.lastIndexOf('.') + 1))
                .sorted().distinct().limit(20).toList();

        return new BindingResolutionException(
                "容器中没有 " + type.getName() + (name == null ? "" : "(名称 " + name + ")")
                + " 的绑定。" + hint
                + "\n  已注册的类型:" + simple
                + "\n  修复:在 ServiceProvider#register 中 bind,或给实现类加 @Component");
    }

    /** 调试用:当前全部绑定 */
    public Map<String, String> dump() {
        Map<String, String> out = new LinkedHashMap<>();
        bindings.forEach((k, v) -> out.put(k, v.shared() ? "singleton" : "factory"));
        resolved.forEach((k, v) -> out.put(k, "instance"));
        overrides.forEach((k, v) -> out.put(k, "override(test)"));
        return out;
    }
}
