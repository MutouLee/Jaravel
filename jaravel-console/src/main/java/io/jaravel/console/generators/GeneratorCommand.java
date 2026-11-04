package io.jaravel.console.generators;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;

/**
 * 所有 {@code make:*} 的基类:算路径、查重、写文件。
 *
 * <h2>项目布局(docs/16 §4,方案 C)</h2>
 * <pre>
 * src/main/java/app/models/Post.java            package app.models;
 * src/main/java/app/http/controllers/…          package app.http.controllers;
 * src/main/java/database/migrations/…           package database.migrations.…;
 * </pre>
 * 即标准 Maven 布局 + 短包名。包名里没有组织前缀 ——
 * 应用代码不会被当作库发布。
 */
public abstract class GeneratorCommand extends ConsoleCommand {

    /** 源码根。以项目目录为基准,可被测试换掉 */
    public static final String SOURCE_ROOT = "src/main/java";

    @Option(names = {"-f", "--force"}, description = "覆盖已存在的文件")
    protected boolean force;

    private Path projectRoot = Path.of("");

    /** 测试与 {@code jaravel new} 用:把生成目标指到别处 */
    public void inProject(Path root) {
        this.projectRoot = root;
    }

    protected Path projectRoot() {
        return projectRoot;
    }

    protected Stub stub(String name) {
        return Stub.of(name, projectRoot);
    }

    /** 测试源码根。{@code make:test} 覆盖它 */
    public static final String TEST_SOURCE_ROOT = "src/test/java";

    protected String sourceRoot() {
        return SOURCE_ROOT;
    }

    /** {@code app.models} + {@code Post} → {@code src/main/java/app/models/Post.java} */
    protected Path fileFor(String packageName, String className) {
        return projectRoot.resolve(sourceRoot())
                .resolve(packageName.replace('.', '/'))
                .resolve(className + ".java");
    }

    /**
     * 写文件。返回相对项目根的路径,用于输出。
     *
     * <h2>★ 已存在就报错,除非 --force</h2>
     * <p>覆盖是不可逆的:用户在 {@code Post.java} 里写了两小时的代码,
     * 一句 {@code make:model Post} 就能让它变回一个空模型。
     * 而这条命令最常见的使用情形恰恰是「我是不是已经建过了?」——
     * 手一快就按了回车。
     *
     * <p>所以默认拒绝,并在消息里把 {@code --force} 说出来 ——
     * 真要覆盖的人不用去翻文档。
     */
    protected String write(Path file, String contents) {
        if (Files.exists(file) && !force) {
            throw new ConsoleException("""
                    %s 已经存在。

                      要覆盖它:加 --force(⚠ 文件里现有的内容会没了)""".formatted(relative(file)));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConsoleException("写不了 " + relative(file) + ":" + e.getMessage(), e);
        }
        return relative(file);
    }

    protected String relative(Path file) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        return target.startsWith(root)
                ? root.relativize(target).toString().replace('\\', '/')
                : target.toString().replace('\\', '/');
    }

    /**
     * 把用户给的名字规整成类名。
     *
     * <p>{@code make:model post} / {@code make:model Post.java} /
     * {@code make:model blog/Post} 都该成 {@code Post} ——
     * 这三种写法在命令行上都很自然(补全会补出 {@code .java},
     * 从路径复制过来会带目录),而把它们当成不同的类名
     * 只会生成三个奇怪的文件。
     */
    protected String classNameOf(String raw) {
        String name = raw.trim().replace('\\', '/');
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty()) throw new ConsoleException("名字不能为空");
        return io.jaravel.support.Str.studly(name);
    }
}
