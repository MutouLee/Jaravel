package io.jaravel.console.generators;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.console.Command;
import io.jaravel.support.Clock;

/**
 * {@code jaravel install:auth}(docs/13 §2.1 / docs/08)。
 *
 * <p>在一个由 {@code jaravel new} 生成的项目里加认证:User 模型、
 * 用户表与重置 token 表、登录/注册/忘记密码/重置密码的控制器与模板、
 * 以及 config/auth.yml。路由放在独立的 {@code AuthRoutes} 里,不修改
 * 用户的 {@code WebRoutes}。
 */
@Command(name = "install:auth", description = "生成登录/注册/密码重置脚手架", offline = true)
public final class InstallAuthCommand extends GeneratorCommand {

    @Override
    public int handle() {
        Path root = projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("src/main/java"))) {
            warn("当前目录不像一个 Jaravel 项目(没有 src/main/java),仍然会生成到 " + root);
        }

        String migrationPackage = "database.migrations.m"
                + Clock.localDateTime().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HHmmss"));
        Map<String, String> values = Map.of("migrationPackage", migrationPackage);

        Map<Path, String> files = new LinkedHashMap<>();

        files.put(fileFor("app.models", "User"),
                Stub.of("auth.user", root).render());
        files.put(root.resolve(sourceRoot()).resolve(migrationPackage.replace('.', '/'))
                        .resolve("CreateAuthTables.java"),
                Stub.of("auth.migration", root).with(values).render());

        files.put(fileFor("app.http.requests", "LoginRequest"),
                Stub.of("auth.login.request", root).render());
        files.put(fileFor("app.http.requests", "RegisterRequest"),
                Stub.of("auth.register.request", root).render());
        files.put(fileFor("app.http.requests", "ResetPasswordRequest"),
                Stub.of("auth.reset.request", root).render());
        files.put(fileFor("app.http.controllers", "AuthController"),
                Stub.of("auth.controller", root).render());
        files.put(fileFor("routes", "AuthRoutes"),
                Stub.of("auth.routes", root).render());

        files.put(viewFile("auth/login.jblade.html"),
                Stub.of("auth.view.login", root).render());
        files.put(viewFile("auth/register.jblade.html"),
                Stub.of("auth.view.register", root).render());
        files.put(viewFile("auth/forgot.jblade.html"),
                Stub.of("auth.view.forgot", root).render());
        files.put(viewFile("auth/reset.jblade.html"),
                Stub.of("auth.view.reset", root).render());
        files.put(viewFile("dashboard.jblade.html"),
                Stub.of("auth.view.dashboard", root).render());

        Path configRoot = root.resolve("src/main/resources/config");
        files.put(configRoot.resolve("auth.yml"),
                Stub.of("auth.config", root).render());
        files.put(configRoot.resolve("session.yml"),
                Stub.of("auth.session", root).render());
        files.put(configRoot.resolve("hashing.yml"),
                Stub.of("auth.hashing", root).render());
        files.put(configRoot.resolve("mail.yml"),
                Stub.of("auth.mail", root).render());

        writeAll(files);

        success("认证脚手架已生成:");
        files.keySet().forEach(path -> line("  " + relative(path)));
        newLine();
        line("下一步:");
        line("  mvn spring-boot:run");
        line("  打开 http://localhost:8000/login");
        line("  邮件默认写到日志(MAIL_MAILER=log);忘记密码的重置链接可在日志里找到。");
        return 0;
    }

    private void writeAll(Map<Path, String> files) {
        ensureWritable(files.keySet().toArray(Path[]::new));
        files.forEach(this::write);
    }
}
