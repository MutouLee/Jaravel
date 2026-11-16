package io.jaravel.spring.broadcast;

/**
 * 多实例枢纽的标记。
 *
 * <p>自动配置用它判断 `broadcast.driver = redis` 拿到的枢纽是不是
 * 真的会扇出到其它实例 —— 而不直接 `instanceof RedisWsHub`:
 * 后者会让**没有 Lettuce** 的应用在加载自动配置时就去解析
 * {@code RedisWsHub} 的 Lettuce 类型,于是 driver: ws 的应用也
 * 可能撞上 NoClassDefFoundError。
 */
public interface DistributedHub {
}
