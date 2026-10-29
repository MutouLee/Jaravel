package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.TodoController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 路由定义。
 *
 * <p>控制器用**方法引用**,因此重命名 {@code TodoController#toggle} 时
 * 这里会编译失败 —— Laravel 的 {@code 'TodoController@toggle'} 字符串做不到。
 */
@Component
public class WebRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.get("/", TodoController::index).name("todos.index");

        r.post("/todos", TodoController::store).name("todos.store");
        r.post("/todos/{id}/toggle", TodoController::toggle).whereNumber("id").name("todos.toggle");
        r.post("/todos/{id}/delete", TodoController::destroy).whereNumber("id").name("todos.destroy");

        // 直出视图,无需控制器
        r.view("/about", "todos.about");

        r.prefix("/api").group(api -> {
            api.get("/todos", TodoController::apiIndex).name("api.todos.index");
            api.get("/todos/{id}", TodoController::apiShow).whereNumber("id").name("api.todos.show");
        });
    }
}
