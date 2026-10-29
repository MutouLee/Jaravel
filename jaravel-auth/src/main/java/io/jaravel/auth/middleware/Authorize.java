package io.jaravel.auth.middleware;

import java.util.List;
import java.util.function.Function;

import io.jaravel.auth.access.Gate;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 路由级授权:{@code middleware("can:update,post")}。
 *
 * <p>第一个参数是 ability,其余是要传给规则的参数名 ——
 * 从**路由参数**里取。{@code can:update,post} 配 {@code /posts/{post}}
 * 时,取路径里的 {@code post}。
 *
 * <h2>路由参数是字符串,规则要的是模型</h2>
 * <p>这是这个中间件最关键的一点:{@code /posts/{post}} 里的 {@code post}
 * 是 {@code "42"},而 {@code PostPolicy.update(User, Post)} 要的是 Post 实例。
 * Laravel 用「隐式模型绑定」解决 —— 路由参数名与模型名对上时自动查库。
 *
 * <p>Jaravel 的隐式绑定排在 v0.3 之后(它需要路由与 Eloquent 的联动),
 * 所以当前实现是:**参数值原样传给规则**。规则自己接 String 或先查库。
 * 这个限制必须说清楚,否则用户会写出一个永远拒绝的规则而不知道为什么 ——
 * 所以这里在类型对不上时给出明确的错误,而不是让它静默 403。
 */
public class Authorize implements Middleware.Parameterized {

    private String ability;
    private List<String> parameterNames = List.of();

    @Override
    public void configure(String... params) {
        if (params.length == 0) {
            throw new IllegalArgumentException("""
                    can 中间件需要至少一个参数(ability)。
                      写法:middleware("can:update,post")
                            middleware("can:viewAny")""");
        }
        this.ability = params[0];
        this.parameterNames = List.of(params).subList(1, params.length);
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        Object[] args = parameterNames.stream()
                .map(name -> resolve(request, name))
                .toArray();

        Gate.authorize(ability, args);
        return next.apply(request);
    }

    /**
     * 解析一个参数名。
     *
     * <p>优先路由参数;其次把它当成类名(用于 {@code can:create,app.models.Post}
     * 这种没有实例的操作)。
     */
    private Object resolve(Request request, String name) {
        var routeParam = request.routeParam(name);
        if (routeParam.isPresent()) return routeParam.get();

        // 全限定类名 → Class,供 create / viewAny 这类操作用
        if (name.contains(".")) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("""
                        can:%s,%s 里的 "%s" 既不是路由参数也不是可加载的类名。
                          路由 %s 的参数:%s
                          写法:
                            · 路由参数   middleware("can:update,post")   配 /posts/{post}
                            · 模型类     middleware("can:create,app.models.Post")"""
                        .formatted(ability, name, name, request.path(),
                                   request.routeParams().keySet()), e);
            }
        }

        throw new IllegalStateException("""
                can:%s,%s 里的 "%s" 不是当前路由的参数。
                  路由 %s 的参数:%s
                  检查参数名是否与路径里的 {名字} 一致。"""
                .formatted(ability, name, name, request.path(),
                           request.routeParams().keySet()));
    }

    public String ability() {
        return ability;
    }

    public List<String> parameterNames() {
        return parameterNames;
    }
}
