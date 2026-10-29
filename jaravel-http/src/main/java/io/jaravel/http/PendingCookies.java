package io.jaravel.http;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.core.context.ExecutionScope;

/**
 * 本请求内要下发的 cookie,由框架在响应写出前统一附加。
 *
 * <pre>{@code
 * // 深处的某个组件想下发一个 cookie
 * PendingCookies.queue(Cookie.make("remember_web", value).httpOnly(true));
 * }</pre>
 *
 * <h2>为什么需要这个队列</h2>
 * <p>有些 cookie 的决定点离响应很远。典型是 remember me:
 * {@code Auth.login(user, true)} 发生在**控制器中间**,而那时响应对象
 * 还不存在 —— 控制器之后可能返回 Redirect、View、JSON,甚至抛异常
 * 被异常处理器换掉。
 *
 * <p>让 {@code SessionGuard} 把 cookie 挂在自己身上(一个
 * {@code takePendingCookie()} 方法)看起来可行,但要求**有人去取**。
 * 实际结果是:单元测试里取了,HTTP 管道里没人取 —— 于是
 * {@code remember=true} 在测试中一切正常,在浏览器里从来不生效。
 * 这正是这个类要消除的那类缺口。
 *
 * <h2>作用域</h2>
 * <p>挂在 {@link ExecutionScope} 上,请求结束即丢。不在请求作用域内时
 * {@link #queue} 静默忽略 —— 调用方(如队列任务里的 {@code Auth.login})
 * 本来就没有响应可写,为此抛异常只会让正常用法炸掉。
 */
public final class PendingCookies {

    private static final String KEY = "http.pending-cookies";

    private PendingCookies() {
    }

    public static void queue(Cookie cookie) {
        if (cookie == null) return;

        ExecutionScope.tryCurrent().ifPresent(scope -> {
            @SuppressWarnings("unchecked")
            var queued = (List<Cookie>) scope.attribute(KEY).orElse(null);
            if (queued == null) {
                queued = new ArrayList<>();
                scope.put(KEY, queued);
            }
            // 同名覆盖:后一次决定胜出。登录后紧接着登出(同一个请求里
            // 不常见但合法)时,留下的应该是「删除」那一条
            queued.removeIf(existing -> existing.name().equals(cookie.name()));
            queued.add(cookie);
        });
    }

    /** 取出并清空 —— 由框架在写响应前调用一次 */
    public static List<Cookie> drain() {
        return ExecutionScope.tryCurrent()
                .map(scope -> {
                    @SuppressWarnings("unchecked")
                    var queued = (List<Cookie>) scope.attribute(KEY).orElse(null);
                    if (queued == null || queued.isEmpty()) return List.<Cookie>of();

                    var snapshot = List.copyOf(queued);
                    queued.clear();
                    return snapshot;
                })
                .orElseGet(List::of);
    }

    /**
     * 把排队的 cookie 附加到响应上。
     *
     * <p>响应里已有同名 cookie 时**以响应为准** —— 控制器显式写的
     * 优先于框架排队的。
     */
    public static Response applyTo(Response response) {
        var queued = drain();
        if (queued.isEmpty()) return response;

        var alreadySet = response.cookies().stream().map(Cookie::name).toList();
        Response result = response;
        for (Cookie cookie : queued) {
            if (!alreadySet.contains(cookie.name())) {
                result = result.cookie(cookie);
            }
        }
        return result;
    }
}
