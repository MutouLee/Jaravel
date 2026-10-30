package io.jaravel.database.events;

import java.time.Duration;
import java.util.List;

/**
 * 一条 SQL 执行完了(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class LogSlowQueries {
 *     public void handle(QueryExecuted e) {
 *         if (e.duration().toMillis() > 100) {
 *             Log.warn("慢查询 {}ms: {} {}", e.duration().toMillis(), e.sql(), e.bindings());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>这是热路径上的事件</h2>
 * <p>每条 SQL 一次。所以发出它的地方用的是
 * {@code Event.tryDispatchIfListening} —— 没人监听时**连这个 record
 * 都不构造**,也不去复制一份 bindings。
 *
 * <p>一个跑 2000 QPS 的接口上,「为可能没人听的事件拼一个对象」
 * 是真实的开销。这个设计上的小心是必要的,否则「事件系统」
 * 会变成一个大家默认关掉的东西。
 *
 * <h2>bindings 里可能有 null</h2>
 * <p>{@code set x = null} 是完全正常的绑定,所以这个列表允许 null 元素。
 * 用 {@code List.copyOf} 复制它会 NPE —— 那正是查询日志当年踩过的坑。
 *
 * @param sql       执行的 SQL(带 ? 占位符,不是拼好的)
 * @param bindings  绑定值,**可能含 null**
 * @param duration  耗时
 * @param caller    调用栈里第一个非框架帧,用来定位「这条 SQL 是哪行代码发的」
 */
public record QueryExecuted(
        String sql,
        List<Object> bindings,
        Duration duration,
        String caller
) {

    /** 慢查询判定的便捷写法 */
    public boolean slowerThan(Duration threshold) {
        return duration.compareTo(threshold) > 0;
    }

    @Override
    public String toString() {
        return "%.2fms  %s  %s".formatted(
                duration.toNanos() / 1_000_000.0, sql, bindings);
    }
}
