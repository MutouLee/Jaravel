package net.dobeen.jaravel.console.generators;

import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.console.ConsoleException;
import net.dobeen.jaravel.console.Option;
import net.dobeen.jaravel.support.Str;

/**
 * {@code jaravel make:factory PostFactory --model=Post}(docs/14 §3)。
 *
 * <h2>★ 它生成一个一半的东西,所以必须把另一半说出来</h2>
 * <p>工厂这条链有两端:工厂类(这条命令生成)与模型上的
 * {@code @UseFactory(PostFactory.class)}(**用户自己加**)。
 * 命令不去改模型文件 —— 往用户写过的文件里插注解,一旦格式不合预期
 * 就会把他的代码改坏,而那是不可逆的。
 *
 * <p>但少了那行注解,生成的工厂**编译不过**(它继承的
 * {@code PostFactoryBase} 由 APT 生成,而 APT 只认那个注解)。
 * 所以成功输出里一定带上那一行,照抄即可。
 *
 * <h2>与 make:policy 同一套推断</h2>
 * <p>{@code PostFactory} → {@code Post};推不出来就报错要 {@code --model},
 * 而不是生成一个指向不存在模型的类。
 */
@Command(name = "make:factory", description = "新建一个模型工厂", offline = true)
public final class MakeFactory extends SimpleGenerator {

    @Option(names = "--model", description = "这个工厂造哪个模型")
    String model;

    @Override protected String targetPackage() { return "database.factories"; }

    @Override protected String label() { return "工厂"; }

    @Override
    protected void customise(Stub stub, String className) {
        String modelName = modelFor(className);
        stub.with("model", modelName)
            .with("var", Str.camel(modelName));
    }

    @Override
    public int handle() {
        int code = super.handle();

        String className = classNameOf(name);
        line("");
        comment("""
                还差一行 —— 在模型上声明它,APT 才会生成 %sBase:

                  @Model
                  @UseFactory(%s.%s.class)        ← 加这行
                  public class %s extends %sBase { … }

                然后 %s.factory() 就有了。"""
                .formatted(className, targetPackage(), className,
                           modelFor(className), modelFor(className), modelFor(className)));
        return code;
    }

    private String modelFor(String className) {
        if (model != null && !model.isBlank()) return Str.studly(model.trim());
        if (className.endsWith("Factory")) {
            return className.substring(0, className.length() - "Factory".length());
        }
        throw new ConsoleException("""
                %s 猜不出它造哪个模型。

                  加 --model=Post,或者把类名写成 PostFactory。

                  ⚠ 工厂要继承 APT 生成的 <工厂名>Base,而那个基类是从
                  **模型**上的 @UseFactory 生成的 —— 两边对不上就编译不过。"""
                .formatted(className));
    }
}
