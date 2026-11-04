package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:middleware EnsureSubscribed} —— HTTP 中间件(docs/03 §3) */
@Command(name = "make:middleware", description = "新建一个 HTTP 中间件")
public final class MakeMiddleware extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.http.middleware"; }

    @Override protected String label() { return "中间件"; }
}
