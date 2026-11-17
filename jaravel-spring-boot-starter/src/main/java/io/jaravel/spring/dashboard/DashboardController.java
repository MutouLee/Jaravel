package io.jaravel.spring.dashboard;

import java.util.Map;

import io.jaravel.dashboard.DashboardService;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.View;

/**
 * 面板的 HTTP 适配(docs/10 §1.8)。
 *
 * <p>读用 GET,改状态用 POST + `@csrf`(路由默认带 {@code web} 组)。
 * 重试/删除后 303 回面板,避免刷新时重复提交。
 */
public final class DashboardController {

    private final DashboardService service;
    private final String base;

    public DashboardController(DashboardService service, String base) {
        this.service = service;
        this.base = base;
    }

    public Response index(Request request) {
        return View.of("dashboard.index", Map.of(
                "data", service.overview(),
                "base", base)).toResponse();
    }

    public Response overview(Request request) {
        return Response.json(service.overview());
    }

    public Response retry(Request request) {
        return withId(request, id -> service.retry(id)
                ? Redirect.to(base, 303)
                : notFound(id));
    }

    public Response forget(Request request) {
        return withId(request, id -> service.forget(id)
                ? Redirect.to(base, 303)
                : notFound(id));
    }

    public Response flush(Request request) {
        service.flushFailed();
        return Redirect.to(base, 303);
    }

    private Response withId(Request request, java.util.function.LongFunction<Response> action) {
        return request.routeParam("id", Long.class)
                .map(action::apply)
                .orElseGet(() -> Response.text("失败任务需要数字 id").status(400));
    }

    private Response notFound(long id) {
        return Response.text("没有 id 为 " + id + " 的失败记录").status(404);
    }
}
