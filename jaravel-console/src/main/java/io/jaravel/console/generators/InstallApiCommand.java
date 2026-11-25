package io.jaravel.console.generators;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.console.Command;
import io.jaravel.support.Clock;

/**
 * {@code jaravel install:api}(docs/13 §2.1、docs/08 §4、docs/12)。
 *
 * <p>在一个已有项目里加 API 脚手架:token 表的迁移、签发/撤销 token 的控制器、
 * 独立的 {@code ApiRoutes}、以及 {@code config/api.yml}(错误格式与幂等性)。
 *
 * <h2>★ 它要求先有 install:auth</h2>
 * <p>token 认证的 guard 需要一个 **user provider**
 * ({@code auth.providers.users.model}),而那是 {@code install:auth} 生成的
 * {@code config/auth.yml} 里的东西。没有它的话这条命令生成的控制器引用
 * {@code app.models.User}、路由引用 {@code auth:api},全都编译不过或启动即失败。
 *
 * <p>所以这里**先检查再生成**,并给出那一条命令。半套脚手架比没有脚手架糟:
 * 用户要先读懂生成的代码才知道缺了什么。
 *
 * <h2>只加,不改</h2>
 * <p>不动 {@code WebRoutes}、不动 {@code auth.yml}。token guard 已经在
 * {@code install:auth} 生成的 auth.yml 里了(那份文件里 {@code guards.api}
 * 就是 {@code driver: token}),所以这条命令不需要去改配置 ——
 * 往用户写过的 YAML 里插片段是不可逆的,而它能省下的只有三行。
 */
@Command(name = "install:api", description = "生成 API 脚手架(token 签发 + 路由 + api.yml)",
         offline = true)
public final class InstallApiCommand extends GeneratorCommand {

    @Override
    public int handle() {
        Path root = projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("src/main/java"))) {
            warn("当前目录不像一个 Jaravel 项目(没有 src/main/java),仍然会生成到 " + root);
        }

        Path authConfig = root.resolve("src/main/resources/config/auth.yml");
        if (!Files.isRegularFile(authConfig)) {
            error("""
                    没有 config/auth.yml —— API token 认证需要一个 user provider。

                      先跑:jaravel install:auth

                      理由:token guard 要按凭据找人、要 User 模型、要 users 表。
                      少了它,这里生成的控制器与路由编译不过(引用 app.models.User
                      与 auth:api),而那比没有脚手架更难办。""");
            return 1;
        }

        String migrationPackage = "database.migrations.m"
                + Clock.localDateTime().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HHmmss"));

        Map<Path, String> files = new LinkedHashMap<>();

        files.put(root.resolve(sourceRoot()).resolve(migrationPackage.replace('.', '/'))
                        .resolve("CreateApiTokensTable.java"),
                Stub.of("api.migration", root)
                        .with("migrationPackage", migrationPackage).render());

        files.put(fileFor("app.http.controllers", "ApiTokenController"),
                Stub.of("api.token.controller", root).render());
        files.put(fileFor("routes", "ApiRoutes"),
                Stub.of("api.routes", root).render());
        files.put(root.resolve("src/main/resources/config/api.yml"),
                Stub.of("api.config", root).render());

        ensureWritable(files.keySet().toArray(Path[]::new));
        files.forEach(this::write);

        success("API 脚手架已生成:");
        files.keySet().forEach(path -> line("  " + relative(path)));
        newLine();
        line("下一步:");
        line("  jaravel migrate                     # 建 personal_access_tokens 表");
        line("  mvn spring-boot:run");
        line("");
        line("  # 签发(用 install:auth 注册的账号)");
        line("  curl -X POST localhost:8000/api/tokens \\");
        line("       -H 'Accept: application/json' \\");
        line("       -d 'email=a@b.com&password=secret&name=cli'");
        line("");
        line("  # 带上它");
        line("  curl localhost:8000/api/me -H 'Authorization: Bearer <token>'");
        newLine();
        comment("""
                两件值得现在就知道的事:
                  · 明文 token 只在签发那一次返回 —— 库里存的是 SHA-256,取不回来;
                  · 过期的 token 不会自己消失。给调度器加一条:
                    schedule.call("清理过期 token", () ->
                            ((TokenGuard) Auth.guard("api")).purgeExpired()).daily();""");
        return 0;
    }
}
