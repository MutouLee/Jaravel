package io.jaravel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.Argument;
import io.jaravel.console.Artisan;
import io.jaravel.console.Command;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.ConsoleIo;
import io.jaravel.console.Option;
import io.jaravel.console.channel.ChannelServer;
import io.jaravel.console.channel.CommandChannel;

/**
 * 本地命令通道的端到端(docs/13 §1.2)。
 *
 * <p>**真的 unix socket、真的协议、真的两端** —— 服务端在同一个 JVM 里的
 * 另一个线程上。假掉 socket 就等于没测这件事:协议的转义、流式输出、
 * 退出码回传、残留文件的处理,全部都在那一层。
 *
 * <p>AF_UNIX 在 JDK 16+ 的 Windows 10 1803+ 上也支持(本机 Windows 11
 * 实测可用),所以三个平台一套实现、一套测试。
 */
class ChannelTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    /** 服务端注册的样例命令:带参数、两条流、非 0 退出码 */
    @Command(name = "migrate", description = "假的迁移命令", offline = false)
    static final class FakeMigrate extends ConsoleCommand {

        static final AtomicReference<String> SEEN = new AtomicReference<>();

        @Argument(required = false) String target;
        @Option(names = "--force") boolean force;
        @Option(names = "--fail") boolean fail;

        @Override public int handle() {
            SEEN.set(target + "|" + force);
            line("迁移 1/2");
            line("迁移 2/2 带\\反斜杠与「中文」");
            if (fail) {
                error("有一个迁移炸了");
                return 2;
            }
            // 通道上不可交互 —— 这里该拿到默认值
            line("确认=" + confirm("要继续吗?", false));
            return 0;
        }
    }

    private CommandRegistry serverRegistry() {
        return new CommandRegistry().register(FakeMigrate.class);
    }

    private record Result(int code, String out, String err) {}

    private Result cli(Path projectRoot, String... args) {
        var io = new ConsoleIo.Recording();
        int code = Jaravel.run(args, io, projectRoot);
        return new Result(code, io.output(), io.errors());
    }

    // ================= 端到端 =================

    @Test
    @DisplayName("★ 应用跑着时,在线命令走通道在那个进程里执行")
    void onlineCommandGoesThroughTheChannel(@TempDir Path tmp) throws Exception {
        FakeMigrate.SEEN.set(null);

        try (var server = new ChannelServer(serverRegistry(), tmp).start()) {
            assertThat(server.socketPath()).exists();

            var result = cli(tmp, "migrate", "posts", "--force");

            assertThat(result.code()).isZero();
            assertThat(FakeMigrate.SEEN.get())
                    .as("参数原样传到了服务端那一侧")
                    .isEqualTo("posts|true");
            assertThat(result.out())
                    .contains("迁移 1/2")
                    .as("转义要能原样还原:反斜杠与中文")
                    .contains("迁移 2/2 带\\反斜杠与「中文」");
        }
    }

    @Test
    @DisplayName("★ 退出码从服务端传回来 —— 「跑了但失败」与「没跑」不能混")
    void exitCodeComesBack(@TempDir Path tmp) throws Exception {
        try (var ignored = new ChannelServer(serverRegistry(), tmp).start()) {
            var result = cli(tmp, "migrate", "--fail");

            assertThat(result.code()).isEqualTo(2);
            assertThat(result.err())
                    .as("服务端的 stderr 要回到客户端的 stderr,而不是合成一条流")
                    .contains("有一个迁移炸了");
            assertThat(result.out()).doesNotContain("有一个迁移炸了");
        }
    }

    @Test
    @DisplayName("★ 通道上的命令不可交互,confirm 走默认值")
    void channelIsNotInteractive(@TempDir Path tmp) throws Exception {
        try (var ignored = new ChannelServer(serverRegistry(), tmp).start()) {
            assertThat(cli(tmp, "migrate").out())
                    .as("""
                            与管道、CI、Artisan.call 同一个约定。
                            转发 stdin 要先解决「客户端 Ctrl-C 而服务端还在等输入」,
                            而当前行为是安全的那一侧:migrate 的确认默认答不。""")
                    .contains("确认=false");
        }
    }

    @Test
    @DisplayName("没有通道时回落到那条说明,退出码 1")
    void withoutChannelFallsBack(@TempDir Path tmp) {
        var result = cli(tmp, "migrate");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("需要应用上下文").contains("先把应用跑起来");
    }

    @Test
    @DisplayName("★ 残留的 socket 文件当成「没有通道」,而不是报连接失败")
    void staleSocketFileIsIgnored(@TempDir Path tmp) throws Exception {
        Path socket = CommandChannel.socketPath(tmp);
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "上一次被 kill -9 之后留下的");

        var result = cli(tmp, "migrate");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err())
                .as("""
                        用户的问题是「我该怎么跑这条命令」,
                        而不是「连接为什么被拒绝」。""")
                .contains("需要应用上下文")
                .doesNotContain("Connection");
    }

    @Test
    @DisplayName("服务端起来时会删掉上一次残留的 socket 文件")
    void serverReplacesStaleSocket(@TempDir Path tmp) throws Exception {
        Path socket = CommandChannel.socketPath(tmp);
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "残留");

        try (var server = new ChannelServer(serverRegistry(), tmp).start()) {
            assertThat(server.isRunning())
                    .as("不删的话 bind 会报「地址已在使用」,"
                            + "而那条消息会把人送去查端口占用")
                    .isTrue();
            assertThat(cli(tmp, "migrate").code()).isZero();
        }
    }

    @Test
    @DisplayName("关掉之后 socket 文件不留下来")
    void closeRemovesTheSocket(@TempDir Path tmp) throws Exception {
        Path socket;
        try (var server = new ChannelServer(serverRegistry(), tmp).start()) {
            socket = server.socketPath();
            assertThat(socket).exists();
        }
        assertThat(socket).doesNotExist();
    }

    @Test
    @DisplayName("离线命令不走通道 —— 它在 CLI 这个进程里就跑完了")
    void offlineCommandsStayLocal(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));

        try (var ignored = new ChannelServer(serverRegistry(), tmp).start()) {
            assertThat(cli(tmp, "make:model", "Post").code()).isZero();
            assertThat(tmp.resolve("src/main/java/app/models/Post.java"))
                    .as("走通道的话文件会写到服务端那个进程的工作目录")
                    .exists();
        }
    }

    @Command(name = "db:wipe", description = "会抛异常的命令", offline = false)
    static final class Exploding extends ConsoleCommand {
        @Override public int handle() {
            line("开始干活");
            throw new IllegalStateException("""
                    这张表不存在。

                      本次已回滚,数据库结构未变。""");
        }
    }

    @Test
    @DisplayName("★ 命令抛异常时,客户端看到的是**消息**而不是「通道断开了」")
    void exceptionsTravelThroughTheChannel(@TempDir Path tmp) throws Exception {
        var registry = new CommandRegistry().register(Exploding.class);

        try (var ignored = new ChannelServer(registry, tmp).start()) {
            var result = cli(tmp, "db:wipe");

            assertThat(result.code()).isEqualTo(1);
            assertThat(result.out())
                    .as("炸之前的输出照样到了客户端")
                    .contains("开始干活");
            assertThat(result.err())
                    .as("""
                            不转发的话客户端只会看到「通道在收到退出码之前断开了」,
                            而真正的消息留在 dev server 那个终端里 ——
                            一个用户当时没在看的地方。""")
                    .contains("这张表不存在")
                    .contains("本次已回滚")
                    .as("类型名作为补充,而不是主角")
                    .contains("IllegalStateException");
        }
    }

    /** 一个会一直打印的命令 —— 模拟 queue:work */
    @Command(name = "queue:work", description = "一直跑的命令", offline = false)
    static final class Forever extends ConsoleCommand {
        static final java.util.concurrent.atomic.AtomicInteger TICKS =
                new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.CountDownLatch STOPPED =
                new java.util.concurrent.CountDownLatch(1);

        @Override public int handle() {
            try {
                for (int i = 0; i < 10_000; i++) {
                    line("tick " + TICKS.incrementAndGet());
                    Thread.sleep(5);
                }
                return 0;
            } catch (InterruptedException e) {
                return 1;
            } finally {
                STOPPED.countDown();
            }
        }
    }

    @Test
    @DisplayName("★ 客户端断开(Ctrl-C)之后,服务端那条命令会停下来")
    void clientDisconnectStopsTheCommand(@TempDir Path tmp) throws Exception {
        Forever.TICKS.set(0);
        var registry = new CommandRegistry().register(Forever.class);

        try (var server = new ChannelServer(registry, tmp).start()) {
            // 手写客户端:发完请求读几行就**直接关掉** —— 这就是 Ctrl-C
            try (var connection = java.nio.channels.SocketChannel.open(
                    java.net.StandardProtocolFamily.UNIX)) {
                connection.connect(java.net.UnixDomainSocketAddress.of(server.socketPath()));

                var writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                        java.nio.channels.Channels.newOutputStream(connection),
                        java.nio.charset.StandardCharsets.UTF_8), true);
                CommandChannel.request("queue:work", List.of()).forEach(writer::println);

                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        java.nio.channels.Channels.newInputStream(connection),
                        java.nio.charset.StandardCharsets.UTF_8));
                assertThat(reader.readLine()).startsWith(String.valueOf(CommandChannel.STDOUT));
            }

            assertThat(Forever.STOPPED.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("""
                            没有这个检测的话,一次 Ctrl-C 会在 dev server 里留下一个
                            永远跑着的 queue:work —— 而下一次 queue:work 会再留一个。
                            几次之后那个进程里有五个 worker 在抢任务,
                            而终端上什么都看不到。""")
                    .isTrue();

            int atStop = Forever.TICKS.get();
            Thread.sleep(100);
            assertThat(Forever.TICKS.get())
                    .as("真的停了,不是只是慢了")
                    .isEqualTo(atStop);
        }
    }

    // ================= 协议 =================

    @Test
    @DisplayName("转义能原样还原换行、回车与反斜杠")
    void escaping() {
        for (String raw : List.of("普通", "带\n换行", "带\\反斜杠", "带\r回车",
                                  "混合\\n看起来像转义的字面量", "")) {
            assertThat(CommandChannel.unescape(CommandChannel.escape(raw)))
                    .as("原文:%s", raw.replace("\n", "<LF>"))
                    .isEqualTo(raw);
        }
    }

    @Test
    @DisplayName("★ 带换行的参数不会把协议搞错位")
    void argumentsWithNewlines(@TempDir Path tmp) throws Exception {
        FakeMigrate.SEEN.set(null);

        try (var ignored = new ChannelServer(serverRegistry(), tmp).start()) {
            var result = cli(tmp, "migrate", "带\n换行的参数");

            assertThat(result.code()).isZero();
            assertThat(FakeMigrate.SEEN.get())
                    .as("""
                            不转义的话这个参数会把下一行当成新的帧 ——
                            那种错乱在协议层很难定位,因为每一帧单独看都是合法的。""")
                    .isEqualTo("带\n换行的参数|false");
        }
    }

    @Test
    @DisplayName("协议版本对不上时,报的是版本不一致而不是协议错误")
    void versionMismatch(@TempDir Path tmp) throws Exception {
        try (var server = new ChannelServer(serverRegistry(), tmp).start()) {
            try (var connection = java.nio.channels.SocketChannel.open(
                    java.net.StandardProtocolFamily.UNIX)) {
                connection.connect(java.net.UnixDomainSocketAddress.of(server.socketPath()));

                var writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                        java.nio.channels.Channels.newOutputStream(connection),
                        java.nio.charset.StandardCharsets.UTF_8), true);
                writer.println("99");
                writer.println("migrate");
                writer.println("0");

                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        java.nio.channels.Channels.newInputStream(connection),
                        java.nio.charset.StandardCharsets.UTF_8));

                String first = reader.readLine();
                assertThat(first).startsWith(String.valueOf(CommandChannel.STDERR));
                assertThat(CommandChannel.unescape(first.substring(1)))
                        .as("版本错位比协议错误更难查:双方都「按自己的版本」正常工作")
                        .contains("协议版本不一致")
                        .contains("版本要一致");
                assertThat(reader.readLine()).isEqualTo(CommandChannel.EXIT + "1");
            }
        }
    }
}
