package routes;

import app.http.controllers.PostController;
import app.http.controllers.UserController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/users", UserController::index).name("users.index");
        r.get("/users/{id}", UserController::show).name("users.show");
        r.post("/users", UserController::store).name("users.store");
        r.put("/users/{id}", UserController::update);
        r.delete("/users/{id}", UserController::destroy);

        r.resource("/posts", PostController.class);
    }
}
