package bench.http.jv;

import org.springframework.stereotype.Component;

import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

@Component
public class PingRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.get("/ping", PingController::ping);
    }
}
