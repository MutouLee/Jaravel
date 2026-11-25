package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.DirectiveDemoController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 条件指令演示页的路由(docs/04 §1.8)。
 *
 * <p>**单独一个 RouteDefinition**,不放进 {@code WebRoutes}:
 * 那个文件是 parity 切片 03 的被测对象(与
 * {@link PermissionDemoRoutes} 同一条理由)。
 *
 * <p>只挂 {@code web} 而**不挂** {@code auth}:这个页面要同时能给游客
 * 与登录用户看,那正是 {@code @auth} / {@code @guest} 的用处。
 */
@Component
public class DirectiveDemoRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        // 挂上 locale:按 ?lang= / 会话 / Accept-Language 选语言。
        // 这个页面用 @lang / @choice 渲染,而消息目录里有 zh_CN 与 en
        // 两份 —— 正好是那个中间件的演示场景
        r.get("/notes/page", DirectiveDemoController::page)
         .middleware("web", "locale").name("notes.page");

        // 局部刷新:只回一行(@fragment)
        r.get("/notes/{id}/row", DirectiveDemoController::row)
         .whereNumber("id")
         .middleware("web").name("notes.row");

        // ★ 真的 DELETE 路由。浏览器表单发不出 DELETE,所以页面上那个
        // 表单是 POST + @method('DELETE'),由 MethodOverrideFilter 改写
        // (docs/04 §1.8)。挂 auth:删之前先得知道你是谁
        r.delete("/notes/{id}", DirectiveDemoController::destroy)
         .whereNumber("id")
         .middleware("web", "auth").name("notes.destroy");
    }
}
