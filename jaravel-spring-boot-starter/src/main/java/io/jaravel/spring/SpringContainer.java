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
            if (name == null) {
                var constructed = tryConstruct(type);
                if (constructed.isPresent()) return constructed.get();
            }
            throw unresolved(type, name, e);
        }
    }

    /**
     * 没有 bean 定义时,用构造注入**现场造一个**。
     *
     * <p>这条回落是中间件能工作的前提:路由上写
     * {@code middleware("auth")} 时,{@code Authenticate} 需要一个
     * {@code AuthManager}。要求用户为框架自带的每个中间件都声明一个
     * {@code @Bean} 是不可接受的 —— 那就等于把「加个登录 = 3 行」
     * 变成「3 行 + 8 个 @Bean」。
     *
     * <p><b>每次返回新实例,这一点是必需的。</b>
     * {@code Middleware.Parameterized} 的 {@code configure(String...)}
     * 把参数写进实例字段({@code throttle:60,1} 的 60),注册成单例的话
     * 两条路由会互相改对方的限流阈值 —— 而那是个只在并发下偶发的
     * 配置错乱,几乎无法从现象定位。
     *
     * <p>{@code createBean} 不把实例登记进容器,所以它也不会被
     * 别处按类型注入到。
     *
     * @return 构造不出来时为空 —— 此时让调用方抛那条带候选列表的错误,
     *         它比 BeanInstantiationException 有用得多
     */
    private <T> Optional<T> tryConstruct(Class<T> type) {
        if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            return Optional.empty();
        }
        try {
            return Optional.of(context.getBeanFactory().createBean(type));
        } catch (org.springframework.beans.BeansException e) {
            // createBean 在**多个 public 构造函数**面前会放弃 ——
            // 它要求有 @Autowired 标注或只有一个候选。而 Jaravel 的
            // 中间件不能 import Spring 注解(ADR-001),所以这里自己选。
            return constructGreedily(type);
        }
    }

    /**
     * 选参数最多、且每个参数都能从容器解析出来的那个构造函数。
     *
     * <p>这是 Laravel 容器的规则,照用的理由是它对「可选依赖」的处理
     * 恰好正确:{@code RedirectIfAuthenticated(AuthManager)} 与
     * {@code RedirectIfAuthenticated(AuthManager, String redirectTo)}
     * 并存时,{@code String} 解析不出来 → 退到单参版本。
     *
     * <p>反过来的规则(参数最少优先)会在「有无参构造 + 有注入构造」时
     * 选错 —— 那个类会拿到一个所有依赖都是 null 的实例,然后在
     * 第一次使用时 NPE,而栈顶离根因很远。
     */
    private <T> Optional<T> constructGreedily(Class<T> type) {
        var constructors = new ArrayList<>(List.of(type.getConstructors()));
        constructors.sort((a, b) -> b.getParameterCount() - a.getParameterCount());

        for (var constructor : constructors) {
            Object[] args = resolveParameters(constructor.getParameterTypes());
            if (args == null) continue;

            try {
                return Optional.of(type.cast(constructor.newInstance(args)));
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // 试下一个构造函数
            }
        }
        return Optional.empty();
    }

    /** @return 有任意一个参数解析不出来时为 null */
    private Object[] resolveParameters(Class<?>[] types) {
        var args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            // 基本类型与 String 这类值参数不从容器取 —— 它们是配置,
            // 不是依赖。容器里碰巧有一个 String bean 就注进去更糟
            if (types[i].isPrimitive() || types[i].getName().startsWith("java.")) {
                return null;
            }
            try {
                args[i] = context.getBean(types[i]);
            } catch (org.springframework.beans.BeansException e) {
                return null;
            }
        }
        return args;
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
