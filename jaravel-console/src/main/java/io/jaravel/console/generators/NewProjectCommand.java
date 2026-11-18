package io.jaravel.console.generators;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;

/**
 * {@code jaravel new <name> [--preset=web|api|minimal]}(docs/13 §2.1)。
 *
 * <p>生成一个可直接 {@code mvn spring-boot:run} 的项目:标准 Maven 布局、
 * {@code app}/{@code routes} 约定包、config/*.yml、一个首页和一条烟雾测试。
 * 模板内嵌在 CLI 里,离线可用;{@code --preset} 只控制首页是 jblade 页面
 * 还是 JSON。
 */
@Command(name = "new", description = "新建一个 Jaravel 项目", offline = true)
public final class NewProjectCommand extends GeneratorCommand {

    @Argument(description = "项目目录名,如 blog")
    String name;

    @Option(names = {"--preset"}, description = "web(默认)/ api / minimal")
    String preset;

    @Option(names = {"--jaravel-version"}, description = "生成的 pom 引用的框架版本")
    String jaravelVersion;

    @Option(names = {"--spring-boot-version"}, description = "生成的 pom 引用的 Spring Boot 版本")
    String springBootVersion;

    @Override
    public int handle() {
        String dirName = directoryName();
        String mode = preset == null || preset.isBlank() ? "web" : preset.trim().toLowerCase();
        if (!mode.equals("web") && !mode.equals("api") && !mode.equals("minimal")) {
            throw new ConsoleException("""
                    --preset 只能是 web / api / minimal,当前是 "%s"。""".formatted(preset));
        }

        Path parent = projectRoot().toAbsolutePath().normalize();
        Path target = parent.resolve(dirName).normalize();
        if (!target.startsWith(parent)) {
            throw new ConsoleException("项目目录必须位于当前目录下:" + dirName);
        }
        if (Files.exists(target)) {
            boolean empty;
            try (var stream = Files.list(target)) {
                empty = stream.findAny().isEmpty();
            } catch (Exception e) {
                throw new ConsoleException("看不了目录 " + target + ":" + e.getMessage(), e);
            }
            if (!empty && !force) {
                throw new ConsoleException("""
                        目录 %s 已存在且不为空。

                          换一个名字,或确认后加 --force(只覆盖生成器写的文件,
                          不会删除你已有的其它文件)。""".formatted(dirName));
            }
        }

        String version = jaravelVersion == null || jaravelVersion.isBlank()
                ? detectedVersion() : jaravelVersion.trim();
        String bootVersion = springBootVersion == null || springBootVersion.isBlank()
                ? "3.5.16" : springBootVersion.trim();
        String appKey = KeyGenerateCommand.generate();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("artifactId", dirName);
        values.put("appName", dirName);
        values.put("jaravelVersion", version);
        values.put("springBootVersion", bootVersion);
        values.put("appKey", appKey);
        Map<Path, String> files = new LinkedHashMap<>();

        files.put(target.resolve("pom.xml"),
                Stub.of("project.pom", target).with(values).render());
        files.put(target.resolve("src/main/java/app/Application.java"),
                Stub.of("project.application", target).render());
        files.put(target.resolve("src/main/java/routes/WebRoutes.java"),
                Stub.of("project.routes", target).render());
        files.put(target.resolve("src/main/resources/application.yml"),
                Stub.of("project.application.yml", target).render());
        files.put(target.resolve("src/main/resources/config/app.yml"),
                Stub.of("project.config.app", target).with(values).render());
        files.put(target.resolve("src/main/resources/config/database.yml"),
                Stub.of("project.config.database", target).render());
        files.put(target.resolve("src/main/resources/config/jaravel.yml"),
                Stub.of("project.config.jaravel", target).render());
        files.put(target.resolve("src/test/java/apptest/HomeTest.java"),
                Stub.of("project.test", target).render());
        files.put(target.resolve(".env"),
                Stub.of("project.env", target).with(values).render());
        files.put(target.resolve(".env.example"),
                Stub.of("project.env.example", target).render());
        files.put(target.resolve(".gitignore"),
                Stub.of("project.gitignore", target).render());
        files.put(target.resolve("README.md"),
                Stub.of("project.readme", target).with(values).render());

        if (mode.equals("web")) {
            files.put(target.resolve("src/main/java/app/http/controllers/HomeController.java"),
                    Stub.of("project.controller.web", target).with(values).render());
            files.put(target.resolve("src/main/resources/views/home.jblade.html"),
                    Stub.of("project.view.home", target).with(values).render());
        } else {
            files.put(target.resolve("src/main/java/app/http/controllers/HomeController.java"),
                    Stub.of("project.controller.api", target).with(values).render());
        }

        writeAll(files);
        success("项目已创建:" + target);
        line("  cd " + dirName);
        line("  mvn spring-boot:run");
        if (!mode.equals("api") && !mode.equals("minimal")) {
            line("  打开 http://localhost:8000");
        }
        return 0;
    }

    private String directoryName() {
        String raw = name == null ? "" : name.trim();
        if (!raw.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")) {
            throw new ConsoleException("""
                    项目名不合法:"%s"

                      只允许字母开头,后接字母、数字、点、下划线与连字符,最长 64。
                      不要把路径写进来(目录在**当前目录**下创建)。""".formatted(raw));
        }
        return raw;
    }

    private void writeAll(Map<Path, String> files) {
        ensureWritable(files.keySet().toArray(Path[]::new));
        files.forEach(this::write);
    }

    static String detectedVersion() {
        String implementation = NewProjectCommand.class.getPackage().getImplementationVersion();
        return implementation == null || implementation.isBlank()
                ? "0.1.0-SNAPSHOT" : implementation;
    }
}
