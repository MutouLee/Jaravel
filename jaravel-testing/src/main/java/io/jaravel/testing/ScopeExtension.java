package io.jaravel.testing;

import java.lang.reflect.Method;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import io.jaravel.core.context.ExecutionScope;

/**
 * 把测试方法包进一个 {@link ExecutionScope}(docs/14 §10 的开放项之一)。
 *
 * <h2>为什么不继承 FeatureTest 的测试也需要作用域</h2>
 * <p>框架里有一批东西是**按执行作用域**存活的:guard 实例(于是
 * {@code Auth.user()} 在一次执行内只解析一次)、Eloquent 的身份映射、
 * 作用域内单例。HTTP 请求由内核开作用域,队列任务由 worker 开作用域 ——
 * 而 {@link AppTest} 既不是请求也不是任务。
 *
 * <p>没有作用域时它们退化为「每次新建」({@code scopedOrFresh}),于是
 * {@code actingAs(user)} 设进去的用户,下一行 {@code Auth.user()}
 * 读到的是**另一个 guard 实例**,结果是空。症状是「登录没生效」,
 * 而原因与认证代码无关。
 *
 * <h2>作用域只覆盖测试方法本身</h2>
 * <p>{@code @BeforeEach} 是另一次拦截、另一个调用栈,JUnit 没有
 * 「跨回调绑定」的扩展点。所以在 {@code @BeforeEach} 里
 * {@code actingAs} 不会带进测试体 —— {@link AppTest#actingAs} 为此
 * 会直接报错,而不是让你对着一个空的 {@code Auth.user()} 查半天。
 *
 * <p>造数据不受影响:那只需要连接,不需要作用域。
 */
public class ScopeExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {

        // 失败先存下来再抛:ExecutionScope.runIn 会把检查异常包一层,
        // 直接让断言失败穿过它会得到一个套了两层的 AssertionError,
        // IDE 里显示成「Exception」而不是「断言失败」
        var failure = new Throwable[1];
        ExecutionScope.runInOrReuse("test", () -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                failure[0] = t;
            }
            return null;
        });

        if (failure[0] != null) throw failure[0];
    }
}
