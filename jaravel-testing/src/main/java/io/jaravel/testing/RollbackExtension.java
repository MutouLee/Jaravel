package io.jaravel.testing;

import java.lang.reflect.Method;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.jaravel.database.Connection;

/**
 * 每个测试在事务里跑,结束回滚(docs/14 §2.1)。
 *
 * <pre>{@code
 * class PostTest extends FeatureTest {
 *     // 不需要写任何清理代码
 * }
 * }</pre>
 *
 * <h2>为什么默认回滚而不是默认清库</h2>
 * <p>「测试后要清理」这件事一旦交给用户,就会有人忘、有人清不全、
 * 有人为了省事让测试互相依赖顺序。而**需要清理的测试写起来麻烦,
 * 写起来麻烦的测试就不会被写**。
 *
 * <h2>为什么是 InvocationInterceptor 而不是 @BeforeEach/@AfterEach</h2>
 * <p>{@link Connection} 刻意只暴露 {@code transaction(Supplier)},
 * 没有手工 {@code begin}/{@code rollback} —— 手工配对很容易在某个
 * 早返回分支上漏掉(那个设计决定写在 Connection 的注释里)。
 *
 * <p>所以回滚只能靠「把测试体整个包进 transaction」,而 JUnit 里
 * 能做到这件事的只有拦截器。{@code @BeforeEach} 开事务、
 * {@code @AfterEach} 回滚是另一个线程/另一个调用栈,做不到。
 *
 * <h2>回滚的实现方式:抛一个哨兵异常</h2>
 * <p>{@code transaction} 在正常返回时**提交**。要让它回滚就得抛异常,
 * 于是测试体跑完后扔一个 {@link Rollback} 出来,在外面吞掉。
 *
 * <p>看起来绕,但替代方案更差:给 Connection 加一个
 * {@code setRollbackOnly()} 等于为测试在生产 API 上开一个口子,
 * 而那个口子在业务代码里被误用的后果是「数据静默不保存」。
 *
 * <h2>没有数据库时什么都不做</h2>
 * <p>纯 HTTP 测试、或者还没配 DataSource 的项目,直接放行 ——
 * 为此报错会让「我只想测一个路由」这件事变得需要先配数据库。
 */
public class RollbackExtension implements InvocationInterceptor {

    /** 强制回滚用的哨兵。不继承 Exception 层级里任何有语义的类型。 */
    private static final class Rollback extends RuntimeException {
        Rollback() {
            super("测试结束,回滚", null, false, false);   // 不收集堆栈:它没有信息量
        }
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        if (optedOut(extensionContext)) {
            invocation.proceed();
            return;
        }

        Connection connection = connectionOf(extensionContext);

        if (connection == null) {
            invocation.proceed();
            return;
        }

        var failure = new Throwable[1];
        try {
            connection.transaction(() -> {
                try {
                    invocation.proceed();
                } catch (Throwable t) {
                    // 记下来,但**仍然抛哨兵**让事务回滚 ——
                    // 直接抛 t 也会回滚,但那样就无法区分
                    // 「测试失败」与「回滚本身出错」
                    failure[0] = t;
                }
                throw new Rollback();
            });
        } catch (Rollback expected) {
            // 正常路径
        }

        if (failure[0] != null) throw failure[0];
    }

    /**
     * 这个测试声明了 {@link WithoutRollback} 吗。
     *
     * <p>方法上与类上都看 —— 类上一个注解关掉整个类是常见需求
     * (队列测试整类都需要真提交),而逐个方法标会漏。
     *
     * <p>用 {@code getTestMethod} / {@code getTestClass} 而不是
     * {@code getElement}:后者在 {@code @Nested} 里拿到的是内层类,
     * 而注解通常标在外层 —— 那会让「标了类但内层 @Nested 不生效」,
     * 且症状是只有一部分测试失效。{@code @Inherited} 管不了这个:
     * 内部类不是外部类的子类。
     */
    private boolean optedOut(ExtensionContext context) {
        var method = context.getTestMethod();
        if (method.isPresent() && method.get().isAnnotationPresent(WithoutRollback.class)) {
            return true;
        }

        // 从当前类往外层一路找 —— @Nested 的外层类上标了也算
        Class<?> type = context.getTestClass().orElse(null);
        while (type != null) {
            if (type.isAnnotationPresent(WithoutRollback.class)) return true;
            type = type.getEnclosingClass();
        }
        return false;
    }

    /**
     * 取被测应用的数据库连接。
     *
     * <p>从 Spring 的 TestContext 拿,而不是从测试实例的字段 ——
     * 拦截器在字段注入**之前**也可能被调用,而且这样对
     * 不继承 {@link FeatureTest} 的用法也有效。
     */
    private Connection connectionOf(ExtensionContext context) {
        try {
            var applicationContext = SpringExtension.getApplicationContext(context);
            var beans = applicationContext.getBeanNamesForType(Connection.class);
            return beans.length == 0
                    ? null
                    : applicationContext.getBean(Connection.class);
        } catch (RuntimeException e) {
            // 没有 Spring 上下文(纯单元测试误用了这个扩展)→ 放行
            return null;
        }
    }
}
