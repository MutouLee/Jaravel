package bench.http.bare;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对照组:裸 Spring MVC,同一个 JSON 端点。 */
@SpringBootApplication
@RestController
public class BareApp {

    public static void main(String[] args) {
        SpringApplication.run(BareApp.class, args);
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "app", "bare");
    }
}
