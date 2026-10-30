package io.jaravel.mail;

/**
 * 一个邮件驱动(docs/11 §1.3)。
 *
 * <p>驱动只认 {@link RenderedMessage} —— 它拿到的是最终的 HTML、
 * 最终的收件人、已经读出来的附件。它**不知道** Mailable、模板、
 * 语言这些概念。
 *
 * <p>这个边界让写一个新驱动变成一件小事:处理一个扁平的数据结构。
 *
 * <h2>send 抛异常就表示失败</h2>
 * <p>不返回布尔值。理由:失败的**原因**是必须的信息
 * (SMTP 认证失败 / 收件人被拒 / 连不上),而一个 false
 * 把它们全擦掉了 —— 那时唯一的诊断手段是去翻驱动自己的日志。
 *
 * <p>{@code failover} 驱动依赖异常来决定「换下一个」,
 * 它也需要知道上一个为什么失败(写进 suppressed)。
 */
public interface Transport {

    /** 驱动名,用于日志与错误消息 */
    String name();

    /**
     * 发送。
     *
     * @throws TransportException 发送失败
     */
    void send(RenderedMessage message);

    /**
     * 这个驱动真的会把邮件发出去吗。
     *
     * <p>{@code log} / {@code array} 返回 false。
     *
     * <p>用途:starter 在生产环境检测到一个不真发的驱动时警告 ——
     * 「生产环境配了 log 驱动」的症状是「用户说没收到邮件」,
     * 而发件方的日志里一切正常。
     */
    default boolean actuallyDelivers() {
        return true;
    }

    /** 发送失败 */
    class TransportException extends RuntimeException {

        private final String transport;

        public TransportException(String transport, String message, Throwable cause) {
            super(message, cause);
            this.transport = transport;
        }

        public String transport() {
            return transport;
        }
    }
}
