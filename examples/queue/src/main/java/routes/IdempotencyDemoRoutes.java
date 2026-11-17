package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.IdempotencyDemoController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 幂等性示例的路由。
 *
 * <p>**单独一个 RouteDefinition**,不放进 {@code WebRoutes}:
 * 那个文件是 parity 切片 04 的被测对象,往里加功能会让基准
 * 变成 apples-to-oranges(v0.4 踩过这个坑)。
 *
 * <p>`idempotent` 是框架自带的中间件别名;客户端带
 * {@code Idempotency-Key} 头。
 */
@Component
public class IdempotencyDemoRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.prefix("/api").middleware("api").group(api -> {
            api.post("/idempotency-demo", IdempotencyDemoController::create)
               .middleware("idempotent")
               .name("idempotency.demo");

            api.post("/idempotency-demo/fail", IdempotencyDemoController::failOnceThenSucceed)
               .middleware("idempotent")
               .name("idempotency.fail");
        });
    }
}
