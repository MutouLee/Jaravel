package io.jaravel.console.channel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地命令通道的协议与地址(docs/13 §1.2)。
 *
 * <pre>
 * 终端 A:jaravel serve      → 应用进程开一个 unix socket
 * 终端 B:jaravel migrate    → 连上去,在那个**已经热起来的**进程里执行
 * </pre>
 *
 * <p>docs/13 称它是「本模块最重要的 DX 设计」,理由是在线命令
 * ({@code migrate} / {@code tinker} / {@code queue:*})的成本不在命令本身,
 * 而在**启动一个应用 JVM**。dev server 已经启动好了,复用它就是 0 开销。
 *
 * <h2>为什么是 unix socket,而且只有一种实现</h2>
 * <p>JDK 16 起 {@code AF_UNIX} 在 Windows 10 1803+ 上也支持
 * (本机实测:Windows 11 + JDK 21 可用)。所以三个平台一套实现 ——
 * 不需要「unix 用 socket、Windows 用命名管道」的两条分支,
 * 而那两条分支会各自腐坏。
 *
 * <p>比 TCP 回环更好的地方是**权限**:socket 是个文件,
 * 它受文件系统权限保护;而 127.0.0.1 上的端口,同一台机器上
 * 任何进程都能连 —— 对一个「执行任意命令」的通道来说差别很大。
 *
 * <h2>★ 这个通道就是远程代码执行,所以只在开发模式开</h2>
 * <p>它按名字执行注册表里的任何命令。这在开发机上正是要的,
 * 在生产环境里是个后门。所以装配那边只在非 production 时启动,
 * 并且把 socket 放在项目目录下(受项目目录的权限保护),
 * 而不是 {@code /tmp} 那种全局可写的地方。
 */
public final class CommandChannel {

    private CommandChannel() {
    }

    /** 协议版本。对不上就拒绝 —— 版本错位比协议错误更难查 */
    public static final String VERSION = "1";

    /** 项目下存放通道的目录 */
    public static final String DIRECTORY = ".jaravel";

    public static final String SOCKET_FILE = "dev.sock";

    public static Path socketPath(Path projectRoot) {
        return projectRoot.resolve(DIRECTORY).resolve(SOCKET_FILE);
    }

    // ================= 帧 =================

    /**
     * 行内的换行与反斜杠要转义。
     *
     * <p>不转义的话一个带换行的参数(或者输出里的换行)会把下一行
     * 当成新的帧 —— 那种错乱在协议层很难定位,
     * 因为每一帧单独看都是合法的。
     */
    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static String unescape(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n'  -> out.append('\n');
                case 'r'  -> out.append('\r');
                case '\\' -> out.append('\\');
                default   -> out.append(next);
            }
        }
        return out.toString();
    }

    /** 请求:版本、命令名、参数个数、各参数 */
    public static List<String> request(String command, List<String> args) {
        var lines = new ArrayList<String>();
        lines.add(VERSION);
        lines.add(escape(command));
        lines.add(String.valueOf(args.size()));
        args.forEach(arg -> lines.add(escape(arg)));
        return lines;
    }

    /**
     * 响应帧的首字符。
     *
     * <p>分开 stdout 与 stderr 不是洁癖:控制台的错误**刻意走 stderr**
     * (为了 {@code route:list --json | jq} 不被报错污染)。
     * 通道上合成一条流的话,客户端就没法把它们分回去了。
     */
    public static final char STDOUT = 'O';
    public static final char STDERR = 'E';
    public static final char EXIT   = 'X';
}
