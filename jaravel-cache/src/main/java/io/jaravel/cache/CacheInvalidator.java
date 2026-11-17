package io.jaravel.cache;

import io.jaravel.cache.store.TieredStore;

/**
 * 两级缓存的 L1 失效广播(docs/09 §7)。
 *
 * <p>L1 是进程内的,{@code forget} 只能清掉本实例的那一份。多实例下
 * 其它实例会继续命中旧值,最长到 {@code l1-ttl} —— 一个读请求打到
 * 旧实例就返回旧数据,症状是「刷新几次才变」。
 *
 * <p>这个接口只做一件事:把 key 发给其它实例,让它们清本地 L1。
 * 默认实现是 Redis pub/sub;单实例应用可以没有它(不会注册)。
 */
public interface CacheInvalidator {

    /** 让这个 tiered store 接收失效消息。 */
    void register(TieredStore store);

    /** 广播一个 key;{@code "*"} 表示清空全部 L1。 */
    void publish(String key);
}
