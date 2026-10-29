package io.jaravel.spring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.auth.middleware.Authenticate;
import io.jaravel.auth.middleware.Authorize;
import io.jaravel.auth.middleware.ConfirmPassword;
import io.jaravel.auth.middleware.EnsureEmailVerified;
import io.jaravel.auth.middleware.RedirectIfAuthenticated;
import io.jaravel.auth.middleware.ThrottleRequests;
import io.jaravel.http.Kernel;
import io.jaravel.http.Middleware;
import io.jaravel.session.StartSession;
import io.jaravel.session.VerifyCsrfToken;

/**
 * 默认内核:预置 {@code web} / {@code api} 两组与标准别名。
 *
 * <p>用户不提供自己的 {@code Kernel} bean 时用这个。覆盖的方式是
 * 继承它并只改要改的那一项 —— {@code Kernel.empty()} 做不到这件事,
 * 所以默认值从「空」换成了「Laravel 的那一套」。
 *
 * <pre>{@code
 * // app/http/HttpKernel.java —— 只想加一个全局中间件
 * @Component
 * public class HttpKernel extends DefaultKernel {
 *     @Override protected List<Class<? extends Middleware>> global() {
 *         return List.of(TrimStrings.class);
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么 install:auth 不需要生成一份 Kernel</h2>
 * <p>「加个登录 = 3 行」要求 {@code middleware("auth")} 开箱即用。
 * 如果别名表是脚手架生成的文件,那 3 行就变成了「跑脚手架 + 看懂生成的
 * Kernel + 确认别名拼写」。所以别名在框架里,生成的只有 config/auth.yml
 * 和登录控制器。
 */
public class DefaultKernel extends Kernel {

    /**
     * {@code web} 组:会话 + CSRF。
     *
     * <p>顺序不是靠这里的声明保证的 —— 见 {@link #priority()}。
     */
    @Override
    protected Map<String, List<Class<? extends Middleware>>> groups() {
        var groups = new LinkedHashMap<String, List<Class<? extends Middleware>>>();
        // bindings 放进 web 与 api 两组:隐式模型绑定应该是默认行为,
        // 而不是「记得在路由上加一个 bindings」。忘了加的症状是
        // can:update,post 永远拒绝,那看起来像权限配错了
        groups.put("web", List.of(StartSession.class, VerifyCsrfToken.class,
                                  SubstituteBindings.class));

        // api 组刻意**不含** StartSession:无状态接口不该下发会话 cookie。
        // 空组而不是省略 —— 这样路由上写 middleware("api") 不会报
        // 「没有这个分组」,而写了之后再往里加东西也不需要先建组
        groups.put("api", List.of(SubstituteBindings.class));
        return groups;
    }

    @Override
    protected Map<String, Class<? extends Middleware>> aliases() {
        var aliases = new LinkedHashMap<String, Class<? extends Middleware>>();
        aliases.put("auth", Authenticate.class);                    // auth / auth:api
        aliases.put("guest", RedirectIfAuthenticated.class);
        aliases.put("can", Authorize.class);                        // can:update,post
        aliases.put("verified", EnsureEmailVerified.class);
        aliases.put("throttle", ThrottleRequests.class);            // throttle:60,1
        aliases.put("password.confirm", ConfirmPassword.class);     // password.confirm:30
        aliases.put("bindings", SubstituteBindings.class);
        aliases.put("session", StartSession.class);
        aliases.put("csrf", VerifyCsrfToken.class);
        return aliases;
    }

    /**
     * 硬顺序约束。
     *
     * <p>每一条都对应一个真实会发生的故障:
     * <ol>
     *   <li><b>StartSession 必须最先。</b> 其余全部依赖会话:CSRF 要比对
     *       会话里的 token,SessionGuard 要读会话里的用户 id。顺序错了的
     *       症状是「登录成功但下一个请求又变成未登录」—— 而用户会以为是
     *       登录逻辑有问题。</li>
     *   <li><b>VerifyCsrfToken 在认证之前。</b> CSRF 校验不需要知道是谁,
     *       而让伪造的请求先走到认证逻辑等于白送一次登录尝试(以及一次
     *       节流计数)。</li>
     *   <li><b>Authenticate 在 Authorize 之前。</b> 「这是谁」必须先有答案,
     *       否则授权永远在对游客做判断,一律 403 —— 而 403 看起来像
     *       「权限配错了」,不像「认证没跑」。</li>
     *   <li><b>EnsureEmailVerified / ConfirmPassword 在 Authenticate 之后。</b>
     *       它们都要读当前用户。</li>
     *   <li><b>SubstituteBindings 在 Authorize 之前。</b> {@code can:update,post}
     *       要的是 Post 实例;拿到字符串会让规则永远拒绝,而 403 看起来像
     *       「权限配错了」,不像「模型没绑上」。</li>
     *   <li><b>ThrottleRequests 最先于认证。</b> 节流的意义就是在昂贵操作
     *       (查库 + 哈希)之前挡住洪水。放在认证之后等于没放。</li>
     * </ol>
     *
     * <p>第 3 条是 docs/08 §10 列出的待决问题之一,这里把它固定下来并由
     * {@code DefaultKernelTest} 守住。
     */
    @Override
    protected List<Class<? extends Middleware>> priority() {
        return List.of(
                StartSession.class,
                VerifyCsrfToken.class,
                ThrottleRequests.class,
                Authenticate.class,
                RedirectIfAuthenticated.class,
                EnsureEmailVerified.class,
                ConfirmPassword.class,
                // **必须在 Authorize 之前**:can:update,post 需要的是
                // Post 实例,而中间件比控制器先跑。拿到字符串 "42" 去问
                // PostPolicy.update(User, Post) 的结果是永远拒绝,
                // 而那看起来像「权限配错了」。
                // docs/08 §10 把这条顺序列为必须固定的待决项
                SubstituteBindings.class,
                Authorize.class);
    }
}
