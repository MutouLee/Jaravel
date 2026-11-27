package bench.http.jv;

import java.util.Map;

import org.springframework.stereotype.Component;

import net.dobeen.jaravel.http.Response;

@Component
public class PingController {

    public Response ping() {
        return Response.json(Map.of("ok", true, "app", "jaravel"));
    }
}
