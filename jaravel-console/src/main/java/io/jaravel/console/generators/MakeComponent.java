package io.jaravel.console.generators;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:component Alert} —— jblade 类组件(类 + 模板)。
 *
 * <p>docs/13 §2.2 把它与 {@code make:view} 一起留到了组件系统落地之后:
 * 生成的是 jblade 模板而不是 Java,落点与后处理都不同。
 *
 * <h2>为什么是类组件而不是匿名组件</h2>
 * <p>与 Laravel 的 {@code make:component} 一致:类 + 模板。匿名组件只要
 * 一个模板文件,想加逻辑就得改成一个类 —— 而那时模板的 {@code @params}
 * 也要跟着改。默认生成完整的那个形态,省掉这次迁移。
 *
 * <h2>两个文件要么都生成、要么都不生成</h2>
 * <p>类在、模板不在的话,{@code <x-alert />} 按约定找不到视图;
 * 模板在、类不在的话,模板的 {@code @params} 引用一个不存在的类。
 * 两种都只有一半。所以先 {@code ensureWritable(...)} 查两个路径再写 ——
 * 「已存在」这条路径上不会留下半个组件。
 */
@Command(name = "make:component", description = "新建一个 jblade 类组件(类 + 模板)", offline = true)
public final class MakeComponent extends GeneratorCommand {

    @Argument(description = "组件名,如 Alert 或 Forms.Input")
    String name;

    @Override
    public int handle() {
        ComponentName component = ComponentName.of(name);
        Path classFile = fileFor(component.pkg(), component.className());
        Path viewFile = viewFile(component.viewPath() + ".jblade.html");

        ensureWritable(classFile, viewFile);

        String writtenClass = write(classFile, stub("component")
                .with("pkg", component.pkg())
                .with("class", component.className())
                .with("view", component.viewName())
                .with("tag", component.tag())
                .render());

        // 模板里的 {{ slot }} 用反斜杠转义:Stub 只认 {{ 变量 }},
        // 不转的话它会被当成一个没给值的变量而报错。
        String writtenView = write(viewFile, stub("component.view")
                .with("pkg", component.pkg())
                .with("class", component.className())
                .with("tag", component.tag())
                .render());

        success("组件类:" + writtenClass);
        success("组件模板:" + writtenView);
        return 0;
    }

    /**
     * 组件名的三段投影:Java 类、视图路径、调用标签。
     *
     * <table>
     *   <tr><th>输入</th><th>包 / 类</th><th>视图</th><th>标签</th></tr>
     *   <tr><td>{@code Alert}</td><td>{@code app.http.components.Alert}</td>
     *       <td>{@code components.alert}</td><td>{@code <x-alert />}</td></tr>
     *   <tr><td>{@code Forms.Input}</td><td>{@code app.http.components.forms.Input}</td>
     *       <td>{@code components.forms.input}</td><td>{@code <x-forms.input />}</td></tr>
     *   <tr><td>{@code user-avatar}</td><td>{@code app.http.components.UserAvatar}</td>
     *       <td>{@code components.user-avatar}</td><td>{@code <x-user-avatar />}</td></tr>
     * </table>
     *
     * <p>点号嵌套与 Blade 的 {@code <x-forms.input>} 对应(转写器把
     * {@code x-forms.input} 解析成 {@code components/forms/input.jblade.html});
     * 包名按 Java 惯例小写,类名按 Java 惯例大写。
     */
    record ComponentName(String pkg, String className, String viewName,
                         String viewPath, String tag) {

        static ComponentName of(String raw) {
            String name = raw == null ? "" : raw.trim();
            if (name.endsWith(".java")) {
                name = name.substring(0, name.length() - ".java".length());
            }
            List<String> segments = nameSegments(name, "组件名");

            List<String> kebab = segments.stream().map(Str::kebab).toList();
            String className = Str.studly(segments.get(segments.size() - 1));

            List<String> parents = segments.subList(0, segments.size() - 1);
            String subPackage = parents.stream().map(Str::snake).collect(Collectors.joining("."));
            String pkg = subPackage.isEmpty()
                    ? "app.http.components"
                    : "app.http.components." + subPackage;

            return new ComponentName(
                    pkg,
                    className,
                    "components." + String.join(".", kebab),
                    "components/" + String.join("/", kebab),
                    String.join(".", kebab));
        }
    }
}
