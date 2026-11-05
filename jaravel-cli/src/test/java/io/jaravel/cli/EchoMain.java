package io.jaravel.cli;

/**
 * 启动器真的会去起一个 JVM —— 这个类就是那个 JVM 要跑的东西。
 *
 * <p>它不打印断言用的东西:{@code spawn} 用的是 {@code inheritIO()},
 * 子进程的输出直接进了 surefire 的控制台,测试捕不到。
 * 所以**用退出码回话** —— 那正是启动器与 shell 之间唯一的契约,
 * 顺带也就验证了退出码真的传得回来。
 *
 * <ul>
 *   <li>7 —— 收到了预期的 {@code -Djaravel.command}</li>
 *   <li>8 —— 那个属性是空的或者不对</li>
 * </ul>
 */
public final class EchoMain {

    private EchoMain() {
    }

    public static void main(String[] args) {
        String command = System.getProperty("jaravel.command", "");
        System.out.println("[EchoMain] jaravel.command=" + command);
        System.exit(command.equals("migrate --force") ? 7 : 8);
    }
}
