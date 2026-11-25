package io.jaravel.console.generators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.console.Command;

/**
 * {@code jaravel install:vite}(docs/13 §2.1、docs/04 §1.9、ADR-020)。
 *
 * <p>生成五个文件:{@code vite.config.js}、{@code package.json}、
 * {@code resources/css/app.css}、{@code resources/js/app.js}、
 * {@code config/vite.yml}。
 *
 * <h2>★ 为什么是一条命令,而不是一个 npm 包</h2>
 * <p>Laravel 的 {@code laravel-vite-plugin} 把这些约定收进了一行
 * {@code plugins: [laravel([...])]}。Jaravel 没有 npm 发布渠道,
 * 加一条等于多一个要长期维护的流水线,还多一个「插件版本与 Java 侧
 * 不匹配」的故障源 —— 而要做的事只有「把 outDir 指过去 + 开 manifest」,
 * 十来行,生成一次就不用再碰(ADR-020 第 4 条)。
 *
 * <h2>它不碰 .gitignore</h2>
 * <p>生成器只写整个文件,不改用户已有的文件 —— 往 {@code .gitignore}
 * 里追一行看起来无害,但「命令偷偷改了我的文件」是更糟的性质。
 * 所以这里**检查**并提示那两行,不代劳。
 */
@Command(name = "install:vite", description = "生成 vite 配置与前端入口", offline = true)
public final class InstallViteCommand extends GeneratorCommand {

    /** 产物目录(相对项目根)—— 必须进 .gitignore */
    private static final String BUILD_OUTPUT = "src/main/resources/public/build";

    @Override
    public int handle() {
        Path root = projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("src/main/java"))) {
            warn("当前目录不像一个 Jaravel 项目(没有 src/main/java),仍然会生成到 " + root);
        }

        Map<Path, String> files = new LinkedHashMap<>();
        files.put(root.resolve("vite.config.js"),
                Stub.of("vite.config", root).render());
        files.put(root.resolve("package.json"),
                Stub.of("vite.package.json", root).render());
        files.put(root.resolve("resources/css/app.css"),
                Stub.of("vite.css", root).render());
        files.put(root.resolve("resources/js/app.js"),
                Stub.of("vite.js", root).render());
        files.put(root.resolve("src/main/resources/config/vite.yml"),
                Stub.of("vite.config.yml", root).render());

        ensureWritable(files.keySet().toArray(Path[]::new));
        files.forEach(this::write);

        success("前端脚手架已生成:");
        files.keySet().forEach(path -> line("  " + relative(path)));

        warnAboutGitignore(root);

        newLine();
        line("下一步:");
        line("  npm install");
        line("  npm run build                      # 产物 → " + BUILD_OUTPUT);
        line("");
        line("  在布局模板的 <head> 里加一行:");
        line("    @vite(['resources/css/app.css', 'resources/js/app.js'])");
        line("");
        line("  开发时(改一行就刷新):");
        line("    npm run dev");
        line("    并在 .env 里写 VITE_DEV_SERVER=http://localhost:5173");

        newLine();
        comment("""
                三件要知道的事:

                1. **mvn package 不会替你跑前端**(ADR-020 第 3 条)。
                   只改了 Java 的那次打包不该因为下载一整个 Node 慢上几十秒。
                   发布前的顺序是:
                     npm run build && mvn package
                   要一键的话给 pom 加一个 profile(frontend-maven-plugin),
                   自己打开 -Pfrontend。

                2. **路径出现在三处**:vite.config.js 的 input、manifest 的 key、
                   模板里的 @vite([...])。逐字相同才查得到 —— 改名要一起改。

                3. **build 模式的产物变更要重启应用**:类路径资源读的是
                   target/classes 里的副本,没有「改了就重新读」这回事。
                   真正的开发回路是 dev server,那条路上压根不读 manifest。""");
        return 0;
    }

    /**
     * 产物与 node_modules 在不在 .gitignore 里。
     *
     * <p>漏了的后果是把带哈希的产物与整个 node_modules 提交进仓库 ——
     * 而那不会报错,只会让每次构建都产生一堆无意义的 diff,
     * 直到某天有人发现仓库有几百兆。
     */
    private void warnAboutGitignore(Path root) {
        Path gitignore = root.resolve(".gitignore");
        String text = read(gitignore);
        if (text == null) return;        // 没有 .gitignore:不是这条命令该管的事

        var missing = new java.util.ArrayList<String>();
        if (!text.contains(BUILD_OUTPUT)) missing.add(BUILD_OUTPUT);
        if (!text.contains("node_modules")) missing.add("node_modules");
        if (missing.isEmpty()) return;

        newLine();
        warn("""
                .gitignore 里还缺这几行(这条命令不改你已有的文件,请自己加):
                %s""".formatted(missing.stream().map(line -> "  " + line)
                        .collect(java.util.stream.Collectors.joining("\n"))));
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;             // 读不了就不提示,不值得为此让命令失败
        }
    }
}
