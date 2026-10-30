package io.jaravel.mail.transport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;

/**
 * 轮询分摊到多个驱动(docs/11 §1.3)。
 *
 * <p>用途:多个服务商各有发送配额,把量分摊开。
 *
 * <h2>与 failover 的区别,以及一个常见的误用</h2>
 * <p>{@code roundrobin} 是**分摊负载**,{@code failover} 是**容错**。
 *
 * <p>常见误用:以为 roundrobin 顺带有容错。它**没有** ——
 * 这一轮轮到的那个驱动失败了,这封邮件就失败了。
 *
 * <p>那个误用的后果很具体:两个服务商之一挂掉时,**一半的邮件发不出去**,
 * 而另一半正常 —— 于是监控看到的是「发送成功率 50%」,
 * 而不是「某个服务商挂了」。
 *
 * <p>要两者都有就把 failover 套在外面:
 * <pre>{@code
 * mailers:
 *   primary:   { transport: roundrobin, mailers: [ses-a, ses-b] }
 *   resilient: { transport: failover,   mailers: [primary, smtp] }
 * }</pre>
 *
 * <h2>轮询是进程内的</h2>
 * <p>多实例部署时每个实例各轮一圈。总体上仍然是均匀的
 * (大数定律),但**短时间内不均匀** —— 三个实例同时发第一封,
 * 三封都走第一个驱动。
 *
 * <p>对「分摊配额」这个用途足够了。要严格均匀就需要一个共享计数器,
 * 而那意味着每封邮件多一次 Redis 往返 —— 为一个统计性质的目标
 * 付这个代价不值得。
 */
public class RoundRobinTransport implements Transport {

    private final List<String> mailerNames;
    private final Function<String, Transport> lookup;
    private final AtomicInteger cursor = new AtomicInteger();

    public RoundRobinTransport(List<String> mailerNames, Function<String, Transport> lookup) {
        if (mailerNames == null || mailerNames.size() < 2) {
            throw new IllegalArgumentException("""
                    roundrobin 驱动至少要两个 mailer,得到 %s。"""
                    .formatted(mailerNames));
        }
        this.mailerNames = List.copyOf(mailerNames);
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return "roundrobin(" + String.join(",", mailerNames) + ")";
    }

    @Override
    public void send(RenderedMessage message) {
        // getAndIncrement 可能溢出成负数 —— Math.floorMod 而不是 %
        int index = Math.floorMod(cursor.getAndIncrement(), mailerNames.size());
        String mailer = mailerNames.get(index);

        try {
            lookup.apply(mailer).send(message);
        } catch (RuntimeException e) {
            // **不换下一个** —— roundrobin 是分摊不是容错。
            // 换的话它就变成了一个「隐式的 failover」,
            // 而那会让「某个服务商挂了」这件事被掩盖掉
            throw new TransportException(mailer, """
                    roundrobin 轮到的 mailer "%s" 发送失败:%s
                      ⚠ roundrobin **不做容错** —— 它是分摊负载。
                      要容错就把 failover 套在外面:
                        primary:   { transport: roundrobin, mailers: [a, b] }
                        resilient: { transport: failover,   mailers: [primary, smtp] }"""
                    .formatted(mailer, e.getMessage()), e);
        }
    }

    /** 测试用:下一封会走哪个 */
    public String nextMailer() {
        return mailerNames.get(Math.floorMod(cursor.get(), mailerNames.size()));
    }
}
