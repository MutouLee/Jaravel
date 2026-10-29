package io.jaravel.auth.access;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.jaravel.auth.Auth;
import io.jaravel.auth.Authenticatable;
import io.jaravel.support.Arr;

/**
 * 授权判定:简单规则(Gate)与模型规则(Policy)的统一入口。
 *
 * <pre>{@code
 * // 定义
 * Gate.define("view-admin", (User u) -> u.isAdmin());
 * Gate.define("update-post", (User u, Post p) -> u.id.equals(p.userId));
 * Gate.policy(Post.class, new PostPolicy());
 *
 * // 使用
 * Gate.allows("update-post", post);
 * Gate.authorize("update", post);          // 失败抛 403
 * Gate.forUser(other).allows("update", post);
 * }</pre>
 *
 * <h2>Gate 与 Policy 的分工</h2>
 * <p>Gate 适合「与模型无关」的权限(查看后台、导出报表);Policy 适合
 * 「对某个模型的某个操作」。两者在这里共用一套查找逻辑:先找同名 Gate,
 * 没有再按参数类型找 Policy。
 *
 * <p><b>未定义的权限一律拒绝。</b> 这是唯一安全的默认 —— 打错一个
 * ability 名字就「通过」的话,权限体系等于不存在。但同时要报出
 * 「没有定义过这个权限」而不是静默 403,否则排查时分不清
 * 「规则拒绝了我」与「规则不存在」。
 */
public final class Gate {

    private Gate() {}

    private static volatile GateRegistry registry = new GateRegistry();

    public static void use(GateRegistry configured) {
        registry = configured;
    }

    public static GateRegistry registry() {
        return registry;
    }

    // ================= 定义 =================

    /** 不需要额外参数的权限 */
    public static <U> void define(String ability, Function<U, Object> rule) {
        registry.define(ability, (user, args) -> toDecision(rule.apply(cast(user))));
    }

    /** 需要一个参数的权限 */
    public static <U, T> void define(String ability, BiFunction<U, T, Object> rule) {
        registry.define(ability, (user, args) -> {
            if (args.isEmpty()) {
                throw new IllegalArgumentException("""
                        权限 "%s" 的规则需要一个参数,但调用时没给。
                          定义:Gate.define("%s", (User u, Post p) -> ...)
                          调用:Gate.allows("%s", post)"""
                        .formatted(ability, ability, ability));
            }
            return toDecision(rule.apply(cast(user), cast(args.get(0))));
        });
    }

    public static void policy(Class<?> modelType, Object policy) {
        registry.policy(modelType, policy);
    }

    /** 所有权限判定之前先走一遍 —— 超级管理员通常在这里一刀切 */
    public static void before(BiFunction<Authenticatable, String, Optional<Boolean>> hook) {
        registry.before(hook);
    }

    /** 判定之后的观察点(审计日志) */
    public static void after(AfterHook hook) {
        registry.after(hook);
    }

    public interface AfterHook {
        void observe(Authenticatable user, String ability, Decision decision, List<Object> args);
    }

    // ================= 判定 =================

    public static Decision inspect(String ability, Object... args) {
        return registry.inspect(currentUser(), ability, List.of(args));
    }

    public static boolean allows(String ability, Object... args) {
        return inspect(ability, args).allowed();
    }

    public static boolean denies(String ability, Object... args) {
        return inspect(ability, args).denied();
    }

    /** 失败抛 {@link AuthorizationException} → 403(或规则指定的状态码) */
    public static void authorize(String ability, Object... args) {
        inspect(ability, args).authorize(ability);
    }

    public static boolean any(List<String> abilities, Object... args) {
        return abilities.stream().anyMatch(a -> allows(a, args));
    }

    public static boolean none(List<String> abilities, Object... args) {
        return abilities.stream().noneMatch(a -> allows(a, args));
    }

    /** 换一个用户来判定 —— 后台「以该用户视角预览」时用 */
    public static ForUser forUser(Authenticatable user) {
        return new ForUser(registry, user);
    }

    public static ForUser forUser(Object model) {
        return new ForUser(registry, wrapIfNeeded(model));
    }

    public record ForUser(GateRegistry registry, Authenticatable user) {

        public Decision inspect(String ability, Object... args) {
            return registry.inspect(Optional.ofNullable(user), ability, List.of(args));
        }

        public boolean allows(String ability, Object... args) {
            return inspect(ability, args).allowed();
        }

        public boolean denies(String ability, Object... args) {
            return inspect(ability, args).denied();
        }

        public void authorize(String ability, Object... args) {
            inspect(ability, args).authorize(ability);
        }
    }

    // ================= 工具 =================

    /**
     * 当前用户。
     *
     * <p>**未登录不等于拒绝**:有些权限对访客开放({@code viewAny} 看公开列表)。
     * 所以传 {@code Optional.empty()} 给规则,由规则自己决定 ——
     * 而规则签名里的 {@code User u} 为 null 时,{@link GateRegistry} 会
     * 跳过那条规则并拒绝(见那里的说明)。
     */
    private static Optional<Authenticatable> currentUser() {
        try {
            return Auth.user();
        } catch (RuntimeException e) {
            // 没有认证上下文(队列任务、命令行)。当作访客而不是抛异常 ——
            // Gate 在后台任务里被调用是正常的,而那里本来就没有「当前用户」
            return Optional.empty();
        }
    }

    private static Authenticatable wrapIfNeeded(Object model) {
        if (model instanceof Authenticatable a) return a;
        return new RawUser(model);
    }

    /** 把任意对象当作主体 —— {@code Gate.forUser(someUser)} 的便利包装 */
    private record RawUser(Object model) implements Authenticatable {
        @Override public Object getAuthIdentifier() { return model; }
        @Override public Optional<String> getAuthPassword() { return Optional.empty(); }
        @Override public Object unwrap() { return model; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Optional<Authenticatable> user) {
        return (T) user.map(Authenticatable::unwrap).orElse(null);
    }

    /** 测试用:把 toDecision 的行为暴露出来 */
    public static Decision toDecisionForTest(Object result) {
        return toDecision(result);
    }

    static Decision toDecision(Object result) {
        if (result instanceof Decision d) return d;
        if (result instanceof Boolean b) return Decision.of(b);
        if (result == null) return Decision.deny();

        throw new IllegalStateException("""
                权限规则返回了 %s,但只接受 boolean 或 Decision。
                  写法:
                    (User u, Post p) -> u.id.equals(p.userId)                 // boolean
                    (User u, Post p) -> p.isComplete() ? Decision.allow()
                                                       : Decision.deny("未完成", 422)"""
                .formatted(result.getClass().getName()));
    }
}
