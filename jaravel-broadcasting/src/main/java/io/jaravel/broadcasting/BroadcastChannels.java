package io.jaravel.broadcasting;

/**
 * 应用的频道定义入口(docs/19 §3.1)。与 {@code RouteDefinition}
 * 同一发现机制:实现类注册成 Spring bean,启动时被自动装配发现。
 *
 * <pre>{@code
 * // app/channels/Channels.java
 * public class Channels implements BroadcastChannels {
 *     @Override public void define(ChannelRegistry channels) {
 *         channels.channel("private-orders.{orderId}", (User user, Map<String, String> p) ->
 *                 user.getId().equals(Long.parseLong(p.get("orderId"))));
 *     }
 * }
 * }</pre>
 */
public interface BroadcastChannels {

    void define(ChannelRegistry channels);
}
