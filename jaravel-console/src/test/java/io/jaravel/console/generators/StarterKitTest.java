package io.jaravel.console.generators;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

@DisplayName("jaravel new + install:auth(v1.0)")
class StarterKitTest {

    private CommandRegistry registry(Path cwd, Class<?> command) {
        var registry = new CommandRegistry();
        registry.register((Class<? extends io.jaravel.console.ConsoleCommand>) command);
        registry.configure(c -> {
            if (c instanceof io.jaravel.console.ProjectAware aware) aware.inProject(cwd);
        });
        return registry;
    }

    @Test
    @DisplayName("new 生成可构建的项目骨架与一条烟雾测试")
    void newProject(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        int code = registry(tmp, NewProjectCommand.class).run(
                "new", List.of("demo", "--preset=web",
                        "--jaravel-version=0.1.0-SNAPSHOT"), io);

        assertThat(code).isZero();
        Path root = tmp.resolve("demo");
        assertThat(root.resolve("pom.xml")).exists();
        assertThat(root.resolve("src/main/java/app/Application.java")).exists();
        assertThat(root.resolve("src/main/java/routes/WebRoutes.java")).exists();
        assertThat(root.resolve("src/main/resources/views/home.jblade.html")).exists();
        assertThat(root.resolve(".env")).exists();
        assertThat(Files.readString(root.resolve("pom.xml")))
                .contains("jaravel-spring-boot-starter")
                .contains("jaravel-maven-plugin")
                .contains("<jaravel.version>0.1.0-SNAPSHOT</jaravel.version>");
        assertThat(Files.readString(root.resolve("src/main/resources/views/home.jblade.html")))
                .contains("{{ name }}")
                .doesNotContain("\\{{");
    }

    @Test
    @DisplayName("install:auth 生成模型、路由、控制器、模板与迁移")
    void installAuth(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        var registry = registry(tmp, NewProjectCommand.class);
        assertThat(registry.run("new", List.of("demo"), io)).isZero();

        Path root = tmp.resolve("demo");
        int code = registry(root, InstallAuthCommand.class).run("install:auth", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("src/main/java/app/models/User.java")).exists();
        assertThat(root.resolve("src/main/java/app/http/controllers/AuthController.java")).exists();
        assertThat(root.resolve("src/main/java/routes/AuthRoutes.java")).exists();
        assertThat(root.resolve("src/main/resources/views/auth/login.jblade.html")).exists();
        assertThat(root.resolve("src/main/resources/views/dashboard.jblade.html")).exists();
        assertThat(root.resolve("src/main/resources/config/auth.yml")).exists();
        assertThat(Files.readString(root.resolve("src/main/java/app/models/User.java")))
                .contains("@AuthModel")
                .contains("@Fillable");
        assertThat(Files.readString(root.resolve("src/main/java/app/http/controllers/AuthController.java")))
                .contains("Auth.attempt")
                .contains("Password.sendResetLink");
        assertThat(Files.readString(root.resolve("src/main/resources/views/auth/login.jblade.html")))
                .contains("{{ csrf }}")
                .doesNotContain("\\{{");
    }

    @Test
    @DisplayName("新建目录已存在且非空时拒绝覆盖")
    void refusesNonEmptyTarget(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Files.writeString(tmp.resolve("demo/keep.txt"), "keep");

        var io = new ConsoleIo.Recording();
        int code = registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io);

        assertThat(code).isNotZero();
        assertThat(Files.readString(tmp.resolve("demo/keep.txt"))).isEqualTo("keep");
    }
}
