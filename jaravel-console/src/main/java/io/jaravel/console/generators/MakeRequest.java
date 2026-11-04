package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:request StorePostRequest} —— 表单请求(docs/07 §4) */
@Command(name = "make:request", description = "新建一个表单请求(FormRequest)", offline = true)
public final class MakeRequest extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.http.requests"; }

    @Override protected String label() { return "请求类"; }
}
