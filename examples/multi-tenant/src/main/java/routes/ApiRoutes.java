package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.NoteController;
import app.http.controllers.TenantController;
import net.dobeen.jaravel.routing.RouteDefinition;
import net.dobeen.jaravel.routing.Router;

@Component
public class ApiRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.prefix("/api").middleware("api").group(api -> {
            // 发现租户:不带 tenant 中间件,便于试用时找到可用的 X-Tenant
            api.get("/tenants", TenantController::index).name("api.tenants.index");

            api.get("/notes", NoteController::index).middleware("tenant")
               .name("api.notes.index");
            api.post("/notes", NoteController::store).middleware("tenant")
               .name("api.notes.store");
        });
    }
}
