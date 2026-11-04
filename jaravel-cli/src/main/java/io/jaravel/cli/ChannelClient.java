package io.jaravel.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.jaravel.console.ConsoleIo;
import io.jaravel.console.channel.CommandChannel;

/**
 * 通道的客户端 —— 把在线命令交给已经跑着的应用进程(docs/13 §1.2)。
 *
 * <h2>★ 连不上就当通道不存在</h2>
 * <p>socket 文件可能是**残留**的:上一次 dev server 被 kill -9 之后,
 * 文件还在而没人监听。那时 connect 会失败,而这里把它与「没开 dev server」
 * 同等对待 —— 都回落到「这条命令现在跑不了」的说明。
 *
 * <p>反过来做(把连接失败当成错误报出去)会给出一条
 * 「连接被拒绝」的消息,而用户的问题是「我该怎么跑这条命令」。
 */
final class ChannelClient {

    private ChannelClient() {
    }

    /**
     * 试着通过通道执行。
     *
     * <p>返回 {@code empty} 表示**通道不可用**(没开 / 残留 / 连不上),
     * 调用方该走它的回落路径。返回退出码表示命令真的执行了 ——
     * 包括执行失败,那是命令自己的退出码。
     *
     * <p>这个区分很重要:「跑了但失败」与「没跑」对用户的下一步
     * 完全不同,而两者都是非 0。
     */
    static Optional<Integer> tryRun(Path projectRoot, String command, List<String> args,
                                    ConsoleIo io) {
        Path socket = CommandChannel.socketPath(projectRoot);
        if (!Files.exists(socket)) return Optional.empty();

        try (SocketChannel connection = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            connection.connect(UnixDomainSocketAddress.of(socket));
            return Optional.of(exchange(connection, command, args, io));
        } catch (IOException e) {
            // 残留的 socket 文件,或者 server 正在关 —— 当成没有通道
            return Optional.empty();
        }
    }

    private static int exchange(SocketChannel connection, String command, List<String> args,
                                ConsoleIo io) throws IOException {
        var writer = new PrintWriter(new java.io.OutputStreamWriter(
                Channels.newOutputStream(connection), StandardCharsets.UTF_8), true);
        var reader = new BufferedReader(new InputStreamReader(
                Channels.newInputStream(connection), StandardCharsets.UTF_8));

        CommandChannel.request(command, args).forEach(writer::println);

        int code = 1;
        boolean sawExit = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            char kind = line.charAt(0);
            String payload = CommandChannel.unescape(line.substring(1));

            switch (kind) {
                case CommandChannel.STDOUT -> io.write(payload);
                case CommandChannel.STDERR -> io.writeError(payload);
                case CommandChannel.EXIT -> {
                    code = Integer.parseInt(payload.trim());
                    sawExit = true;
                }
                default -> io.writeError("[jaravel] 通道上收到无法识别的帧:" + line + "\n");
            }
        }

        if (!sawExit) {
            // 连接断了但没收到退出码 —— 命令可能跑了一半(dev server 重启、
            // 或者那条命令把进程搞崩了)。**不能当成成功**:
            // 退出码 0 会让脚本继续往下走
            io.writeError("""
                    [jaravel] 命令通道在收到退出码之前断开了。

                      那条命令可能执行了一部分。去 dev server 的终端看日志 ——
                      真正的原因在那边(比如应用重启、或者命令本身让进程崩了)。
                    """);
            return 1;
        }
        return code;
    }
}
