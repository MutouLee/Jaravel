package io.jaravel.http;

import java.util.List;
import java.util.Map;

/**
 * HTTP 内核:声明中间件的全局链、分组、别名与优先级。
 *
 * <pre>{@code
 * // app/http/HttpKernel.java
 * public class HttpKernel extends Kernel {
 *
 *     @Override protected List<Class<? extends Middleware>> global() {
 *         return List.of(TrimStrings.class, HandleCors.class);
 *     }
 *
 *     @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
 *         return Map.of("web", List.of(StartSession.class, VerifyCsrfToken.class));
 *     }
 *
 *     @Override protected Map<String, Class<? extends Middleware>> aliases() {
 *         return Map.of("auth", Authenticate.class, "throttle", ThrottleRequests.class);
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么要有「优先级」这一项</h2>
 * <p>中间件的声明顺序来自两处:分组里的顺序,和路由上写的顺序。两处合并后
 * 可能出现 {@code auth} 跑在 {@code StartSession} 之前 —— 而认证需要会话,
 * 于是用户看到的是「登录了但立刻被当成未登录」。{@link #priority()} 把
 * 「谁必须在谁之前」从**声明顺序**里解耦出来,声明在哪都不会错。
 *
 * <p>这是 Laravel 的 {@code $middlewarePriority},照抄的理由是:这个问题
 * 在任何中间件体系里都会出现,而「让用户自己保证顺序」已被证明不可行。
 */
public abstract class Kernel {

    /** 所有请求都经过,按声明顺序 */
    protected List<Class<? extends Middleware>> global() {
        return List.of();
    }

    /** 命名分组。路由上写 {@code middleware("web")} 即展开为整组。 */
    protected Map<String, List<Class<? extends Middleware>>> groups() {
        return Map.of();
    }

    /** 单个中间件的别名。路由上写 {@code middleware("auth")} 或 {@code "throttle:60,1"}。 */
    protected Map<String, Class<? extends Middleware>> aliases() {
        return Map.of();
    }

    /**
     * 执行顺序的硬约束。
     *
     * <p>出现在这个列表里的中间件,无论在哪声明,都按这里的相对顺序执行;
     * 不在列表里的保持声明顺序。
     */
    protected List<Class<? extends Middleware>> priority() {
        return List.of();
    }

    /**
     * 没有任何中间件的空内核。
     *
     * <p>用户不提供 Kernel 时的默认值 —— 它让「不写 Kernel 也能跑」成立,
     * 同时让「写了 Kernel」是一个纯增量的动作。
     */
    public static Kernel empty() {
        return new Kernel() {};
    }

    // ---------- 供框架读取(把 protected 的声明暴露给解析器) ----------

    public final List<Class<? extends Middleware>> globalMiddleware() { return global(); }

    public final Map<String, List<Class<? extends Middleware>>> middlewareGroups() {
        return groups();
    }

    public final Map<String, Class<? extends Middleware>> middlewareAliases() {
        return aliases();
    }

    public final List<Class<? extends Middleware>> middlewarePriority() { return priority(); }
}
