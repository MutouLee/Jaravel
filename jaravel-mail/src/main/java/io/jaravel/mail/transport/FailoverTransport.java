package io.jaravel.mail.transport;

import java.util.List;
import java.util.function.Function;

import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;

/**
 * 依次尝试多个驱动,直到一个成功(docs/11 §1.3)。
 *
 * <pre>{@code
 * mail:
 *   mailers:
 *     failover:
 *       transport: failover
 *       mailers: [ ses, smtp ]
 * }</pre>
 *
 * <h2>⚠ 它不保证「只发一封」</h2>
 * <p>这是使用 failover 之前必须知道的事。
 *
 * <p>一个驱动可能**先把邮件投出去,然后在返回前失败** ——
 * 比如 SES 接受了请求但连接在读响应时断开。那时 failover 会
 * 换下一个驱动再发一遍,于是收件人收到两封。
 *
 * <p>这不是实现缺陷,是分布式系统里「at-least-once」的固有性质:
 * 没有办法在不与对方协调的前提下区分「没发出去」与
 * 「发出去了但我没收到确认」。
 *
 * <p>所以:
 * <ul>
 *   <li>对**幂等性无关**的邮件(通知、营销)failover 是对的 ——
 *       重复一封比一封都没有好;</li>
 *   <li>对**带一次性令牌**的邮件(密码重置)要想一下 ——
 *       两封邮件里的令牌不同,用户点了旧的那封会失败。
 *       那个体验不如「发送失败,请重试」。</li>
 * </ul>
 *
 * <p>这段说明放在代码里而不只在文档里,因为选 failover 的人
 * 通常是在读配置示例,而不是在读文档的注意事项。
 *
 * <h2>全部失败时抛的异常带上每一个的原因</h2>
 * <p>用 {@code addSuppressed} 挂上前面几个的异常。
 * 只抛最后一个的话,「SES 的 API key 过期了」这个真正的原因
 * 会被「SMTP 连不上」盖掉 —— 而后者可能只是因为那台机器
 * 本来就没开 SMTP 出口。
 */
public class FailoverTransport implements Transport {

    private final List<String> mailerNames;
    private final Function<String, Transport> lookup;

    public FailoverTransport(List<String> mailerNames, Function<String, Transport> lookup) {
        if (mailerNames == null || mailerNames.size() < 2) {
            throw new IllegalArgumentException("""
                    failover 驱动至少要两个 mailer,得到 %s。
                      只有一个的话它等于那个 mailer 本身,
                      而「配了 failover」会让人以为有冗余 —— 那是个危险的误解。"""
                    .formatted(mailerNames));
        }
        this.mailerNames = List.copyOf(mailerNames);
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return "failover(" + String.join(",", mailerNames) + ")";
    }

    @Override
    public void send(RenderedMessage message) {
        TransportException failure = null;

        for (String mailer : mailerNames) {
            try {
                lookup.apply(mailer).send(message);
                return;
            } catch (RuntimeException e) {
                var wrapped = new TransportException(mailer,
                        "mailer \"%s\" 发送失败:%s".formatted(mailer, e.getMessage()), e);

                if (failure == null) {
                    failure = wrapped;
                } else {
                    // 挂在第一个失败上 —— 第一个通常是主通道,
                    // 它的失败原因是最要紧的那个
                    failure.addSuppressed(wrapped);
                }
            }
        }

        throw new TransportException(name(), """
                failover 的全部 %d 个 mailer 都失败了:%s
                  每一个的原因都在 suppressed 里 —— 只看最后一个的话,
                  真正的原因(比如「SES 的 API key 过期」)会被
                  「SMTP 连不上」盖掉,而后者可能只是因为那台机器
                  本来就没开 SMTP 出口。"""
                .formatted(mailerNames.size(), mailerNames), failure);
    }
}
