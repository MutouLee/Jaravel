package net.dobeen.jaravel.console;

import java.nio.file.Path;

/**
 * 命令需要知道**项目根在哪**(docs/13 §1)。
 *
 * <p>两种命令需要它:
 * <ul>
 *   <li>{@link net.dobeen.jaravel.console.generators.GeneratorCommand} —— 文件要生成到
 *       {@code src/main/java},而不是进程的当前目录(两者在测试里不同);</li>
 *   <li>{@code test} —— 它要在一个真实的 Maven 项目里跑 {@code mvn test}。</li>
 * </ul>
 *
 * <h2>为什么抽成接口</h2>
 * <p>此前 CLI 与 starter 各自写了一遍 {@code instanceof GeneratorCommand}。
 * 第二个需要项目根的命令出现时,漏改一处的症状是「CLI 里跑对了、应用里跑错了」,
 * 而两处代码各自看都是对的。接口让「需要项目根」这件事只有一个名字。
 */
public interface ProjectAware {

    /** 由 CLI / starter 在每次执行前注入。实现不应在构造器里做任何事。 */
    void inProject(Path projectRoot);
}
