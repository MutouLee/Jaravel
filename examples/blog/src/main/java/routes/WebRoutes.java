package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.CommentController;
import app.http.controllers.PostController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

@Component
public class WebRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        // ★ 页面路由在 web 组里:StartSession + VerifyCsrfToken。
        //
        // 这一组此前是**裸的**,而那让两件事静默失效:
        //   1. 控制器里的 Redirect.with("status", "已发布") —— 闪存需要会话,
        //      没有会话时它收下那个值然后什么都不做;
        //   2. 表单 POST 完全没有 CSRF 防护 —— 一个会被照抄的示例不该这样。
        // 模板里的 @csrf / @error / @old 也依赖会话(docs/04 §1.8)。
        //
        // /api 那组刻意留在外面:无状态接口不该下发会话 cookie,
        // 它们用的是 api 组(见 DefaultKernel 里为什么 api 组不含 StartSession)。
        r.prefix("").middleware("web").group(web -> {
            web.get("/", PostController::index).name("posts.index");
            web.get("/posts/create", PostController::create).name("posts.create");
            web.get("/posts/{slug}", PostController::show).name("posts.show");
            web.post("/posts", PostController::store).name("posts.store");
            web.post("/posts/{id}/delete", PostController::destroy).whereNumber("id").name("posts.destroy");
            web.post("/posts/{id}/restore", PostController::restore).whereNumber("id").name("posts.restore");
            web.post("/posts/{id}/comments", CommentController::store).whereNumber("id").name("comments.store");
        });

        r.get("/api/posts", PostController::apiIndex);
        r.get("/api/posts/paged", PostController::apiPaged);
        r.get("/api/posts/resources", PostController::apiResources);
        r.get("/api/posts/cached", PostController::apiCached);
        r.get("/api/posts/cursor", PostController::apiCursor);
        r.get("/api/posts/formats", PostController::apiFormats);
        r.get("/api/posts/{id}", PostController::apiShow).whereNumber("id");
    }
}
