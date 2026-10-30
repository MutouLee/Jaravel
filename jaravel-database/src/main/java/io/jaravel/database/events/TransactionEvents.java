package io.jaravel.database.events;

/**
 * 事务生命周期事件(docs/10 §2.5)。
 *
 * <p>三个事件放在一个文件里:它们只有一个共同字段({@code level}),
 * 分成三个文件会让「这三个是一组」这件事从代码里消失。
 *
 * <h2>level 是什么,以及为什么它必须在事件里</h2>
 * <p>嵌套事务用 SAVEPOINT 实现:最外层是 1,每嵌一层加一。
 *
 * <p>没有这个字段的话,一个监听器分不清「真的提交了」与
 * 「内层保存点释放了」—— 而那两件事的后果完全不同:
 * 内层释放时数据**还没落库**,外层仍可能回滚。
 * 一个「提交后发通知」的监听器如果听到内层事件就发,
 * 会在外层回滚后留下一条关于不存在的订单的通知。
 *
 * <p>所以要「真正提交」的语义,监听器得自己判 {@code level() == 1}。
 * 框架**不替它过滤** —— 过滤掉的话就没法实现「内层回滚了但外层继续」
 * 这类审计需求了。为此 {@link Committed#isOutermost()} 提供了
 * 一个读起来清楚的写法。
 */
public final class TransactionEvents {

    private TransactionEvents() {
    }

    /** 事务开始。level=1 表示最外层;>1 是 SAVEPOINT */
    public record Beginning(int level) {
        public boolean isOutermost() {
            return level == 1;
        }
    }

    /**
     * 事务提交。
     *
     * <p>⚠ {@code level > 1} 时数据**还没落库** —— 那只是释放了一个
     * 保存点。要「真的提交了」就判 {@link #isOutermost()}。
     */
    public record Committed(int level) {
        public boolean isOutermost() {
            return level == 1;
        }
    }

    /**
     * 事务回滚。
     *
     * @param level 哪一层回滚了
     * @param cause 导致回滚的异常。主动 rollback 时为 null
     */
    public record RolledBack(int level, Throwable cause) {
        public boolean isOutermost() {
            return level == 1;
        }
    }
}
