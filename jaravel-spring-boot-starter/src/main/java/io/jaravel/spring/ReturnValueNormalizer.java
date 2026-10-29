package io.jaravel.spring;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.jaravel.core.container.Container;
import io.jaravel.http.Redirect;
import io.jaravel.http.Response;
import io.jaravel.http.View;
import io.jaravel.support.Coll;

/**
 * 控制器返回值 → {@link Response} 的归一化(docs/03 §4.1)。
 *
 * <p>让控制器可以自由返回 {@code View} / {@code Response} / {@code Redirect} /
 * 任意 POJO / {@code void},而不必每处都包一层 ——
 * 这是 Laravel 控制器读起来干净的一个原因。
 */
final class ReturnValueNormalizer {

    private ReturnValueNormalizer() {}

    static Response normalize(Object result, Container container) {
        if (result == null)                 return Response.noContent();
        if (result instanceof Response r)   return r;        // 含 Redirect(是子类)
        if (result instanceof View v)       return v.toResponse();

        if (result instanceof CompletableFuture<?> f) {
            // 虚拟线程下直接 join,不需要异步回调
            return normalize(f.join(), container);
        }

        if (result instanceof String s) {
            // 以 < 开头视为 HTML 片段,否则纯文本 —— 与 Laravel 的行为一致
            return s.stripLeading().startsWith("<") ? Response.html(s) : Response.text(s);
        }

        if (result instanceof Coll<?> c)    return Response.json(c.toList());
        if (result instanceof Number || result instanceof Boolean) {
            return Response.json(result);
        }

        // 其余一切交给 JSON 序列化(Map / record / POJO / List)
        return Response.json(result);
    }
}
