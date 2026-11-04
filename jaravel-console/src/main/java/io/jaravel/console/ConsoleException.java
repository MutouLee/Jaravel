package io.jaravel.console;

/**
 * 命令**用错了**,而不是跑失败了。
 *
 * <p>区分的意义在退出码与输出形态:用错了要打印用法并返回 1,
 * 跑失败了要打印堆栈。把两者混在一起的话,
 * 「参数拼错」会得到一屏与原因无关的栈帧。
 */
public class ConsoleException extends RuntimeException {

    public ConsoleException(String message) {
        super(message);
    }

    public ConsoleException(String message, Throwable cause) {
        super(message, cause);
    }
}
