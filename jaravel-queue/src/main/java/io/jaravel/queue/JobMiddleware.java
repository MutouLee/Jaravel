package io.jaravel.queue;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 任务中间件(docs/10 §1.3)。
 *
 * <pre>{@code
 * public class SendInvoice implements ShouldQueue {
 *     @Override public List<JobMiddleware> middleware() {
 *         return List.of(
 *             new WithoutOverlapping("invoice:" + orderId),
 *             new RateLimited("mail-api"));
 *     }
 * }
 * }</pre>
 *
 * <h2>与 HTTP 中间件同一个心智模型,但有一个关键区别</h2>
 * <p>HTTP 中间件只能「放行」或「返回一个响应」。任务中间件多了第三种:
 * **把任务放回队列稍后再试**({@link Context#release})。
 *
 * <p>这个区别很重要 —— 它是「等锁」「等限流」的正确表达方式。
 * 用阻塞等待代替会占着一个并发额度干等,而那个额度本可以去跑别的任务。
 */
public interface JobMiddleware {

    /**
     * 处理一个任务。
     *
     * <p>调 {@code next.run()} 表示放行。不调就等于**不执行这个任务** ——
     * 此时必须明确地做一件事:{@link Context#release} 放回队列,
     * 或 {@link Context#delete} 丢弃。什么都不做会让任务静默消失
     * (它已经被 pop 出来了),所以框架会在那种情况下报错。
     */
    void handle(Context context, Runnable next);

    /** 中间件的名字,用于日志与报错 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 中间件能对任务做的事。
     *
     * <p>刻意做得很窄:只有 release / delete 两个动作加上只读信息。
     * 给它暴露 QueueStore 会让中间件能做任何事,而任何事都包括
     * 「把别人的任务删掉」。
     */
    interface Context {

        /** 任务实例 */
        ShouldQueue job();

        /** 信封(uuid、尝试次数、队列名) */
        QueuedJob envelope();

        /**
         * 放回队列,延迟后再试。
         *
         * <p>**不计入异常次数** —— 这是 {@code maxExceptions} 与
         * {@code tries} 分开的理由:等锁等了 50 次不该耗尽重试配额。
         */
        void release(Duration delay);

        /**
         * 丢弃这个任务。
         *
         * <p>用于「这件事已经不需要做了」(唯一性约束发现重复、
         * 目标资源已删除)。**不算失败**,不进 failed_jobs ——
         * 它是一个正常的结束。
         */
        void delete(String reason);

        /** 这次处理是否已经被中间件决定了(release 或 delete 过) */
        boolean isResolved();
    }

    /**
     * 组合成一条链。
     *
     * <p>顺序就是声明顺序,第一个最外层 —— 与 HTTP 中间件一致。
     */
    static void run(java.util.List<JobMiddleware> middleware, Context context,
                    Runnable terminal) {
        Runnable chain = terminal;
        for (int i = middleware.size() - 1; i >= 0; i--) {
            JobMiddleware current = middleware.get(i);
            Runnable next = chain;
            chain = () -> current.handle(context, next);
        }
        chain.run();
    }

    /** 给测试与自定义用的简易适配 */
    static JobMiddleware of(String name, Consumer<Context> before) {
        return new JobMiddleware() {
            @Override public void handle(Context context, Runnable next) {
                before.accept(context);
                if (!context.isResolved()) next.run();
            }

            @Override public String name() {
                return name;
            }
        };
    }
}
