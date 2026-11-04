package io.jaravel.console.generators;

import io.jaravel.console.Command;
import io.jaravel.console.Option;

/**
 * {@code jaravel make:test PostTest [--unit]}(docs/13 §2.2)。
 *
 * <p>写到 {@code src/test/java} 而不是 {@code src/main/java} ——
 * 这是唯一一条换源码根的生成命令。
 *
 * <h2>默认是功能测试</h2>
 * <p>与 Laravel 一致(那边 {@code make:test} 默认 Feature、
 * {@code --unit} 才是单元测试)。理由也一样:**功能测试是更该被默认
 * 鼓励的那一种** —— 它走真实管道(路由 → 中间件 → 绑定 → 控制器 →
 * 异常处理),而那一层恰恰是单元测试一律报绿、集成起来却坏掉的地方
 * (docs/10 里 examples/queue 那段记了 18 个这样的缺陷)。
 *
 * <p>包名用 {@code apptest} —— 仓库里已有的功能测试就在这个包下。
 * 不与被测类同包是故意的:同包会让测试能碰到包私有成员,
 * 而那条捷径一旦存在就会被用,于是测试开始依赖实现细节。
 */
@Command(name = "make:test", description = "新建一个测试", offline = true)
public final class MakeTest extends SimpleGenerator {

    @Option(names = "--unit", description = "生成单元测试(默认是功能测试)")
    boolean unit;

    @Override protected String sourceRoot() { return TEST_SOURCE_ROOT; }

    @Override protected String targetPackage() { return "apptest"; }

    @Override protected String stubName() { return unit ? "test.unit" : "test.feature"; }

    @Override protected String label() { return unit ? "单元测试" : "功能测试"; }
}
