import app.http.controllers.*;
import io.jaravel.routing.*;
class C2 { public Object show(Long userId) { return null; } }
/** 负例 2:路径写 {id},方法参数叫 userId */
public class BadParamName implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/users/{id}", C2::show);
    }
}
