package io.jaravel.console.generators;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:model Post [-m] [-c] [-r] [--all]}(docs/13 §2.2)。
 *
 * <pre>
 * make:model Post            → app/models/Post.java
 * make:model Post -m         →   + database/migrations/…/CreatePostsTable.java
 * make:model Post -mcr       →   + app/http/controllers/PostController.java(资源控制器)
 * </pre>
 *
 * <p>合并的短开关({@code -mcr})是 artisan 的手感,ArgumentBinder 支持它。
 */
@Command(name = "make:model", description = "新建一个 Eloquent 模型", offline = true)
public final class MakeModel extends GeneratorCommand {

    @Argument(description = "模型类名,如 Post")
    String name;

    @Option(names = {"-m", "--migration"}, description = "同时生成建表迁移")
    boolean migration;

    @Option(names = {"-c", "--controller"}, description = "同时生成控制器")
    boolean controller;

    @Option(names = {"-r", "--resource"}, description = "控制器带 CRUD 七个方法")
    boolean resource;

    @Option(names = "--api", description = "控制器是 API 版(没有 create / edit)")
    boolean api;

    @Option(names = "--all", description = "等价于 -mcr")
    boolean all;

    @Option(names = "--table", description = "表名。默认由类名推断(Post → posts)")
    String table;

    @Override
    public int handle() {
        String className = classNameOf(name);
        String tableName = table != null && !table.isBlank() ? table : tableNameOf(className);

        String file = write(fileFor(MODELS_PACKAGE, className),
                stub("model")
                        .with("pkg", MODELS_PACKAGE)
                        .with("class", className)
                        .with("table", tableName)
                        .render());
        success("模型:" + file);

        if (migration || all) {
            call("make:migration", "create_" + tableName + "_table");
        }
        if (controller || all) {
            var args = new java.util.ArrayList<String>();
            args.add(className + "Controller");
            args.add("--model=" + className);
            if (resource || all) args.add("--resource");
            if (api) args.add("--api");
            call("make:controller", args.toArray(String[]::new));
        }
        return 0;
    }

    static final String MODELS_PACKAGE = "app.models";

    /**
     * {@code Post} → {@code posts};{@code PostCategory} → {@code post_categories}。
     *
     * <p>**只复数化最后一个词**:{@code Str.plural("post_category")} 直接作用在
     * 整串上会得到 {@code post_categorys} 之类的东西 ——
     * 英语的复数规则作用在词上,而 snake 之后那是一串词。
     */
    static String tableNameOf(String className) {
        String snake = Str.snake(className);
        int underscore = snake.lastIndexOf('_');
        return underscore < 0
                ? Str.plural(snake)
                : snake.substring(0, underscore + 1) + Str.plural(snake.substring(underscore + 1));
    }
}
