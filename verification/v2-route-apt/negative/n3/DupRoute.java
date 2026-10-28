import app.http.controllers.*;
import io.jaravel.routing.*;
/** 负例 3:同一 GET /users 定义两次 */
public class DupRoute implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/users", UserController::index);
        r.get("/users", UserController::index);
    }
}
