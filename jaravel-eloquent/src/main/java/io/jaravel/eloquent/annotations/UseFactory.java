package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这个模型的工厂类(docs/14 §3)。APT 据此生成
 * {@code <Factory>Base}(类型化 setter + 关联构建器)与模型上的
 * {@code Post.factory()} 静态入口。
 *
 * <pre>{@code
 * @Model
 * @UseFactory(PostFactory.class)
 * public class Post extends PostBase { ... }
 *
 * // 用户只写定义与状态:
 * public class PostFactory extends PostFactoryBase {
 *     @Override protected void definition(Faker f, Post p) {
 *         p.title  = f.sentence();
 *         p.status = "published";
 *     }
 *     public PostFactory draft() { return state(p -> p.status = "draft"); }
 * }
 *
 * Post.factory().draft().count(3).create();
 * }</pre>
 *
 * <h2>★ 为什么注解标在模型上,而不是工厂上</h2>
 * <p>反过来写({@code @FactoryFor(Post.class)} 标在工厂上)更像 Laravel,
 * 但在 Java 里会坏在增量编译上:生成 {@code Post.factory()} 需要在
 * **处理模型的那一轮**就知道工厂的类名,而只改了模型时工厂不在这一轮的
 * 元素里 —— 于是 {@code Post.factory()} 会在一次增量编译后**静默消失**,
 * 下一次全量编译又回来。
 *
 * <p>标在模型上,这条链就写在模型自己的源码里:编译模型时一定看得到。
 *
 * <h2>工厂类放哪个包都行</h2>
 * <p>生成的基类跟着工厂走(同包同名加 {@code Base}),所以
 * {@code database.factories.PostFactory} 与把工厂写在模型旁边都可以。
 *
 * <p>但它必须与模型**在同一次编译里**(同一个 Maven 模块的同一个源根)。
 * 写在 {@code src/test/java} 的工厂会让 main 里的 {@code Post.factory()}
 * 编译不过 —— 这一点由处理器报错说清,而不是让 javac 抱怨找不到符号。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface UseFactory {

    /** 工厂类。必须 {@code extends <它自己的名字>Base}。 */
    Class<?> value();
}
