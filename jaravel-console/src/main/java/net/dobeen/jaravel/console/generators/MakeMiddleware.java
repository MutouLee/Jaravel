package net.dobeen.jaravel.console.generators;

import net.dobeen.jaravel.console.Command;

/** {@code jaravel make:middleware EnsureSubscribed} —— HTTP 中间件(docs/03 §3) */
@Command(name = "make:middleware", description = "新建一个 HTTP 中间件", offline = true)
public final class MakeMiddleware extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.http.middleware"; }

    @Override protected String label() { return "中间件"; }
}
