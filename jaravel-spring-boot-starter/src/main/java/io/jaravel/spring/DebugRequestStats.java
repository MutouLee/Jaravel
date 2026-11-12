package io.jaravel.spring;

import io.jaravel.cache.events.CacheEvents;
import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.queue.events.QueueEvents;

/**
 * 调试工具栏的缓存 / 队列计数(docs/15 §4.3)。
 *
 * <p>与 SQL 的按线程收集是同一个模式:计数在 {@link ThreadLocal} 上,
 * 一个请求一个线程,并发请求不会串台。
 *
 * <p>监听器**在第一个工具栏请求到来时才注册** —— 注册之后缓存/队列事件
 * 会在每次操作时构造并分发,而那在工具栏关闭时不值得付。生产环境
 * filter 不启用,所以这里也不会装上。
 */
final class DebugRequestStats {

    /** 一次请求的计数 */
    static final class Snapshot {
        int cacheHits;
        int cacheMisses;
        int queued;
        int processed;
        int failed;
    }

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    /** 已注册过监听器的总线 —— 测试会换一个 bus,换了就重新装 */
    private static volatile EventBus installedOn;

    private DebugRequestStats() {
    }

    static void begin() {
        CURRENT.set(new Snapshot());
        install();
    }

    static Snapshot current() {
        return CURRENT.get();
    }

    static void end() {
        CURRENT.remove();
    }

    private static synchronized void install() {
        EventBus bus;
        try {
            bus = Event.bus();
        } catch (RuntimeException notYetAssembled) {
            // 应用还没装配事件总线 —— 这个请求没有统计,但不影响页面
            return;
        }
        if (bus == installedOn) return;

        bus.listen(CacheEvents.Hit.class, event -> {
            Snapshot stats = CURRENT.get();
            if (stats != null) stats.cacheHits++;
        });
        bus.listen(CacheEvents.Missed.class, event -> {
            Snapshot stats = CURRENT.get();
            if (stats != null) stats.cacheMisses++;
        });
        bus.listen(QueueEvents.JobQueued.class, event -> {
            Snapshot stats = CURRENT.get();
            if (stats != null) stats.queued++;
        });
        bus.listen(QueueEvents.JobProcessed.class, event -> {
            Snapshot stats = CURRENT.get();
            if (stats != null) stats.processed++;
        });
        bus.listen(QueueEvents.JobFailed.class, event -> {
            Snapshot stats = CURRENT.get();
            if (stats != null) stats.failed++;
        });

        installedOn = bus;
    }
}
