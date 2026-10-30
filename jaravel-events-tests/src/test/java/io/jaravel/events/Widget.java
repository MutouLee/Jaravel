package io.jaravel.events;

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/**
 * 测试用模型。
 *
 * <p>**顶层类而不是嵌套类** —— {@code @Model} 的处理器不支持嵌套模型
 * (它生成的基类按简单名引用模型,嵌套时那个名字解析不到)。
 *
 * <p>这个限制在实践中无关紧要:模型在真实应用里一律是顶层类。
 * 处理器现在会对嵌套的 {@code @Model} 报一条明确的编译错误 ——
 * 之前它生成的是无法编译的代码,而那个报错指向生成的文件,
 * 与真正的原因(模型是嵌套类)之间没有任何线索。
 */
@Model(table = "widgets")
public class Widget extends WidgetBase {

    @Id public Long id;
    public String name;
}
