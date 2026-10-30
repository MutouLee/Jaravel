package io.jaravel.events;

import java.util.List;
import java.util.function.Consumer;

/**
 * 事件的静态门面(docs/10 §2.1)。
 *
 * <pre>{@code
 * Event.dispatch(new OrderPaid(orderId, Instant.now()));
 * Event.dispatchIf(order.isPaid(), new OrderPaid(...));
 * Event.dispatchAfterCommit(new OrderPaid(...));
 *
 * Event.listen(OrderPaid.class, e -> metrics.increment("orders.paid"));
 * }</pre>
 */
public final class Event {

    private static volatile EventBus bus;

    private Event() {
    }

    public static void use(EventBus configured) {
        bus = configured;
    }

    public static EventBus bus() {
        EventBus current = bus;
        if (current == null) {
            throw new IllegalStateException("""
                    事件总线尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调 Event.use(EventBus.standalone())。""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        bus = null;
    }

    // ================= 分发 =================

    /**
     * 分发。
     *
     * @return 是否跑完了所有监听器。false = 被中止
     *         (见 {@link EventBus} 关于中止传播的说明)
     */
    public static boolean dispatch(Object event) {
        return bus().dispatch(event);
    }

    public static boolean dispatchIf(boolean condition, Object event) {
        return bus().dispatchIf(condition, event);
    }

    // ================= 框架内部用 =================

    /**
     * 总线没装配时**什么都不做**地分发。
     *
     * <p>**只给框架自己用**,不是给应用代码的。
     *
     * <p>存在的理由:框架事件由各模块在真实调用点发出
     * ({@code QueryExecuted} 在 JdbcConnection 里、{@code CacheHit} 在
     * Repository 里)。而那些模块有自己的单元测试,那些测试不该
     * 为了跑一条 SQL 先装一个事件总线 —— 否则「加一个框架事件」
     * 会连带改掉几十个无关测试的 setUp。
     *
     * <p>代价必须说清:总线真的忘了装时,所有框架事件会**静默消失**。
     * 这正是这个项目反复在修的那类问题。压住它的办法不是在这里报错
     * (那会让模块单测跑不起来),而是:
     * <ul>
     *   <li>starter 里 {@code EventBus} 是无条件注册的 bean ——
     *       它不可能「忘了装」;</li>
     *   <li>每个框架事件都有一条端到端测试断言它真的从那个
     *       调用点发出来了。这是 v0.3/v0.4 的经验:
     *       「类存在」和「它真的被发出来」是两件事。</li>
     * </ul>
     */
    public static void tryDispatch(Object event) {
        EventBus current = bus;
        if (current == null) return;
        current.dispatch(event);
    }

    /**
     * 有人听才构造并分发;总线没装配时什么都不做。
     *
     * <p>框架里**每次操作都发一遍**的那几个事件走这条路径 ——
     * 见 {@link EventBus#dispatchIfListening}。
     */
    public static void tryDispatchIfListening(Class<?> eventType,
                                              java.util.function.Supplier<Object> factory) {
        EventBus current = bus;
        if (current == null) return;
        current.dispatchIfListening(eventType, factory);
    }

    /** 总线装了没。框架里用来跳过「准备事件数据」的那段开销 */
    public static boolean listening(Class<?> eventType) {
        EventBus current = bus;
        return current != null && current.hasListeners(eventType);
    }

    public static boolean dispatchUnless(boolean condition, Object event) {
        return bus().dispatchUnless(condition, event);
    }

    /** 事务提交后再分发。不在事务里时立即分发 */
    public static boolean dispatchAfterCommit(Object event) {
        return bus().dispatchAfterCommit(event);
    }

    // ================= 注册 =================

    public static <E> void listen(Class<E> eventType, Consumer<E> handler) {
        bus().listen(eventType, handler);
    }

    public static <E> void listen(Class<E> eventType, int priority, Consumer<E> handler) {
        bus().listen(eventType, priority, handler);
    }

    /** 按**类名**通配。与文档里的 {@code "order.*"} 的偏差见 {@link EventBus#listen(String, java.util.function.BiConsumer)} */
    public static void listen(String classNamePattern,
                              java.util.function.BiConsumer<String, Object> handler) {
        bus().listen(classNamePattern, handler);
    }

    // ================= 测试 =================

    /** 记录分发但不执行监听器。**在被测代码之前调** */
    public static void fake() {
        bus().fake();
    }

    /** 只假装这些事件,其余照常分发 */
    public static void fake(Class<?>... types) {
        bus().fakeOnly(List.of(types));
    }

    public static void stopFaking() {
        bus().stopFaking();
    }

    // ================= 断言 =================

    /**
     * 断言某个事件被分发过。
     *
     * <p>断言写在 {@code Event} 上而不是一个测试辅助类里 ——
     * 与 Laravel 一致,也让「fake 了但忘了断言」这件事更难发生:
     * 两者就在同一个门面上。
     */
    public static void assertDispatched(Class<?> type) {
        int count = bus().recorder().count(type);
        if (count == 0) {
            throw new AssertionError("""
                    期望 %s 被分发过,但它没有。
                      已分发的事件:%s
                      若事件是在 Event.fake() **之前**分发的,这里看不到它。"""
                    .formatted(type.getSimpleName(), simpleNames()));
        }
    }

    /** 断言分发了恰好 n 次 */
    public static void assertDispatched(Class<?> type, int times) {
        int count = bus().recorder().count(type);
        if (count != times) {
            throw new AssertionError(
                    "期望 %s 被分发 %d 次,实际 %d 次。".formatted(
                            type.getSimpleName(), times, count));
        }
    }

    /**
     * 断言分发过且满足条件。
     *
     * <pre>{@code
     * Event.assertDispatched(OrderPaid.class,
     *         e -> e.orderId().equals(order.id));
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public static <E> void assertDispatched(Class<E> type, java.util.function.Predicate<E> matching) {
        var events = bus().recorder().of(type);
        if (events.isEmpty()) {
            assertDispatched(type);          // 复用那条更有信息量的消息
        }

        boolean matched = events.stream().anyMatch(e -> matching.test((E) e));
        if (!matched) {
            throw new AssertionError("""
                    %s 被分发了 %d 次,但没有一次满足给定条件。
                      实际分发的:%s"""
                    .formatted(type.getSimpleName(), events.size(), events));
        }
    }

    public static void assertNotDispatched(Class<?> type) {
        int count = bus().recorder().count(type);
        if (count > 0) {
            throw new AssertionError(
                    "期望 %s 没有被分发,但它被分发了 %d 次:%s".formatted(
                            type.getSimpleName(), count, bus().recorder().of(type)));
        }
    }

    public static void assertNothingDispatched() {
        var types = bus().recorder().types();
        if (!types.isEmpty()) {
            throw new AssertionError("期望没有任何事件被分发,实际:" + simpleNames());
        }
    }

    private static List<String> simpleNames() {
        return bus().recorder().types().stream().map(Class::getSimpleName).toList();
    }
}
