package io.jaravel.eloquent.event;

/**
 * 模型生命周期事件。
 *
 * <p>{@code CREATING} / {@code UPDATING} / {@code DELETING} / {@code SAVING}
 * 是**可中止**的:观察者返回 false 即取消该操作。
 *
 * <p>事件默认**在事务内同步触发**。需要事务提交后才执行的副作用,
 * 用 {@code DB.afterCommit(...)} —— 否则会出现「事务还没提交就发了消息」
 * 这个经典 bug。
 */
public enum ModelEvent {

    /** 从数据库读出后 */
    RETRIEVED(false),

    /** 插入前。可中止。 */
    CREATING(true),
    CREATED(false),

    /** 更新前。可中止。 */
    UPDATING(true),
    UPDATED(false),

    /** 插入或更新前,在 CREATING/UPDATING 之后。可中止。 */
    SAVING(true),
    SAVED(false),

    /** 删除前。可中止。 */
    DELETING(true),
    DELETED(false),

    /** 软删除恢复后 */
    RESTORED(false);

    private final boolean abortable;

    ModelEvent(boolean abortable) {
        this.abortable = abortable;
    }

    /** 观察者返回 false 能否取消该操作 */
    public boolean isAbortable() {
        return abortable;
    }
}
