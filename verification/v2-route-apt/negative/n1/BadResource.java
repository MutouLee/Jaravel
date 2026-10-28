import app.http.controllers.*;
import io.jaravel.routing.*;
/** 负例 1:resource() 要求 7 个方法,控制器只有 2 个 */
class HalfController {
    public Object index() { return null; }
    public Object show(Long post) { return null; }
}
public class BadResource implements RouteDefinition {
    @Override public void define(Router r) {
        r.resource("/half", HalfController.class);
    }
}
