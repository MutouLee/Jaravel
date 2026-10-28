import app.http.controllers.*;
import io.jaravel.routing.*;
/** 负例 5(应优雅降级,不报错):URI 是动态构造的 */
public class DynamicUri implements RouteDefinition {
    static final String PREFIX = "/api";
    @Override public void define(Router r) {
        r.get(PREFIX + "/users", UserController::index);
    }
}
