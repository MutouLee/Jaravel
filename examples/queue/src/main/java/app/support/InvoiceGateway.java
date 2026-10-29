package app.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 假装是一个外部发票服务。
 *
 * <p>示例里需要一个**能被控制地失败**的下游:队列的全部价值都在
 * 「下游不可靠时怎么办」上,而一个永远成功的下游会让重试、backoff、
 * 熔断、failed_jobs 全部变成死代码。
 *
 * <p>它是一个普通的 {@code @Component} —— 任务的 handle 靠容器注入拿到它。
 * 这一点是示例要展示的重点之一:任务的依赖是**显式参数**,
 * 不是方法体里的 {@code Application.make(...)}。
 */
@Component
public class InvoiceGateway {

    private final List<String> sent = new CopyOnWriteArrayList<>();

    /** 还要失败几次。测试用它构造「第 N 次才成功」的场景 */
    private final AtomicInteger failuresLeft = new AtomicInteger();

    private volatile boolean alwaysFails;

    public void send(String orderNo, int amount) {
        if (alwaysFails || failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            // 抛 RuntimeException —— Worker 靠它判断「是否可重试」。
            // 这里不能包成 checked 异常:那会被反射层包一层,
            // 而包装会让按异常类型做判断的中间件失效
            throw new GatewayDownException(
                    "发票服务暂时不可用(order=" + orderNo + ")");
        }
        sent.add(orderNo + ":" + amount);
    }

    // ================= 测试控制 =================

    public void failNextTimes(int times) {
        failuresLeft.set(times);
    }

    public void failAlways(boolean enabled) {
        alwaysFails = enabled;
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
        failuresLeft.set(0);
        alwaysFails = false;
    }

    /** 下游挂了。独立的类型,好让中间件按类型熔断 */
    public static class GatewayDownException extends RuntimeException {
        public GatewayDownException(String message) {
            super(message);
        }
    }
}
