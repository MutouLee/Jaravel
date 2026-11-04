package io.jaravel.console.generators;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:policy PostPolicy --model=Post}(docs/08 §5.2)。
 *
 * <h2>★ 没有模型就不生成</h2>
 * <p>{@code @Policy(X.class)} 的那个 X 是它的注册键 —— 没有模型的 policy
 * 永远不会被调用。先按类名推断({@code PostPolicy} → {@code Post}),
 * 推不出来就**报错要 --model**,而不是生成一个注册不上的类。
 *
 * <p>这与 {@code make:migration} 猜不出表名时仍然生成**刚好相反**,
 * 值得说清:那里缺的是 stub 里一个一眼能看见的空字符串,
 * 这里缺的是「这个类会不会生效」—— 后者在页面上表现为
 * 「授权规则没生效」,与一个没填的注解之间没有线索。
 */
@Command(name = "make:policy", description = "新建一个授权策略")
public final class MakePolicy extends SimpleGenerator {

    @Option(names = "--model", description = "这个策略管哪个模型")
    String model;

    @Override protected String targetPackage() { return "app.policies"; }

    @Override protected String label() { return "策略"; }

    @Override
    protected void customise(Stub stub, String className) {
        stub.with("model", modelFor(className));
    }

    private String modelFor(String className) {
        if (model != null && !model.isBlank()) return Str.studly(model.trim());
        if (className.endsWith("Policy")) {
            return className.substring(0, className.length() - "Policy".length());
        }
        throw new ConsoleException("""
                %s 猜不出它管哪个模型。

                  加 --model=Post,或者把类名写成 PostPolicy。

                  ⚠ @Policy(X.class) 的那个 X 是注册键 —— 没有模型的策略
                  永远不会被调用,而症状是「授权规则没生效」。""".formatted(className));
    }
}
