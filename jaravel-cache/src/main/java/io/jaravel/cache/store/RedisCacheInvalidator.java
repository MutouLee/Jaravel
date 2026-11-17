package io.jaravel.cache.store;

import java.util.concurrent.CopyOnWriteArrayList;

import io.jaravel.cache.CacheInvalidator;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * 用 Redis pub/sub 广播两级缓存的 L1 失效(docs/09 §7)。
 *
 * <p>{@link TieredStore#forget} 清掉本实例的 L1 + L2 后,把 key 发到
 * 这个频道;其它实例收到只清**自己的 L1**({@link TieredStore#evictLocal}),
 * 不回应消息 —— 否则两个实例会互相触发,消息无限循环。
 * {@code "*"} 表示 {@link TieredStore#flush()} 的全量失效。
 *
 * <h2>连接惰性</h2>
 * <p>构造只记 URI 与频道;第一次 {@link #register} 或 {@link #publish}
 * 才连。没有 tiered store 的应用(或单实例)不会因为 classpath 上有
 * Lettuce 就在启动时连 Redis。
 *
 * <h2>连不上时降级</h2>
 * <p>失效是增强,不是正确性前提(单实例下本来就不需要)。连不上只打
 * 一次警告,L1 照常工作 —— 其它实例会读到旧值,最长到 {@code l1-ttl}。
 * 这与 {@code TieredStore} 文档里的陈旧窗口一致。
 */
public final class RedisCacheInvalidator implements CacheInvalidator, AutoCloseable {

    public static final String DEFAULT_CHANNEL = "jaravel:cache:invalidate";

    private final String uri;
    private final String channel;
    private final CopyOnWriteArrayList<TieredStore> locals = new CopyOnWriteArrayList<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    private StatefulRedisPubSubConnection<String, String> pubsub;
    private volatile boolean warned;
    private volatile boolean closed;

    public RedisCacheInvalidator(String uri) {
        this(uri, DEFAULT_CHANNEL);
    }

    public RedisCacheInvalidator(String uri, String channel) {
        this.uri = uri;
        this.channel = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel;
    }

    @Override
    public void register(TieredStore store) {
        locals.addIfAbsent(store);
        try {
            start();
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    @Override
    public void publish(String key) {
        if (closed) return;
        try {
            start();
            commands.publish(channel, key);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private synchronized void start() {
        if (pubsub != null || closed) return;

        try {
            client = RedisClient.create(uri);
            connection = client.connect(StringCodec.UTF8);
            commands = connection.sync();

            pubsub = client.connectPubSub(StringCodec.UTF8);
            pubsub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String ignored, String key) {
                    onMessage(key);
                }
            });
            pubsub.sync().subscribe(channel);
        } catch (RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    /** 收到别的实例的失效 → 只清本地 L1,不再发布 */
    private void onMessage(String key) {
        for (TieredStore store : locals) {
            try {
                if ("*".equals(key)) store.flushLocal();
                else store.evictLocal(key);
            } catch (RuntimeException ignored) {
                // 一个 store 出问题不该让其它 store 漏掉失效
            }
        }
    }

    private void warn(RuntimeException e) {
        if (warned) return;
        warned = true;
        System.err.printf("""
                [jaravel] L1 失效广播不可用(只警告一次):%s
                          两级缓存仍工作,但其它实例会读到旧值,最长 l1-ttl。
                          检查 cache.stores.redis.uri 与 Redis 是否可达。%n""",
                e.getMessage());
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (pubsub != null) {
                pubsub.sync().unsubscribe(channel);
                pubsub.close();
            }
        } catch (RuntimeException ignored) {
            // 关闭路径不抛
        }
        try {
            if (connection != null) connection.close();
        } catch (RuntimeException ignored) {
            // 同上
        }
        try {
            if (client != null) client.shutdown();
        } catch (RuntimeException ignored) {
            // 同上
        }
        pubsub = null;
        connection = null;
        commands = null;
        client = null;
    }
}
