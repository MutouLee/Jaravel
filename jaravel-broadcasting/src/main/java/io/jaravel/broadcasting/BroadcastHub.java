package io.jaravel.broadcasting;

import java.util.Map;

/**
 * 广播驱动的契约(docs/19 §6.2)。
 *
 * <p>刻意收窄成一个方法:驱动只回答「把这条消息交给所有订阅了
 * channel 的连接」。频道鉴权、presence 成员表、协议编解码都是
 * 传输层的事,与驱动无关 —— 那正是 {@code ws} 驱动能在 starter 里
 * 完整实现、而 v0.7 的 redis 驱动只需在 {@code publish} 里加一步
 * pub/sub 的原因。
 *
 * <p><b>语义:尽力而为。</b> 没有订阅者、订阅者断线、网络失败 ——
 * 都**不抛异常**(序列化失败除外,那发生在调 {@code publish} 之前,
 * 属于程序员错误)。广播不是事务的一部分,前端也总有自己的
 * 兜底(重连后重新拉数据)。
 */
public interface BroadcastHub {

    void publish(String channel, String event, Map<String, Object> data);
}
