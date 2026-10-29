package io.jaravel.testing;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 测试用的引导配置 —— 让 {@link FeatureTest} 在**任何包**下都能工作。
 *
 * <h2>它解决的问题</h2>
 * <p>{@code @SpringBootTest} 默认从测试类所在包**向上**搜
 * {@code @SpringBootConfiguration}。而 Jaravel 的约定布局把应用放在
 * {@code app} 包,测试却自然地写在 {@code apptest} / {@code tests} 之类的
 * 兄弟包里 —— 向上搜不到兄弟包,于是报
 * 「Unable to find a @SpringBootConfiguration」。
 *
 * <p>让用户为此调整包结构是错的:那是框架的约定与测试框架的约定撞车,
 * 代价不该由用户付。
 *
 * <h2>为什么它不需要引用用户的 Application 类</h2>
 * <p>用户的入口类只是 {@code @SpringBootApplication},等于
 * {@code @SpringBootConfiguration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan}。而**第三项 Jaravel 自己就提供了** ——
 * {@code JaravelAutoConfiguration} 上的
 * {@code @Import(JaravelComponentScan.class)} 会扫 app / routes / database
 * 三个约定包(那是 v0.3 为了让 @SpringBootTest 能用而加的)。
 *
 * <p>所以这个类加上自动装配,就等价于启动用户的应用。
 *
 * <h2>需要用自己的入口类时</h2>
 * <p>在测试类上写自己的 {@code @SpringBootTest(classes = MyApp.class)} ——
 * 子类上的注解覆盖继承来的那一个。入口类上挂了额外的
 * {@code @Import} 或 {@code @EnableXxx} 时需要这样做。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class JaravelTestApplication {
}
