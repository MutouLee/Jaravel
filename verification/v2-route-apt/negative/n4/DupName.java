import app.http.controllers.*;
import io.jaravel.routing.*;
/** 负例 4:两条不同路由用了同一个路由名 */
public class DupName implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/a", UserController::index).name("same");
        r.get("/b", UserController::index).name("same");
    }
}
