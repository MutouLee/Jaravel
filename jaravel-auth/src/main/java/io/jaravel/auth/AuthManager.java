package io.jaravel.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.support.Arr;

/**
 * 按名字管理 guard 与 provider。
 *
 * <p>guard 实例是**请求级**的 —— 它缓存「当前用户」,跨请求复用会把
 * 上一个请求的用户带给下一个,那是最严重的一类安全问题。所以
 * {@link #guard(String)} 把实例放进 {@link ExecutionScope},
 * 请求结束即丢。
 *
 * <p>provider 相反:它无状态(只是查询逻辑),可以全局共享。
 */
public final class AuthManager {

    /** guard 名 → 工厂 */
    private final Map<String, Function<UserProvider, Guard>> guardFactories =
            new LinkedHashMap<>();
    /** guard 名 → 它用哪个 provider */
    private final Map<String, String> guardProviders = new LinkedHashMap<>();
    private final Map<String, UserProvider> providers = new LinkedHashMap<>();

    private String defaultGuard = "web";

    // ================= 注册 =================

    public AuthManager registerProvider(UserProvider provider) {
        providers.put(provider.name(), provider);
        return this;
    }

    /**
     * 注册一个 guard。
     *
     * @param providerName 它用哪个 provider
     * @param factory      由 provider 构造 guard —— 工厂而非实例,
     *                     因为 guard 是请求级的
     */
    public AuthManager registerGuard(String name, String providerName,
                                     Function<UserProvider, Guard> factory) {
        guardFactories.put(name, factory);
        guardProviders.put(name, providerName);
        return this;
    }

    public AuthManager defaultGuard(String name) {
        this.defaultGuard = name;
        return this;
    }

    public String defaultGuardName() {
        // 本请求内可被 shouldUse 覆盖
        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.attribute("auth.guard", String.class))
                .orElse(defaultGuard);
    }

    /**
     * 本请求内切换默认 guard。
     *
     * <p>用途:一条路由同时服务 web 与 api 时,中间件按请求特征选 guard,
     * 之后的 {@code Auth.user()} 自动用对的那个。
     */
    public void shouldUse(String name) {
        requireKnownGuard(name);
        ExecutionScope.tryCurrent().ifPresentOrElse(
                scope -> scope.put("auth.guard", name),
                () -> {
                    throw new IllegalStateException(
                            "shouldUse 只能在请求作用域内调用 —— 它改的是**本请求**的"
                          + "默认 guard。要改全局默认请用 AuthManager.defaultGuard()。");
                });
    }

    public java.util.Set<String> registeredGuards() {
        return java.util.Set.copyOf(guardFactories.keySet());
    }

    public java.util.Set<String> registeredProviders() {
        return java.util.Set.copyOf(providers.keySet());
    }

    // ================= 解析 =================

    public Guard guard() {
        return guard(defaultGuardName());
    }

    /**
     * 取指定 guard。请求作用域内按名字缓存实例。
     *
     * <p>缓存是必要的:{@code Auth.check()} 后紧跟 {@code Auth.user()} 不该
     * 查两次库,而 SessionGuard 的用户缓存只在同一个实例里有效。
     */
    public Guard guard(String name) {
        requireKnownGuard(name);

        return ExecutionScope.tryCurrent()
                .map(scope -> scopedGuard(scope, name))
                .orElseGet(() -> build(name));
    }

    /**
     * 每个 guard 名一个独立的缓存键类型。
     *
     * <p>{@link ExecutionScope#scoped} 按 Class 做键,而所有 guard 都是
     * 同一组类 —— 直接用 {@code Guard.class} 会让 web 与 admin 两个 guard
     * 共用一个实例,于是「admin 登录了」会被当成「web 也登录了」。
     * 这里改用字符串键的作用域属性来避开。
     */
    private Guard scopedGuard(ExecutionScope scope, String name) {
        String key = guardCacheKeyName(name);
        var existing = scope.attribute(key, Guard.class);
        if (existing.isPresent()) return existing.get();

        Guard built = build(name);
        scope.put(key, built);
        return built;
    }

    private static String guardCacheKeyName(String name) {
        return "auth.guard.instance." + name;
    }

    private Guard build(String name) {
        String providerName = guardProviders.get(name);
        UserProvider provider = providers.get(providerName);

        if (provider == null) {
            throw new IllegalStateException("""
                    guard "%s" 声明使用 provider "%s",但它没有注册。%s
                      已注册的 provider:%s
                      在 config/auth.yml 的 auth.providers 下登记它。"""
                    .formatted(name, providerName,
                               Arr.closest(providerName, providers.keySet().stream().toList())
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                               providers.keySet()));
        }
        return guardFactories.get(name).apply(provider);
    }

    private void requireKnownGuard(String name) {
        if (guardFactories.containsKey(name)) return;

        throw new IllegalStateException("""
                没有名为 "%s" 的 guard。%s
                  已注册:%s
                  在 config/auth.yml 的 auth.guards 下登记它,
                  或改用已注册的名字(路由上写 middleware("auth:%s"))。"""
                .formatted(name,
                           Arr.closest(name, guardFactories.keySet().stream().toList())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           guardFactories.keySet(),
                           guardFactories.keySet().stream().findFirst().orElse("web")));
    }

    public UserProvider provider(String name) {
        UserProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalStateException(
                    "没有名为 \"" + name + "\" 的 user provider。已注册:" + providers.keySet());
        }
        return provider;
    }
}
