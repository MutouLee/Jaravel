package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;

/**
 * {@code jaravel doctor}(docs/13 §2.6)。
 *
 * <pre>
 * ✓ APP_KEY 已设置
 * ✓ 数据库连接正常(sqlite)
 * ⚠ 懒加载检测:非生产环境但没开启
 *   → 开着的话访问未预加载的关联会直接报错,于是 N+1 在开发期就被发现
 *   → 修复:Eloquent.preventLazyLoading(true),或打开 app.debug
 * ✗ 3 个迁移执行后被改过:[…]
 *   → migrate 只看名字,所以它会说「没有待执行的迁移」
 * </pre>
 *
 * <h2>★ 有 ✗ 就退出码非 0</h2>
 * <p>这让它能当 CI 的一道闸:「部署前先 doctor」。
 * 退出码恒为 0 的检查命令只能靠人去读输出,而人会读过去。
 *
 * <p>⚠ 只有 ⚠ 的时候退出码是 0 —— 警告是「有个坑在前面」,不是
 * 「现在就不对」。把它也算失败的话,CI 会因为「开发环境没开懒加载检测」
 * 这种事变红,而那会训练人加 {@code || true}。
 */
@Command(name = "doctor", description = "健康检查:配置、连通性、常见误配")
public final class DoctorCommand extends ConsoleCommand {

    @Option(names = "--fix", description = "自动修复可修复项")
    boolean fix;

    private final List<DoctorCheck> checks;

    public DoctorCommand(List<DoctorCheck> checks) {
        this.checks = checks;
    }

    @Override
    public int handle() {
        int failures = 0;
        int warnings = 0;
        var fixable = new ArrayList<DoctorCheck.Result>();

        for (DoctorCheck check : checks) {
            DoctorCheck.Result result = safeRun(check);

            switch (result.severity()) {
                case OK -> success(check.name() + ":" + result.summary());
                case WARN -> {
                    warnings++;
                    warn(check.name() + ":" + result.summary());
                }
                case FAIL -> {
                    failures++;
                    // failure 而不是 error:这是**报告里的**一条坏消息,
                    // 要和它下面那几行 → 说明在同一条流上。
                    // 命令本身是否失败由退出码表达(见 ConsoleCommand.failure)
                    failure(check.name() + ":" + result.summary());
                }
            }
            if (result.severity() != DoctorCheck.Severity.OK) {
                result.details().forEach(detail -> line("  → " + detail));
                result.fix().ifPresent(command -> fixable.add(result));
            }
        }

        newLine();
        line("%d 项检查:%d 个问题,%d 个警告".formatted(
                checks.size(), failures, warnings));

        if (!fixable.isEmpty() && !fix) {
            line("其中 " + fixable.size() + " 项可以自动修复:doctor --fix");
        }
        if (fix && !fixable.isEmpty()) {
            newLine();
            failures -= applyFixes(fixable);
        }

        return failures > 0 ? 1 : 0;
    }

    /**
     * 跑修复。
     *
     * @return 成功修好的项数
     */
    private int applyFixes(List<DoctorCheck.Result> fixable) {
        int fixed = 0;
        for (DoctorCheck.Result result : fixable) {
            String command = result.fix().orElseThrow();
            line("执行 " + command + " …");
            if (call(command) == 0) {
                fixed++;
                continue;
            }
            // 修复失败不是「修好了」—— 退出码要继续反映那个问题。
            // 这里不中断:后面的修复项之间互不相关
            failure("修复没成功:" + command);
        }
        return fixed;
    }

    /**
     * 一项检查自己炸了,不该把整个 doctor 带下去。
     *
     * <p>doctor 的用途恰恰是「有些东西不对」的时候 —— 那时某项检查
     * 抛异常是**预期之内**的(数据库连不上、调度器没装)。
     * 让它冒出去的话,第一个坏掉的检查会遮住后面所有项,
     * 而用户要的是一张完整的清单。
     */
    private DoctorCheck.Result safeRun(DoctorCheck check) {
        try {
            return check.run();
        } catch (RuntimeException | Error e) {
            return DoctorCheck.Result.fail("检查本身失败了",
                    e.getMessage() == null ? e.getClass().getName() : e.getMessage(),
                    "这是框架的问题,不是你的配置 —— 完整堆栈:JARAVEL_DEBUG=1");
        }
    }
}
