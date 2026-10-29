package io.jaravel.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 关掉这个测试(或整个测试类)的自动回滚。
 *
 * <pre>{@code
 * @WithoutRollback
 * class QueueFeatureTest extends FeatureTest {
 *     @BeforeEach
 *     void clean() { db.table("jobs").delete(); }     // 自己清
 * }
 * }</pre>
 *
 * <h2>什么时候需要它</h2>
 * <p>自动回滚把测试体包在一个**永不提交**的事务里。对绝大多数测试
 * 这正是想要的,但有几类测试会因此直接失效:
 *
 * <ul>
 *   <li><b>队列投递</b> —— {@code afterCommit} 是默认行为,任务要等
 *       事务提交才真正入队。而那个事务永远不提交,于是任务永不入队。
 *       失败的样子是「队列是空的」,看起来像投递坏了;</li>
 *   <li><b>另一个线程要看到数据</b> —— worker 线程、异步任务拿到的是
 *       另一条连接,看不到未提交的数据。症状是「worker 说记录不存在」;</li>
 *   <li><b>测试自己要验证提交行为</b> —— 比如断言
 *       {@code afterCommit} 回调真的跑了。</li>
 * </ul>
 *
 * <h2>代价要说清</h2>
 * <p>关掉之后**清理是你的事**。不清的后果不是「测试失败」而是
 * 「测试互相污染,且污染方向取决于执行顺序」—— 那是最难查的一类
 * 测试问题,因为单独跑每个测试都是绿的。
 *
 * <p>所以这个注解刻意取了一个不好打的名字,也没有做成
 * {@code FeatureTest} 上的开关:它应该是一个需要先读一遍注释
 * 才会用上的东西。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface WithoutRollback {
}
