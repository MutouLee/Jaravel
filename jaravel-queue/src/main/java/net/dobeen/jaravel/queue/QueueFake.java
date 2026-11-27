package net.dobeen.jaravel.queue;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * fake 模式下的投递记录与断言(docs/14 §4)。
 *
 * <pre>{@code
 * var queue = Queue.fake();
 *
 * service.checkout(order);
 *
 * queue.assertPushed(SendInvoice.class, j -> j.orderId().equals(order.id()));
 * queue.assertPushedOnQueue("mail", SendInvoice.class);
 * queue.assertNotPushed(RefundJob.class);
 * queue.assertPushedCount(1);
 * queue.assertChained(List.of(ProcessPayment.class, SendInvoice.class));
 * queue.assertBatched(b -> b.jobs().size() == 3);
 * }</pre>
 *
 * <h2>★ 为什么断言写在任务对象上,而不是查队列表</h2>
 * <p>「队列里有一条 payload 含 orderId=42 的 JSON」这种断言既脆(改一个
 * 字段名就挂)又读不出意图。fake 在**序列化之前**拦下,记下的是任务
 * **实例**,于是断言能直接调它的方法:
 * {@code j -> j.orderId().equals(order.id())}。
 *
 * <h2>★ dispatchSync 不被拦下</h2>
 * <p>这是与 Laravel 刻意不同的一点。{@code Queue.dispatchSync(job)} 的
 * 语义是「现在、在我的调用栈上执行」—— 它压根不是后台任务,只是复用了
 * 任务类来表达一次同步调用。拦下它的结果是:被测代码什么都没做,而测试
 * 绿了。所以 fake 只拦**异步**连接上的投递;同步连接照常执行。
 *
 * <p>对这类任务调 {@link #assertPushed} 会失败,失败消息会把这件事说出来。
 *
 * <h2>fake 模式下不做的其它几件事</h2>
 * <ul>
 *   <li>不发 {@code JobQueued} 事件 —— 什么都没入队;</li>
 *   <li>不抢 {@code UniqueJob} 的锁(那需要 cache),所以记录里可能出现
 *       真实运行时会被去重的重复任务;</li>
 *   <li>不碰批次存储 —— {@code Queue.batch(...)} 在 fake 下不要求
 *       {@code job_batches} 表。</li>
 * </ul>
 */
public final class QueueFake {

    /** 一次投递 */
    public record Pushed(ShouldQueue job, String queue, String connection, Duration delay) {
    }

    /** 一条链 */
    public record Chained(List<ShouldQueue> jobs, String queue, String connection,
                          ShouldQueue onFailure, Duration delay) {

        public List<Class<?>> types() {
            return jobs.stream().<Class<?>>map(ShouldQueue::getClass).toList();
        }
    }

    /** 一个批次 */
    public record Batched(String id, String name, List<ShouldQueue> jobs,
                          String queue, String connection, boolean allowsFailures) {
    }

    private final List<Pushed> pushed = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Chained> chains = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Batched> batches = new java.util.concurrent.CopyOnWriteArrayList<>();

    void recordPushed(ShouldQueue job, String queue, String connection, Duration delay) {
        pushed.add(new Pushed(job, queue, connection,
                delay == null ? Duration.ZERO : delay));
    }

    void recordChain(List<ShouldQueue> jobs, String queue, String connection,
                     ShouldQueue onFailure, Duration delay) {
        chains.add(new Chained(List.copyOf(jobs), queue, connection, onFailure,
                delay == null ? Duration.ZERO : delay));
    }

    void recordBatch(String id, String name, List<ShouldQueue> jobs,
                     String queue, String connection, boolean allowsFailures) {
        batches.add(new Batched(id, name, List.copyOf(jobs), queue, connection,
                allowsFailures));
    }

    // ================= 记录 =================

    public List<Pushed> pushed() {
        return List.copyOf(pushed);
    }

    @SuppressWarnings("unchecked")
    public <J extends ShouldQueue> List<J> pushed(Class<J> type) {
        return pushed.stream()
                .filter(p -> type.isInstance(p.job()))
                .map(p -> (J) p.job())
                .toList();
    }

    public List<Chained> chains() {
        return List.copyOf(chains);
    }

    public List<Batched> batches() {
        return List.copyOf(batches);
    }

    public int count() {
        return pushed.size();
    }

    // ================= 断言:投递 =================

    public void assertPushed(Class<? extends ShouldQueue> type) {
        if (!pushed.isEmpty() && !pushed(type).isEmpty()) return;

        throw new AssertionError("""
                没有投递 %s。%s
                  若它是 Queue.dispatchSync(...) 投的,fake 不拦同步投递
                  (它在你的调用栈上已经真的执行了);
                  若它是在 Queue.fake() **之前**投的,这里看不到它。"""
                .formatted(type.getSimpleName(), recorded()));
    }

    public void assertPushed(Class<? extends ShouldQueue> type, int times) {
        int actual = pushed(type).size();
        if (actual == times) return;

        throw new AssertionError("""
                %s 期望投递 %d 次,实际 %d 次。%s"""
                .formatted(type.getSimpleName(), times, actual, recorded()));
    }

    /**
     * 带条件的断言 —— 断言写在任务对象上。
     *
     * <pre>{@code
     * queue.assertPushed(SendInvoice.class, j -> j.orderId().equals(42L));
     * }</pre>
     */
    public <J extends ShouldQueue> void assertPushed(Class<J> type, Predicate<J> matching) {
        List<J> candidates = pushed(type);
        if (candidates.stream().anyMatch(matching)) return;

        // 一条也没有时,给那条更有信息量的消息
        if (candidates.isEmpty()) assertPushed(type);

        throw new AssertionError("""
                投递了 %d 个 %s,但没有一个满足条件。%s"""
                .formatted(candidates.size(), type.getSimpleName(), recorded()));
    }

    public void assertPushedOnQueue(String queue, Class<? extends ShouldQueue> type) {
        boolean found = pushed.stream()
                .anyMatch(p -> type.isInstance(p.job()) && p.queue().equals(queue));
        if (found) return;

        if (pushed(type).isEmpty()) assertPushed(type);

        throw new AssertionError("""
                %s 没有投到队列 "%s",它去了 %s。%s
                  队列名的来源依次是:onQueue(...) > 任务的 queue() > 默认队列。"""
                .formatted(type.getSimpleName(), queue,
                           pushed.stream().filter(p -> type.isInstance(p.job()))
                                 .map(Pushed::queue).distinct().toList(),
                           recorded()));
    }

    public void assertNotPushed(Class<? extends ShouldQueue> type) {
        List<? extends ShouldQueue> found = pushed(type);
        if (found.isEmpty()) return;

        throw new AssertionError("""
                不该投递 %s,但投了 %d 次。%s"""
                .formatted(type.getSimpleName(), found.size(), recorded()));
    }

    public void assertPushedCount(int expected) {
        if (pushed.size() == expected) return;

        throw new AssertionError("""
                期望一共投递 %d 条,实际 %d 条。%s
                  注意:链与批次里的任务**不计入**这个数
                  (用 assertChained / assertBatched)。"""
                .formatted(expected, pushed.size(), recorded()));
    }

    public void assertNothingPushed() {
        if (pushed.isEmpty() && chains.isEmpty() && batches.isEmpty()) return;

        throw new AssertionError("""
                期望什么都没投,但有记录。%s""".formatted(recorded()));
    }

    // ================= 断言:链与批 =================

    /**
     * 有一条链**恰好**是这些类型、且顺序一致。
     *
     * <p>不是「包含」:链的意义就在顺序,顺序对不上时通过反而危险。
     */
    public void assertChained(List<Class<? extends ShouldQueue>> types) {
        List<Class<?>> expected = List.copyOf(types);
        if (chains.stream().anyMatch(c -> c.types().equals(expected))) return;

        throw new AssertionError("""
                没有这样一条链:%s
                  已记录的链:%s%s"""
                .formatted(simpleNames(expected),
                           chains.stream().map(c -> simpleNames(c.types())).toList(),
                           recorded()));
    }

    public void assertBatched(Predicate<Batched> matching) {
        if (batches.stream().anyMatch(matching)) return;

        throw new AssertionError("""
                没有满足条件的批次。已记录 %d 个批次:%s%s"""
                .formatted(batches.size(),
                           batches.stream()
                                  .map(b -> simpleNames(b.jobs().stream()
                                          .<Class<?>>map(ShouldQueue::getClass).toList()))
                                  .toList(),
                           recorded()));
    }

    public void assertNothingBatched() {
        if (batches.isEmpty()) return;

        throw new AssertionError("期望没有批次,实际 %d 个。%s"
                .formatted(batches.size(), recorded()));
    }

    // ================= 失败消息里的「已记录」 =================

    /**
     * 失败消息里附上**实际记录了什么**。
     *
     * <p>没有这段的话,一条「没有投递 SendInvoice」只能告诉你结果不对,
     * 查不出是投错了队列、投的是另一个类型、还是压根没走到那行代码。
     */
    private String recorded() {
        if (pushed.isEmpty() && chains.isEmpty() && batches.isEmpty()) {
            return "\n  已记录:(空 —— fake 之后没有任何异步投递)";
        }

        var out = new StringBuilder("\n  已记录:");
        if (!pushed.isEmpty()) {
            out.append(pushed.stream()
                    .map(p -> "%s → %s@%s%s".formatted(
                            p.job().getClass().getSimpleName(),
                            p.queue(), p.connection(),
                            p.delay().isZero() ? "" : " +" + p.delay()))
                    .collect(Collectors.joining(", ", "\n    投递:", "")));
        }
        if (!chains.isEmpty()) {
            out.append(chains.stream()
                    .map(c -> simpleNames(c.types()).toString())
                    .collect(Collectors.joining(", ", "\n    链:", "")));
        }
        if (!batches.isEmpty()) {
            out.append(batches.stream()
                    .map(b -> "%s(%d 个任务)".formatted(
                            b.name() == null ? "(未命名)" : b.name(), b.jobs().size()))
                    .collect(Collectors.joining(", ", "\n    批次:", "")));
        }
        return out.toString();
    }

    private static List<String> simpleNames(List<Class<?>> types) {
        return types.stream().map(Class::getSimpleName).toList();
    }
}
