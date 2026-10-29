package routes;

import app.http.controllers.InvoiceController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;
import org.springframework.stereotype.Component;

/**
 * 队列示例的路由。
 *
 * <p>全是 api 组:这个示例没有会话与表单,只有 JSON 接口 ——
 * 队列的使用者通常就是这样的后台接口。
 *
 * <p>{@code {invoice}} 用的是**隐式模型绑定**(v0.4 之前补的那块):
 * 控制器方法直接收 {@code Invoice invoice},框架在
 * {@code SubstituteBindings} 中间件里查出来,并把结果缓存在请求作用域 ——
 * 于是授权与业务逻辑看到的是**同一行数据**。
 */
@Component
public class WebRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.prefix("/api").middleware("api").group(api -> {

            api.post("/invoices", InvoiceController::store).name("invoices.store");
            api.get("/invoices/{invoice}", InvoiceController::show).name("invoices.show");

            // 四种投递形式各一个端点
            api.post("/invoices/{invoice}/remind", InvoiceController::remind)
               .name("invoices.remind");
            api.post("/invoices/{invoice}/send-now", InvoiceController::sendNow)
               .name("invoices.sendNow");
            api.post("/invoices/{invoice}/pipeline", InvoiceController::pipeline)
               .name("invoices.pipeline");

            api.post("/statements", InvoiceController::statements).name("statements");

            api.get("/batches/{batch}", InvoiceController::batchStatus)
               .name("batches.show");
            api.delete("/batches/{batch}", InvoiceController::cancelBatch)
               .name("batches.cancel");
        });
    }
}
