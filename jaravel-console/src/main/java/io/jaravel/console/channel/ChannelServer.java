package io.jaravel.console.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

/**
 * 通道的服务端 —— 跑在**应用进程里**(docs/13 §1.2)。
 *
 * <pre>{@code
 * var server = new ChannelServer(registry, projectRoot).start();
 * …
 * server.close();
 * }</pre>
 *
 * <h2>每个连接一条命令,然后关掉</h2>
 * <p>不做连接复用:一次 {@code jaravel migrate} 就是一个短连接。
 * 复用要处理「上一条命令改了的静态状态」「谁来超时」这些事,
 * 而收益是省几毫秒的连接开销 —— 相对于命令本身毫无意义。
 *
 * <h2>★ 通道上的命令是不可交互的</h2>
 * <p>{@code confirm()} / {@code ask()} 在这里返回**默认值** ——
 * 与管道、CI、{@code Artisan.call} 完全一致的既有约定
 * (见 {@code ConsoleCommand.confirm} 的注释)。
 *
 * <p>要把交互转发过来,就得把客户端的 stdin 也接上这条流,
 * 还要处理「客户端按了 Ctrl-C 而服务端那条命令还在等输入」。
 * 那是后面的事;现在的行为是**安全的那一侧**:
 * {@code migrate} 那种「要确认吗」默认答不。
 *
 * <h2>虚拟线程</h2>
 * <p>一个连接一个虚拟线程。accept 循环自己也在虚拟线程上 ——
 * 它不能占着应用的启动线程,也不该是守护线程之外的东西:
 * 一个开发期的通道不该阻止 JVM 退出。
 */
public final class ChannelServer implements AutoCloseable {

    private final CommandRegistry registry;
    private final Path socket;
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocketChannel channel;
    private Thread acceptor;

    public ChannelServer(CommandRegistry registry, Path projectRoot) {
        this.registry = registry;
        this.socket = CommandChannel.socketPath(projectRoot);
    }

    public Path socketPath() {
        return socket;
    }

    public boolean isRunning() {
        return running.get();
    }

    public ChannelServer start() throws IOException {
        Files.createDirectories(socket.getParent());

        // ★ 先删掉可能残留的 socket 文件。
        //
        // 上一次进程被 kill -9 的话,文件还在而没人监听 —— bind 会报
        // 「地址已在使用」,而那条消息会把人送去查端口占用,
        // 实际上只是一个该删的文件。客户端那边也有对应的处理:
        // 连不上就当通道不存在(见 ChannelClient)。
        Files.deleteIfExists(socket);

        channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socket));
        restrictPermissions();
        running.set(true);

        acceptor = Thread.ofVirtual().name("jaravel-channel").start(this::acceptLoop);
        return this;
    }

    /**
     * 只有属主能读写。
     *
     * <p>这个通道按名字执行注册表里的任何命令 —— 在一台多用户的开发机上,
     * 一个所有人可写的 socket 等于把这个进程交出去。
     *
     * <p>Windows 上没有 POSIX 权限位,拿不到就跳过:那里的保护来自
     * 项目目录本身的 ACL(而 socket 就放在项目目录下,正是为此)。
     */
    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(socket, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows:走目录 ACL
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                SocketChannel connection = channel.accept();
                Thread.ofVirtual().start(() -> serve(connection));
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[jaravel] 命令通道 accept 失败:" + e.getMessage());
                }
                return;   // close() 会让 accept 抛异常,那是正常退出路径
            }
        }
    }

    private void serve(SocketChannel connection) {
        try (SocketChannel open = connection;
             var reader = new BufferedReader(new InputStreamReader(
                     Channels.newInputStream(open), StandardCharsets.UTF_8));
             var writer = new PrintWriter(new java.io.OutputStreamWriter(
                     Channels.newOutputStream(open), StandardCharsets.UTF_8), true)) {

            String version = reader.readLine();
            if (!CommandChannel.VERSION.equals(version)) {
                // 版本错位比协议错误更难查:双方都「按自己的版本」正常工作,
                // 直到某个字段的含义变了
                writer.println(CommandChannel.STDERR + CommandChannel.escape(
                        "命令通道的协议版本不一致:客户端 " + version
                        + ",服务端 " + CommandChannel.VERSION
                        + "。jaravel 与应用里的 jaravel-console 版本要一致。"));
                writer.println(String.valueOf(CommandChannel.EXIT) + 1);
                return;
            }

            String command = CommandChannel.unescape(reader.readLine());
            int count = Integer.parseInt(reader.readLine().trim());
            var args = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                args.add(CommandChannel.unescape(reader.readLine()));
            }

            var framed = new FramedIo(writer);
            int code;
            try {
                code = registry.run(command, List.copyOf(args), framed);
            } catch (RuntimeException | Error e) {
                // ★ 命令抛出来的异常要**作为输出发回客户端**。
                //
                // 不这么做的话,客户端只会看到「通道在收到退出码之前断开了」,
                // 而真正的消息(比如 Migrator 的「本次已回滚,数据库结构未变」)
                // 留在 dev server 那个终端里 —— 一个用户当时没在看的地方。
                //
                // 栈留在服务端:那是给框架开发者的,而命令的消息是给用户的。
                framed.writeError(describe(e));
                e.printStackTrace();
                code = 1;
            }
            writer.println(String.valueOf(CommandChannel.EXIT) + code);

        } catch (Exception e) {
            // 协议层自己的失败(读帧、版本号解析)—— 这时连不连得上都不确定
            System.err.println("[jaravel] 命令通道执行失败:" + e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // 关不掉也没什么能做的
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
            // 残留的 socket 文件由下一次 start() 删掉
        }
        if (acceptor != null) acceptor.interrupt();
    }

    /**
     * 异常 → 给用户看的那几行。
     *
     * <p>消息优先,类型名其次:{@code Migrator.MigrationException} 这种名字
     * 对用户没有信息量,而它的 message 写着「本次已回滚,下一步做什么」。
     * 但也不能只给 message —— 有些异常的 message 是 null。
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? e.getClass().getName()
                : message + System.lineSeparator()
                  + "  (" + e.getClass().getSimpleName() + ")";
    }

    /**
     * 把命令的输出打成帧发回去。
     *
     * <p>**逐段发而不是攒完再发**:{@code migrate} 跑十个迁移时,
     * 用户要的是一条一条出现,而不是最后一次性出来 ——
     * 后者在一个慢迁移上与「卡住了」无法区分。
     */
    private record FramedIo(PrintWriter writer) implements ConsoleIo {

        @Override public void write(String text) {
            writer.println(CommandChannel.STDOUT + CommandChannel.escape(text));
        }

        @Override public void writeError(String text) {
            writer.println(CommandChannel.STDERR + CommandChannel.escape(text));
        }

        /** 通道上不可交互 —— 与管道、CI 同一个约定,调用方会走默认值 */
        @Override public String readLine(String prompt) {
            return null;
        }

        @Override public String readSecret(String prompt) {
            return null;
        }

        /**
         * 颜色由**客户端**决定。
         *
         * <p>服务端在 dev server 那个终端里,它的 stdout 是不是终端
         * 与客户端那边毫无关系 —— 在这里判断的话,
         * 「我的终端支持颜色但 jaravel migrate 没有颜色」。
         *
         * <p>所以这里统一不带 ANSI 码,让客户端按自己的终端决定怎么显示。
         * 代价是通道上的输出没有颜色(进度条也因此走里程碑模式)。
         */
        @Override public boolean supportsColor() {
            return false;
        }
    }
}
