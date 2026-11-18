package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.PostController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

@Component
public class ApiRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.prefix("/api").middleware("api").group(api -> {
            api.get("/posts", PostController::index).name("api.posts.index");
            api.get("/posts/{id}", PostController::show).whereNumber("id")
               .name("api.posts.show");
            api.post("/posts", PostController::store).name("api.posts.store");
        });
    }
}
