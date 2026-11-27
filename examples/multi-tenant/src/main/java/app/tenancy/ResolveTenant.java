package app.tenancy;

import java.util.function.Function;

import app.models.Tenant;
import app.models.Tenant_;
import net.dobeen.jaravel.http.HttpException;
import net.dobeen.jaravel.http.Middleware;
import net.dobeen.jaravel.http.Request;
import net.dobeen.jaravel.http.Response;

/**
 * 从 {@code X-Tenant} 头解析租户。
 *
 * <p>真实项目里通常从域名、路径前缀或登录用户取;这里用一个显式头,
 * 让示例的隔离语义一眼可见。未知租户返回 404,缺头返回 400 ——
 * 两者都不是「悄悄查全部」。
 */
public class ResolveTenant implements Middleware {

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        String slug = request.header("X-Tenant").orElse("").trim();
        if (slug.isEmpty()) {
            throw new HttpException(400, "缺少 X-Tenant 头。");
        }

        Tenant tenant = Tenant.query().where(Tenant_.slug, slug).first()
                .orElseThrow(() -> new HttpException(404, "租户不存在:" + slug));

        TenantContext.set(tenant);
        return next.apply(request);
    }
}
