package io.jaravel.queue.store;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;

/**
 * 丢弃一切。
 *
 * <p>用途不是「关掉队列」,而是**在不该有副作用的环境里把副作用掐掉** ——
 * 典型是跑一次性的数据修复脚本时,不希望顺带触发一堆通知邮件。
 *
 * <p>{@link #discarded()} 是刻意提供的:静默丢弃最怕的是「我以为它跑了」,
 * 而一个计数器让「丢了多少」至少可观测。
 */
public class NullQueue implements QueueStore {

    private final AtomicLong discarded = new AtomicLong();

    @Override public String name() { return "null"; }

    @Override public boolean isAsynchronous() { return false; }

    @Override
    public String push(QueuedJob job) {
        discarded.incrementAndGet();
        return job.uuid();
    }

    @Override public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
        return Optional.empty();
    }

    @Override public void delete(QueuedJob job) { }

    @Override public void release(QueuedJob job, Duration delay) { }

    @Override public long size(String queue) { return 0; }

    @Override public void clear(String queue) { }

    /** 一共丢了多少条 */
    public long discarded() {
        return discarded.get();
    }
}
