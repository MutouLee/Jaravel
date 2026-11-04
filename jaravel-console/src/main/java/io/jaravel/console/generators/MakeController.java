package io.jaravel.console.generators;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:controller PostController [--resource] [--api] [--model=Post]}
 * (docs/13 §2.2)。
 */
@Command(name = "make:controller", description = "新建一个控制器", offline = true)
public final class MakeController extends GeneratorCommand {

    @Argument(description = "控制器类名,如 PostController")
    String name;

    @Option(names = {"-r", "--resource"}, description = "带 CRUD 七个方法")
    boolean resource;

    @Option(names = "--api", description = "API 版:没有 create / edit")
    boolean api;

    @Option(names = "--model", description = "关联的模型,用于推断路由段与注释")
    String model;

    @Override
    public int handle() {
        String className = classNameOf(name);
        String modelName = model != null && !model.isBlank()
                ? Str.studly(model.trim())
                : modelFrom(className);

        // --api 本身就意味着资源控制器:写了 --api 还得再写 --resource 是个
        // 一定会被漏掉的组合,而漏掉的结果是生成了一个只有 index 的空壳。
        String variant = api ? "controller.api" : resource ? "controller.resource" : "controller";

        String file = write(fileFor(CONTROLLERS_PACKAGE, className),
                stub(variant)
                        .with("pkg", CONTROLLERS_PACKAGE)
                        .with("class", className)
                        .with("model", modelName)
                        .with("route", routeSegmentOf(modelName))
                        .render());

        success("控制器:" + file);
        return 0;
    }

    static final String CONTROLLERS_PACKAGE = "app.http.controllers";

    /** {@code PostController} → {@code Post};不带 Controller 后缀时原样用 */
    private static String modelFrom(String className) {
        return className.endsWith("Controller")
                ? className.substring(0, className.length() - "Controller".length())
                : className;
    }

    /** {@code Post} → {@code posts};{@code PostCategory} → {@code post-categories} */
    static String routeSegmentOf(String modelName) {
        String table = MakeModel.tableNameOf(modelName);
        return table.replace('_', '-');
    }
}
