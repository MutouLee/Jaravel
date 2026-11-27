package bench.http.jv;

import org.springframework.stereotype.Component;

import net.dobeen.jaravel.routing.RouteDefinition;
import net.dobeen.jaravel.routing.Router;

@Component
public class PingRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.get("/ping", PingController::ping);
    }
}
