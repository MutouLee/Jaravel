package net.dobeen.jaravel.console.generators;

import net.dobeen.jaravel.console.Argument;
import net.dobeen.jaravel.console.Command;

/**
 * {@code jaravel make:view posts.index} —— 新建一个 jblade 模板(docs/13 §2.2)。
 *
 * <h2>模板不是 Java,所以不走 {@link SimpleGenerator}</h2>
 * <p>落点在 {@code src/main/resources/views},扩展名是 {@code .jblade.html},
 * 而且名字是**点号分隔的视图名**而不是类名 —— 三处都与那套基类不同。
 * 硬塞进去的话,{@code targetPackage()} / {@code fileFor()} 都得开特例。
 *
 * <h2>三种写法收成同一个文件</h2>
 * <p>{@code posts.index}、{@code posts/index}、
 * {@code posts/index.jblade.html} 都是人在命令行上会自然写出来的形式。
 * 把它们当成三个不同的视图,只会生成三个只有细微差别的文件。
 */
@Command(name = "make:view", description = "新建一个 jblade 模板", offline = true)
public final class MakeView extends GeneratorCommand {

    @Argument(description = "模板名,如 posts.index")
    String name;

    @Override
    public int handle() {
        String path = viewPathOf(name);
        String view = path.replace('/', '.');
        String file = write(viewFile(path + ".jblade.html"),
                stub("view").with("view", view).render());
        success("模板:" + file);
        return 0;
    }

    /**
     * {@code posts.index} / {@code posts/index} / {@code posts.index.jblade.html}
     * → {@code posts/index}。
     *
     * <p>目录分隔接受点号与斜杠两种:视图名在 Java 代码里是
     * {@code "posts.index"}(点号),而文件路径是 {@code posts/index}
     * (斜杠),命令行上两种都会有人写。
     *
     * <p>非法片段(比如 {@code ..})直接报错 —— 不静默规整掉:
     * 一个叫 {@code ../secrets} 的视图会写到项目外面去。
     */
    static String viewPathOf(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.endsWith(".jblade.html")) {
            name = name.substring(0, name.length() - ".jblade.html".length());
        } else if (name.endsWith(".html")) {
            name = name.substring(0, name.length() - ".html".length());
        }
        return String.join("/", nameSegments(name, "模板名"));
    }
}
