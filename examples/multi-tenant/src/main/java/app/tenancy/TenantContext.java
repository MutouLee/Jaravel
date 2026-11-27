package app.tenancy;

import java.util.Optional;

import app.models.Tenant;
import net.dobeen.jaravel.core.context.ExecutionScope;

/**
 * 应用自己的租户上下文。
 *
 * <p>核心不提供 Tenancy 类(ADR-016)。这里用 {@link ExecutionScope}
 * 的自由属性绑定本次请求的租户 —— 作用域退出时属性自然消失,
 * 不会串到下一个请求。
 */
public final class TenantContext {

    private static final String KEY = "tenant";

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        ExecutionScope.current().put(KEY, tenant);
    }

    public static Optional<Tenant> current() {
        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.attribute(KEY, Tenant.class));
    }
}
