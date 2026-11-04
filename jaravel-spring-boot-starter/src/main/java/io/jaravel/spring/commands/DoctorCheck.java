package io.jaravel.spring.commands;

import java.util.List;
import java.util.Optional;

/**
 * {@code jaravel doctor} 的一项检查(docs/13 §2.6)。
 *
 * <p>docs 称 doctor 是「Jaravel 的差异化加分项」,理由是:
 * **把框架使用中的常见陷阱变成可自动检测的项,而不是让用户踩坑后搜索**。
 *
 * <h2>每一项都要能回答三个问题</h2>
 * <ol>
 *   <li>**结论**:行不行({@link Severity});</li>
 *   <li>**后果**:不管它会怎样 —— 这是「⚠ 没开懒加载检测」与
 *       「✗ 生产环境开着 debug」的区别所在;</li>
 *   <li>**怎么办**:一条能照着做的修复步骤。</li>
 * </ol>
 *
 * <p>只给结论的检查项是没用的:「✗ 缓存驱动不对」留给用户的下一步
 * 还是去搜索,而那正是 doctor 要消除的那一步。
 *
 * <p>应用可以把自己的检查项做成 bean 加进来 ——
 * 一条「我们的 S3 桶配了吗」与框架的检查项同样属于「启动前确认」。
 */
public interface DoctorCheck {

    enum Severity {
        /** 没问题 */
        OK,
        /** 现在能跑,但有一个已知的坑在前面 */
        WARN,
        /** 这个配置是错的 —— 现在不报,以后会以别的面目出现 */
        FAIL
    }

    /** 检查项的名字,一行以内 */
    String name();

    Result run();

    /**
     * 一项检查的结果。
     *
     * @param severity 结论
     * @param summary  一行结论(会跟在 ✓ / ⚠ / ✗ 后面)
     * @param details  后果与修复步骤,每条一行(会以 → 开头缩进显示)
     * @param fix      可自动修复时,{@code doctor --fix} 要跑的那条命令
     */
    record Result(Severity severity, String summary, List<String> details,
                  Optional<String> fix) {

        public static Result ok(String summary) {
            return new Result(Severity.OK, summary, List.of(), Optional.empty());
        }

        public static Result warn(String summary, String... details) {
            return new Result(Severity.WARN, summary, List.of(details), Optional.empty());
        }

        public static Result fail(String summary, String... details) {
            return new Result(Severity.FAIL, summary, List.of(details), Optional.empty());
        }

        /** 可自动修复的失败项 —— {@code fixCommand} 是一条 Artisan 命令 */
        public Result fixableWith(String fixCommand) {
            return new Result(severity, summary, details, Optional.of(fixCommand));
        }
    }
}
