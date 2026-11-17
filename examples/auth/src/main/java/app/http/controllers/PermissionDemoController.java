package app.http.controllers;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * RBAC 示例(docs/08 §5.4)—— 只返回「通过了」。
 *
 * <p>真正的判定在 {@code role:admin} / {@code permission:publish-posts}
 * 中间件里;控制器能被执行就说明中间件放行了。
 */
@Component
public class PermissionDemoController {

    public Map<String, Object> roleOnly() {
        return Map.of("ok", true, "scope", "role:admin");
    }

    public Map<String, Object> publish() {
        return Map.of("ok", true, "scope", "permission:publish-posts");
    }
}
