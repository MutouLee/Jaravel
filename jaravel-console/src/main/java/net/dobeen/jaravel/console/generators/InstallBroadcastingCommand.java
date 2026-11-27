package net.dobeen.jaravel.console.generators;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.dobeen.jaravel.console.Command;

/**
 * {@code jaravel install:broadcasting}(docs/13 §2.1、docs/19)。
 *
 * <p>广播本身在 v0.6 就落地了(内置 WebSocket 端点、频道鉴权、presence),
 * 应用侧要写的只有两个文件:{@code config/broadcasting.yml} 与
 * {@code app/channels/Channels.java}。这条命令生成它们。
 *
 * <h2>为什么值得有一条命令</h2>
 * <p>不是为了省那两个文件,而是为了那两个文件里的**默认值**:
 * {@code origins: []}(仅同源)与「没注册的私有频道一律拒绝」。
 * 照着文档手写时,这两件事最容易写成「先打开再说」,
 * 而它们错掉的后果是别人的数据推进了你的浏览器 —— 不会有人报告这种缺陷。
 *
 * <h2>它不要求 install:auth,但频道鉴权需要登录</h2>
 * <p>生成的 {@code Channels} 用 {@code User} 做鉴权回调的参数类型 ——
 * 私有频道的含义就是「这个**用户**能不能订阅」。没有 User 模型时
 * 这里会提示先跑 {@code install:auth},但**仍然生成**:
 * 一个只推公开频道的应用(大盘、公告)是成立的用法,那时把 Channels
 * 里的私有频道示例删掉即可。
 */
@Command(name = "install:broadcasting", description = "生成广播配置与频道鉴权骨架",
         offline = true)
public final class InstallBroadcastingCommand extends GeneratorCommand {

    @Override
    public int handle() {
        Path root = projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("src/main/java"))) {
            warn("当前目录不像一个 Jaravel 项目(没有 src/main/java),仍然会生成到 " + root);
        }

        Map<Path, String> files = new LinkedHashMap<>();
        files.put(root.resolve("src/main/resources/config/broadcasting.yml"),
                Stub.of("broadcasting.config", root).render());
        files.put(fileFor("app.channels", "Channels"),
                Stub.of("broadcasting.channels", root).render());

        ensureWritable(files.keySet().toArray(Path[]::new));
        files.forEach(this::write);

        success("广播脚手架已生成:");
        files.keySet().forEach(path -> line("  " + relative(path)));

        if (!Files.isRegularFile(root.resolve("src/main/java/app/models/User.java"))) {
            newLine();
            warn("""
                    没看到 app/models/User.java —— 生成的 Channels 用 User 做鉴权参数。
                      要私有/presence 频道:先跑 jaravel install:auth;
                      只推公开频道(大盘、公告):把 Channels 里那段私有频道示例删掉。""");
        }

        newLine();
        line("下一步:");
        line("  mvn spring-boot:run");
        line("  前端连 ws://localhost:8000/app/jaravel(app-key 见 broadcasting.yml)");
        line("");
        line("  // 服务端推送:事件实现 ShouldBroadcast");
        line("  Event.dispatch(new OrderShipped(order));");
        newLine();
        comment("""
                两个默认值是刻意的(改之前先读一遍 broadcasting.yml 里的注释):
                  · origins: [] —— 仅同源。WebSocket 不走 CORS 那一层,
                    所以「允许谁连」必须显式列出来;
                  · 没在 Channels 里注册的私有频道**一律拒绝订阅** ——
                    忘了注册的正确行为是订阅不上,而不是谁都能订阅。""");
        return 0;
    }
}
