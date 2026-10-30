package io.jaravel.mail.transport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;

/**
 * 把邮件存在内存里 —— 测试用(docs/11 §1.3)。
 *
 * <p>与 {@code Mail.fake()} 的区别值得说清:
 *
 * <ul>
 *   <li>{@code array} 驱动**走完整的渲染流程** ——
 *       模板真的被渲染、地址真的被校验、附件真的被读出来。
 *       它只是不往网络上发;</li>
 *   <li>{@code Mail.fake()} 在**更早**的地方拦下 ——
 *       它记录「有人要发这个 Mailable」,不渲染。</li>
 * </ul>
 *
 * <p>所以两者测的东西不同:
 * <ul>
 *   <li>要断言「邮件的 HTML 里有订单号」→ 用 {@code array} 驱动;</li>
 *   <li>要断言「下单之后发了 OrderShipped」→ 用 {@code Mail.fake()}。</li>
 * </ul>
 *
 * <p>后者快得多(不渲染),前者真实得多。这个区别不说清的话,
 * 用户会用 fake 去断言正文内容,然后发现拿不到。
 */
public class ArrayTransport implements Transport {

    private final List<RenderedMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "array";
    }

    @Override
    public void send(RenderedMessage message) {
        sent.add(message);
    }

    @Override
    public boolean actuallyDelivers() {
        return false;
    }

    public List<RenderedMessage> sent() {
        return List.copyOf(sent);
    }

    public int count() {
        return sent.size();
    }

    public void clear() {
        sent.clear();
    }
}
