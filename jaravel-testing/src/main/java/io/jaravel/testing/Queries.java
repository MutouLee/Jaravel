package io.jaravel.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.assertj.core.api.Assertions;

import io.jaravel.database.Connection;

/**
 * SQL 条数断言 —— **把 N+1 变成会让 CI 失败的回归测试**(docs/14 §2.3)。
 *
 * <pre>{@code
 * assertQueryCount(2, () -> get("/posts").assertOk());
 * }</pre>
 *
 * <h2>为什么这是对 Laravel 的一个真实增强</h2>
 * <p>N+1 的现状是:本地数据有 3 条,慢 1 毫秒,没人看得出来;
 * 上线后有 10 万条,变成数据库告警。中间没有任何环节会发现它 ——
 * code review 看不出「这个循环里访问了关联」,而性能测试通常不在 CI 里。
 *
 * <p>Jaravel 已有两道防线:开发模式下未预加载的关联访问直接报错
 * ({@code LazyLoadingViolationException}),以及查询数阈值警告。
 * 本类是第三道:**把具体数字写进测试**。于是「有人加了一行代码让
 * 列表页从 2 条查询变成 51 条」会让 CI 红,而不是让线上变慢。
 *
 * <h2>失败时列出全部 SQL,并标出重复的</h2>
 * <p>只说「期望 2 条实际 51 条」没用。真正要回答的是「多出来的是哪些」,
 * 而 N+1 的特征恰恰是**同一条语句重复 N 次** —— 所以按语句聚合后
 * 一眼就能看出是哪个关联没预加载。
 */
final class Queries {

    private Queries() {
    }

    static void assertCount(Connection connection, int expected, Runnable body) {
        var log = record(connection, () -> {
            body.run();
            return null;
        });

        if (log.size() == expected) return;

        Assertions.fail("""
                期望 %d 条 SQL,实际 %d 条。
                %s"""
                .formatted(expected, log.size(), describe(log)));
    }

    static <T> T counting(Connection connection, Supplier<T> body, IntConsumer report) {
        var result = new Object[1];
        var log = record(connection, () -> {
            result[0] = body.get();
            return null;
        });
        report.accept(log.size());

        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    /**
     * 跑一段代码并收集它发出的 SQL。
     *
     * <p>**先 flush 再开启**:查询日志是连接级的,上一个测试或本测试的
     * 准备阶段(建数据)可能已经往里写了几十条。不清的话断言的数字
     * 会随「准备了多少数据」浮动,而那是一个随机失败的测试。
     *
     * <p>结束时恢复原来的开关状态 —— 开发模式下查询日志本来是开的
     * (错误页的 SQL 面板要用),测试不该把它关掉。
     */
    private static List<Connection.QueryLog> record(Connection connection,
                                                    Supplier<Void> body) {
        connection.flushQueryLog();
        connection.enableQueryLog();
        try {
            body.get();
            return List.copyOf(connection.queryLog());
        } finally {
            connection.flushQueryLog();
        }
    }

    private static String describe(List<Connection.QueryLog> log) {
        if (log.isEmpty()) return "  没有发出任何 SQL。";

        var counts = new LinkedHashMap<String, Integer>();
        log.forEach(q -> counts.merge(q.sql(), 1, Integer::sum));

        var sb = new StringBuilder();
        sb.append("  按语句聚合(重复次数 × 语句):").append(System.lineSeparator());
        counts.forEach((sql, times) -> sb
                .append("    ×").append(times).append("  ").append(sql)
                .append(times >= 3 ? "   ⚠ 疑似 N+1" : "")
                .append(System.lineSeparator()));

        var repeated = counts.entrySet().stream().filter(e -> e.getValue() >= 3).toList();
        if (!repeated.isEmpty()) {
            sb.append("""
                      ── 多出来的查询是同一条语句重复的,这就是 N+1 的特征。
                      修复:在查询上加预加载 —— Post.query().with(Post_.author).get()""")
              .append(System.lineSeparator());
        }
        return sb.toString();
    }
}
