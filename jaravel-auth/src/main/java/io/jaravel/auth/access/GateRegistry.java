package io.jaravel.auth.access;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import io.jaravel.auth.Authenticatable;
import io.jaravel.support.Arr;

/**
 * Gate 规则与 Policy 的注册表,以及判定逻辑本身。
 *
 * <p>与 {@link Gate} 分开:门面是静态的(方便调用),而注册表是对象
 * (方便测试里建一个干净的、以及将来支持多租户不同规则)。
 */
public class GateRegistry {

    /** ability → 规则 */
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    /** 模型类型 → policy 实例 */
    private final Map<Class<?>, Object> policies = new LinkedHashMap<>();

    private final List<BiFunction<Authenticatable, String, Optional<Boolean>>> beforeHooks =
            new ArrayList<>();
    private final List<Gate.AfterHook> afterHooks = new ArrayList<>();

    public interface Rule {
        Decision evaluate(Optional<Authenticatable> user, List<Object> args);
    }

    // ================= 注册 =================

    public GateRegistry define(String ability, Rule rule) {
        rules.put(ability, rule);
        return this;
    }

    public GateRegistry policy(Class<?> modelType, Object policy) {
        validatePolicy(modelType, policy);
        policies.put(modelType, policy);
        return this;
    }

    public GateRegistry before(BiFunction<Authenticatable, String, Optional<Boolean>> hook) {
        beforeHooks.add(hook);
        return this;
    }

    public GateRegistry after(Gate.AfterHook hook) {
        afterHooks.add(hook);
        return this;
    }

    public java.util.Set<String> definedAbilities() {
        return java.util.Set.copyOf(rules.keySet());
    }

    public java.util.Set<Class<?>> policiedTypes() {
        return java.util.Set.copyOf(policies.keySet());
    }

    /**
     * 启动期校验 policy 的方法签名。
     *
     * <p>检查「第二个参数的类型是不是目标模型」—— docs/08 §5.2 要求 APT 做这件事。
     * 这里改在注册时用反射做:时机晚一点(启动而非编译),但不必再加一轮 APT,
     * 而且错误同样在任何请求之前暴露。
     *
     * <p>不检查会怎样:{@code update(User u, Comment c)} 注册给 Post 之后,
     * 调用 {@code Gate.authorize("update", post)} 会在反射调用时
     * 抛 IllegalArgumentException,报错是「argument type mismatch」——
     * 完全看不出是 policy 写错了类型。
     */
    private void validatePolicy(Class<?> modelType, Object policy) {
        var problems = new ArrayList<String>();

        for (Method method : policy.getClass().getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            if (method.isSynthetic()) continue;
            if (method.getName().equals("before")) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length < 2) continue;       // viewAny(User) 这类不带模型

            if (!params[1].isAssignableFrom(modelType) && !modelType.isAssignableFrom(params[1])) {
                problems.add("  · %s(%s, %s) 的第二个参数应是 %s".formatted(
                        method.getName(), params[0].getSimpleName(),
                        params[1].getSimpleName(), modelType.getSimpleName()));
            }
            if (params.length > 2) {
                problems.add("  · %s 有 %d 个参数;policy 方法最多两个(用户, 模型)"
                        .formatted(method.getName(), params.length));
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("""
                    Policy %s 注册给 %s 时发现签名问题:
                    %s
                      正确形式:
                        public boolean update(User user, %s model) { ... }
                        public boolean viewAny(User user) { ... }          // 不带模型
                        public Optional<Boolean> before(User user, String ability) { ... }

                      不在注册时检查的话,这些错误会在调用时变成
                      「argument type mismatch」—— 完全看不出是 policy 写错了。"""
                    .formatted(policy.getClass().getSimpleName(), modelType.getSimpleName(),
                               String.join("\n", problems), modelType.getSimpleName()));
        }
    }

    // ================= 判定 =================

    public Decision inspect(Optional<Authenticatable> user, String ability, List<Object> args) {
        Decision decision = evaluate(user, ability, args);

        for (Gate.AfterHook hook : afterHooks) {
            hook.observe(user.orElse(null), ability, decision, args);
        }
        return decision;
    }

    private Decision evaluate(Optional<Authenticatable> user, String ability, List<Object> args) {
        // before 钩子:返回非空即短路(超级管理员一刀切)
        for (var hook : beforeHooks) {
            var shortCircuit = user.flatMap(u -> hook.apply(u, ability));
            if (shortCircuit.isPresent()) {
                return Decision.of(shortCircuit.get());
            }
        }

        // 1. 同名 Gate 规则
        Rule rule = rules.get(ability);
        if (rule != null) {
            return guarded(ability, () -> rule.evaluate(user, args));
        }

        // 2. 按参数类型找 Policy
        if (!args.isEmpty()) {
            var viaPolicy = evaluateViaPolicy(user, ability, args);
            if (viaPolicy.isPresent()) return viaPolicy.get();
        }

        // 3. 没有任何规则 → 拒绝,但说清是「没定义」
        return Decision.deny(undefinedMessage(ability, args), 403);
    }

    /**
     * 未定义权限的消息。
     *
     * <p>**拒绝 + 说明**,而不是静默 403。打错一个 ability 名字时,
     * 静默拒绝会让人以为是规则逻辑不对,然后去读规则 —— 而规则根本不存在。
     */
    private String undefinedMessage(String ability, List<Object> args) {
        String hint = Arr.closest(ability, rules.keySet().stream().toList())
                .map(c -> " 是否想写 \"" + c + "\" ?").orElse("");

        String policyHint = "";
        if (!args.isEmpty() && args.get(0) != null) {
            Class<?> type = args.get(0).getClass();
            Object policy = findPolicy(type);
            if (policy == null) {
                policyHint = "\n  也没有为 %s 注册 Policy —— Gate.policy(%s.class, new %sPolicy())"
                        .formatted(type.getSimpleName(), type.getSimpleName(),
                                   type.getSimpleName());
            } else {
                policyHint = "\n  %s 上没有名为 \"%s\" 的方法。它有:%s".formatted(
                        policy.getClass().getSimpleName(), ability,
                        policyMethodNames(policy));
            }
        }

        return "权限 \"%s\" 没有定义,按最小权限原则拒绝。%s%s\n  已定义的 Gate:%s"
                .formatted(ability, hint, policyHint, rules.keySet());
    }

    private Optional<Decision> evaluateViaPolicy(Optional<Authenticatable> user,
                                                 String ability, List<Object> args) {
        Object target = args.get(0);
        if (target == null) return Optional.empty();

        // 支持传 Class(create / viewAny 这类没有实例的操作)
        Class<?> modelType = target instanceof Class<?> c ? c : target.getClass();
        Object policy = findPolicy(modelType);
        if (policy == null) return Optional.empty();

        // policy 自己的 before 钩子
        var policyBefore = invokePolicyBefore(policy, user, ability);
        if (policyBefore.isPresent()) return Optional.of(Decision.of(policyBefore.get()));

        boolean withModel = !(target instanceof Class<?>);
        Method method = findPolicyMethod(policy, ability, modelType, withModel);
        if (method == null) return Optional.empty();

        Object principal = user.map(Authenticatable::unwrap).orElse(null);
        if (principal == null && requiresUser(method)) {
            // 规则签名要求用户,而当前是访客 → 拒绝。
            // 不传 null 进去:policy 方法几乎都会 u.getX() 然后 NPE,
            // 而那个 NPE 会被当成框架 bug 来查
            return Optional.of(Decision.deny("需要登录", 401));
        }

        Object[] arguments = withModel
                ? new Object[] {principal, target}
                : new Object[] {principal};

        return Optional.of(guarded(ability, () -> {
            try {
                // policy 类本身可以是包私有的(与控制器同包是常见写法),
                // 此时它的 public 方法仍然反射不到 —— 必须显式放开
                makeAccessible(method);
                return Gate.toDecision(method.invoke(policy, arguments));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Policy 方法 " + method + " 不可访问(需要 public)", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException(
                        "Policy 方法 " + method.getName() + " 抛出异常:" + cause, cause);
            }
        }));
    }

    /** 按类型找 policy,沿继承链向上(子类模型复用父类 policy) */
    Object findPolicy(Class<?> modelType) {
        for (Class<?> type = modelType; type != null; type = type.getSuperclass()) {
            Object policy = policies.get(type);
            if (policy != null) return policy;
        }
        return null;
    }

    /**
     * 找 policy 上的方法。
     *
     * <p>ability 名字支持两种写法:{@code update} 与 {@code update-post} ——
     * 后者是 Gate 风格的名字,路由上写 {@code can:update-post,post} 时会用到。
     * 两者都映射到 policy 的 {@code update} 方法。
     */
    private Method findPolicyMethod(Object policy, String ability,
                                    Class<?> modelType, boolean withModel) {
        List<String> candidates = List.of(
                ability,
                io.jaravel.support.Str.camel(ability.replace('-', '_')),
                // update-post → update
                ability.contains("-") ? ability.substring(0, ability.indexOf('-')) : ability);

        for (String name : candidates) {
            for (Method method : policy.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getDeclaringClass() == Object.class) continue;

                int expected = withModel ? 2 : 1;
                if (method.getParameterCount() != expected) continue;
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Optional<Boolean> invokePolicyBefore(Object policy, Optional<Authenticatable> user,
                                                 String ability) {
        if (user.isEmpty()) return Optional.empty();

        for (Method method : policy.getClass().getMethods()) {
            if (!method.getName().equals("before")) continue;
            if (method.getParameterCount() != 2) continue;

            try {
                makeAccessible(method);
                Object result = method.invoke(policy,
                        user.get().unwrap(), ability);
                if (result instanceof Optional<?> o) {
                    return (Optional<Boolean>) o;
                }
                if (result instanceof Boolean b) {
                    // 返回裸 boolean 的 before 只在 true 时短路 ——
                    // 否则「返回 false」会把所有权限一刀切掉,
                    // 而那几乎不是作者的意思
                    return b ? Optional.of(true) : Optional.empty();
                }
                return Optional.empty();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("""
                        调用 %s.before(%s, String) 失败:%s
                          检查它的签名 —— 应是:
                            public Optional<Boolean> before(User user, String ability)
                          返回 Optional.empty() 表示「不干预,交给具体规则」。"""
                        .formatted(policy.getClass().getSimpleName(),
                                   user.get().unwrap().getClass().getSimpleName(),
                                   e.getMessage()), e);
            }
        }
        return Optional.empty();
    }

    /**
     * 放开反射访问。
     *
     * <p>需要它是因为 policy 类常常是**包私有**的 —— 与控制器同包、
     * 或作为测试类的嵌套类。那种情况下即使方法是 public,
     * 跨包反射调用也会抛 IllegalAccessException,而报错是
     * 「class X cannot access class Y」,看不出是可见性问题。
     */
    private static void makeAccessible(Method method) {
        // 不要用 canAccess(null) 先判 —— 那个重载是给**静态**方法的,
        // 对实例方法会抛 IllegalArgumentException("null object for ...")。
        // trySetAccessible 本身就是幂等且不抛异常的,直接调即可。
        method.trySetAccessible();
    }

    private static boolean requiresUser(Method method) {
        // 第一个参数是用户。原始类型不可能接 null,其余都可能
        return method.getParameterCount() > 0;
    }

    private String policyMethodNames(Object policy) {
        var names = new java.util.TreeSet<String>();
        for (Method method : policy.getClass().getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names.toString();
    }

    /**
     * 包一层:规则里抛异常时不要让它看起来像框架崩了。
     *
     * <p>常见情况是 policy 里 {@code u.getId().equals(...)} 而 getId 返回 null。
     * 直接让 NPE 冒出去的话,堆栈顶是反射调用,排查要绕一圈。
     */
    private Decision guarded(String ability, java.util.function.Supplier<Decision> body) {
        try {
            return body.get();
        } catch (AuthorizationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("""
                    判定权限 "%s" 时规则本身抛出了 %s:%s
                      权限规则应当只返回 boolean / Decision,不该抛异常 ——
                      需要表达「拒绝并说明原因」时用 Decision.deny("原因", 状态码)。
                      常见原因:规则里访问了 null 字段(用户未登录、关联未加载)。"""
                    .formatted(ability, e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }
}
