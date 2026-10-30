package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.AvatarController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 头像上传的路由 —— **v0.4 的 multipart 演示**。
 *
 * <h2>★ 为什么单独一个文件,而不是写在 WebRoutes 里</h2>
 * <p>因为 {@code WebRoutes.java} 是 **parity 基准的测量对象**
 * (`benchmark/laravel-parity` 的切片 03),而它对面的 Laravel
 * 参照实现里没有上传功能。
 *
 * <p>把三条上传路由写进去的后果是实测过的:切片 03 的比值从
 * **1.29(通过)变成 1.3015(超标)** —— 而那个退化并不来自
 * 「Jaravel 写同样的功能更费代码」,它来自**两侧在比不同的功能**。
 *
 * <p>那比「超标」本身更糟:一个不再 apples-to-apples 的基准会给出
 * 看起来精确的错数字,而 parity 是这个项目**唯一**用来量化
 * 「Laravel 级开发体验」这个论点的仪器。污染它等于把唯一的标尺弄弯。
 *
 * <p>框架本来就支持多个 {@code RouteDefinition} bean(存储的临时 URL
 * 路由也是一个),所以拆开没有任何代价。
 *
 * <p>一条顺带的好处:上传属于 v0.4,而切片 03 量的是 v0.3 的
 * 「加个登录 = 3 行」。分开之后这个示例的结构也更诚实了。
 *
 * <h2>为什么挂在 api 组</h2>
 * <p>multipart 的 CSRF 令牌要作为一个表单字段一起传,而
 * {@code UploadFlowTest} 要验证的是文件解析与安全检查,不是 CSRF
 * (那一层在 {@code AuthFlowTest} 里已经有测试)。
 */
@Component
public class UploadRoutes implements RouteDefinition {

    /**
     * 排在 {@link WebRoutes} 之后。
     *
     * <p>这里只是为了让 {@code route:list} 的输出稳定 ——
     * 两组路由没有路径重叠,顺序不影响匹配。
     */
    @Override
    public int order() {
        return 10;
    }

    @Override
    public void define(Router r) {
        r.prefix("/api").middleware("api", "auth:api").group(api -> {
            api.post("/avatar", AvatarController::store);
            api.post("/avatar/multi", AvatarController::storeMany);
            api.post("/avatar/public", AvatarController::storePublic);
        });
    }
}
