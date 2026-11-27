package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.PermissionDemoController;
import net.dobeen.jaravel.routing.RouteDefinition;
import net.dobeen.jaravel.routing.Router;

/**
 * RBAC 示例的路由(docs/08 §5.4)。
 *
 * <p>**单独一个 RouteDefinition**,不放进 {@code WebRoutes}:
 * 那个文件是 parity 切片 03 的被测对象。
 */
@Component
public class PermissionDemoRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.get("/permission-demo/role", PermissionDemoController::roleOnly)
         .middleware("web", "auth", "role:admin")
         .name("permission.demo.role");

        r.get("/permission-demo/publish", PermissionDemoController::publish)
         .middleware("web", "auth", "permission:publish-posts")
         .name("permission.demo.publish");
    }
}
