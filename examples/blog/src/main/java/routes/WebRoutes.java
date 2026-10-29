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
        r.get("/", PostController::index).name("posts.index");
        r.get("/posts/create", PostController::create).name("posts.create");
        r.get("/posts/{slug}", PostController::show).name("posts.show");
        r.post("/posts", PostController::store).name("posts.store");
        r.post("/posts/{id}/delete", PostController::destroy).whereNumber("id").name("posts.destroy");
        r.post("/posts/{id}/restore", PostController::restore).whereNumber("id").name("posts.restore");
        r.post("/posts/{id}/comments", CommentController::store).whereNumber("id").name("comments.store");

        r.get("/api/posts", PostController::apiIndex);
        r.get("/api/posts/{id}", PostController::apiShow).whereNumber("id");
    }
}
