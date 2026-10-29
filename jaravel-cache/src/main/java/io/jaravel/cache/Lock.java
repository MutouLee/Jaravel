package io.jaravel.cache;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 原子锁。架在 {@link CacheStore#add}(原子 put-if-absent)之上。
 *
 * <pre>{@code
 * // 闭包式,保证释放
 * Cache.lock("report", Duration.ofMinutes(5)).get(() -> generateReport());
 *
 * // 带等待
 * Cache.lock("import", Duration.ofMinutes(10))
 *      .block(Duration.ofSeconds(5), () -> doImport());
 * }</pre>
 *
 * <h2>适用范围(重要)</h2>
 * <p><b>适用于防重复执行,不适用于强一致场景。</b>它不是 Redlock,
 * 也不打算是 —— 多主节点的分布式锁正确性有公认争议,而「定时任务在多实例下
 * 跑了两遍」这类真实问题用单节点锁就够了。
 *
 * <p>具体来说,这个锁在以下情况会失效:缓存节点故障转移导致锁记录丢失;
 * 持有者进程卡住超过 TTL 而任务仍在跑。要防的是「正常情况下的重复」,
 * 不是「任意故障下的互斥」。
 *
 * <h2>owner 令牌</h2>
 * <p>释放时校验 owner,所以**不会释放别人的锁**。没有它的话,A 的锁因超时
 * 失效、B 拿到锁,而 A 的 finally 跑完会把 B 的锁删掉 —— 于是 C 又能进来。
 * 这是分布式锁最经典的一个坑。
 */
public final class Lock {

    private final Repository cache;
    private final String name;
    private final Duration ttl;
    private final String owner;

    Lock(Repository cache, String name, Duration ttl, String owner) {
        this.cache = cache;
        this.name = name;
        this.ttl = ttl;
        this.owner = owner;
    }

    static String newOwner() {
        return UUID.randomUUID().toString();
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    // ================= 获取 =================

    /** 尝试获取,不等待 */
    public boolean get() {
        return cache.add(key(), owner, ttl);
    }

    /**
     * 拿到锁就执行并在结束后释放;拿不到返回 false 且**不执行**。
     *
     * <p>返回 boolean 而不是抛异常:「别人正在跑」在防重复执行的场景里是
     * 正常结果,不是错误。
     */
    public boolean get(Runnable body) {
        if (!get()) return false;
        try {
            body.run();
        } finally {
            release();
        }
        return true;
    }

    public <T> java.util.Optional<T> get(Supplier<T> body) {
        if (!get()) return java.util.Optional.empty();
        try {
            return java.util.Optional.ofNullable(body.get());
        } finally {
            release();
        }
    }

    /**
     * 等待至多 {@code wait} 拿锁,拿到则执行。
     *
     * @throws LockTimeoutException 等待超时
     */
    public <T> T block(Duration wait, Supplier<T> body) {
        long deadline = System.nanoTime() + wait.toNanos();
        long sleepMillis = 25;

        while (true) {
            if (get()) {
                try {
                    return body.get();
                } finally {
                    release();
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new LockTimeoutException(name, wait);
            }
            try {
                Thread.sleep(Math.min(sleepMillis, 250));
                // 退避:高竞争下固定 25ms 轮询会把缓存打满
                sleepMillis = Math.min(sleepMillis * 2, 250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockTimeoutException(name, wait);
            }
        }
    }

    public void block(Duration wait, Runnable body) {
        block(wait, () -> {
            body.run();
            return null;
        });
    }

    // ================= 释放 =================

    /**
     * 释放锁 —— 仅当自己是持有者。
     *
     * @return 是否真的释放了(false 表示锁已超时并被别人拿走)
     */
    public boolean release() {
        var current = cache.get(key(), String.class);
        if (current.isEmpty()) return false;
        if (!current.get().equals(owner)) {
            // 不是自己的锁。静默返回 false 而不是抛异常:这通常意味着
            // 任务跑得比 TTL 久,是个值得知道但不该让业务代码崩的情况。
            return false;
        }
        cache.forget(key());
        return true;
    }

    /** 无视 owner 强行释放。只在运维介入时用。 */
    public void forceRelease() {
        cache.forget(key());
    }

    public boolean isHeld() {
        return cache.has(key());
    }

    /** 当前持有者;没人持有时为空 */
    public java.util.Optional<String> currentOwner() {
        return cache.get(key(), String.class);
    }

    private String key() {
        return "lock:" + name;
    }

    public static class LockTimeoutException extends RuntimeException {
        private final String lockName;

        public LockTimeoutException(String lockName, Duration waited) {
            super("等待锁 \"" + lockName + "\" 超过 " + waited.toMillis() + "ms 仍未获得。"
                + "\n  说明有另一个进程/线程正持有它并且还没跑完。"
                + "\n  可选处理:加长等待、改用不等待的 get()(拿不到就跳过),"
                + "或检查持有方是否卡住。");
            this.lockName = lockName;
        }

        public String lockName() {
            return lockName;
        }
    }
}
