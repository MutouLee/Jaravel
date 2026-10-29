package io.jaravel.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 事件总线(docs/10 §2)。
 *
 * <pre>{@code
 * var bus = new EventBus(container::make)
 *         .register(generatedBindings)             // APT 生成的
 *         .listen(OrderPaid.class, e -> metrics.increment("orders.paid"));
 *
 * bus.dispatch(new OrderPaid(orderId, now));
 * }</pre>
 *
 * <h2>中止传播</h2>
 * <p>一个监听器返回 {@code false} 会让**后续**监听器不再执行
 * (docs/10 §2.3)。这是「风控拦下这笔订单」的表达方式。
 *
 * <p>一个必须说清的后果:**中止不会回滚已经跑过的监听器**。
 * 优先级更高的那些已经执行完了,它们的副作用还在。所以
 * 「可能中止」的监听器应当排在最前面 —— 这正是 {@code priority}
 * 存在的理由。
 *
 * <p>另一个后果:**已入队的队列化监听器不会被取消**。它们在分发时
 * 就投进队列了,中止只影响还没执行的同步监听器。总线在
 * debug 模式下会提醒这一点,因为「中止了但邮件还是发出去了」
 * 是个会被当成 bug 的行为。
 *
 * <h2>异常不被吞</h2>
 * <p>同步监听器抛异常时**原样向上抛**,后续监听器不执行。
 *
 * <p>另一种设计是「记录日志并继续」。没选它的理由:那会让
 * {@code Event.dispatch(new OrderPaid(...))} 这一行在一个监听器
 * 坏掉时**看起来成功了**,而订单状态与仓库状态从此不一致。
 * 事件不是「发射后不管」—— 同步监听器是调用方事务的一部分。
 *
 * <p>真的想要「坏了也不影响主流程」的监听器,把它做成队列化的:
 * 那时失败有 failed_jobs 兜着,而且语义是显式的。
 */
public final class EventBus {

    /** 类型 → 实例。实践中是容器的 make */
    private final Function<Class<?>, Object> resolver;

    /** 事件类型 → 绑定。按优先级排好序 */
    private final Map<Class<?>, List<Registered>> byType = new ConcurrentHashMap<>();

    /** 通配监听。见 {@link #listen(String, BiConsumer)} 的注释 */
    private final List<WildcardListener> wildcards = new CopyOnWriteArrayList<>();

    /** 队列化监听器的投递通道。没装时遇到队列化监听器就报错 */
    private QueuedListenerDispatcher queueDispatcher;

    /** 当前在不在数据库事务里 + 注册提交后回调。没装时 afterCommit 退化成立即 */
    private TransactionHook transactions = TransactionHook.NONE;

    private FakeRecorder fake;

    public EventBus(Function<Class<?>, Object> resolver) {
        this.resolver = resolver;
    }

    /** 不解析任何依赖 —— 只能用闭包监听器。单元测试用 */
    public static EventBus standalone() {
        return new EventBus(type -> {
            throw new IllegalStateException("""
                    这个 EventBus 没有容器,无法实例化监听器 %s。
                      单元测试里用 bus.listen(Event.class, e -> ...) 注册闭包,
                      或者给它一个 resolver。"""
                    .formatted(type.getSimpleName()));
        });
    }

    // ================= 注册 =================

    /** 装上 APT 生成的绑定 */
    public EventBus register(List<ListenerBinding> bindings) {
        bindings.forEach(this::register);
        return this;
    }

    public EventBus register(ListenerBinding binding) {
        byType.compute(binding.eventType(), (type, existing) -> {
            var list = existing == null ? new ArrayList<Registered>() : new ArrayList<>(existing);
            list.add(Registered.of(binding));

            // 排序在**注册时**做,不在分发时做。分发是热路径,
            // 而注册只发生一次。大的先跑
            list.sort(Comparator.comparingInt((Registered r) -> r.priority()).reversed());
            return List.copyOf(list);
        });
        return this;
    }

    /**
     * 闭包监听器。
     *
     * <p>用途:一行就能说完的事(打点、日志),不值得为它建一个类。
     *
     * <p>**闭包监听器不能队列化** —— 理由与队列的 catch 回调相同:
     * Java 的 lambda 跨不过队列(见 {@code PendingChain} 的类注释)。
     * 需要异步就写一个 {@code @Listener implements ShouldQueue} 的类。
     */
    public <E> EventBus listen(Class<E> eventType, Consumer<E> handler) {
        return listen(eventType, 0, handler);
    }

    @SuppressWarnings("unchecked")
    public <E> EventBus listen(Class<E> eventType, int priority, Consumer<E> handler) {
        return register(new ClosureBinding(eventType, priority,
                (listener, event) -> {
                    ((Consumer<Object>) handler).accept(event);
                    return true;
                }));
    }

    /** 能中止传播的闭包监听器 —— 返回 false 则后续不执行 */
    @SuppressWarnings("unchecked")
    public <E> EventBus listenAbortable(Class<E> eventType, int priority,
                                        Function<E, Boolean> handler) {
        return register(new ClosureBinding(eventType, priority,
                (listener, event) -> ((Function<Object, Boolean>) handler).apply(event)));
    }

    /**
     * 通配监听(docs/10 §2.2 的最后一行)。
     *
     * <pre>{@code
     * bus.listen("com.app.events.Order*", (name, event) -> audit(name, event));
     * }</pre>
     *
     * <h2>与设计文档的偏差</h2>
     * <p>文档里写的是 {@code Event.listen("order.*", ...)} —— 那是 Laravel 的
     * 形式,它的事件可以用**字符串名**分发({@code Event::dispatch('order.paid')})。
     *
     * <p>Jaravel 的事件是 record,没有字符串名。硬给每个事件编一个名字
     * 意味着多一个要维护的映射,而那个映射写错时的症状是
     * 「监听器静默不触发」。
     *
     * <p>所以这里匹配的是**类名**:完全限定名或简单名都试一次,
     * {@code *} 是通配符。{@code "Order*"} 能匹配 {@code OrderPaid}。
     *
     * <p>⚠ 通配监听**不参与中止传播**,也拿不到类型。它适合审计、
     * 打点这类横切用途。要对一个事件做判断就用具体类型注册 ——
     * 一个 {@code (name, Object)} 的回调里做 {@code instanceof}
     * 等于把编译器能查的东西挪到运行时。
     */
    public EventBus listen(String classNamePattern, BiConsumer<String, Object> handler) {
        wildcards.add(new WildcardListener(compile(classNamePattern), handler));
        return this;
    }

    private static Pattern compile(String glob) {
        var regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') regex.append(".*");
            else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        return Pattern.compile(regex.toString());
    }

    // ================= 装配 =================

    public EventBus queueDispatcher(QueuedListenerDispatcher dispatcher) {
        this.queueDispatcher = dispatcher;
        return this;
    }

    public EventBus transactions(TransactionHook hook) {
        this.transactions = hook == null ? TransactionHook.NONE : hook;
        return this;
    }

    // ================= 分发 =================

    /**
     * 分发一个事件。
     *
     * @return 是否跑完了所有监听器。false = 被某个监听器中止了
     */
    public boolean dispatch(Object event) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "不能分发 null 事件。要表达「什么都没发生」就别调 dispatch。");
        }

        if (fake != null) {
            fake.record(event);
            // fake 下**不执行任何监听器**,包括队列化的。
            // 否则 Event.fake() 的用途(「别真的发邮件」)就失效了
            return true;
        }

        return deliver(event);
    }

    public boolean dispatchIf(boolean condition, Object event) {
        return !condition || dispatch(event);
    }

    public boolean dispatchUnless(boolean condition, Object event) {
        return condition || dispatch(event);
    }

    /**
     * 事务提交后再分发。
     *
     * <p>用途与队列的 {@code afterCommit} 相同:事务回滚了,
     * 那个事件描述的事情**没有发生过**,监听器不该跑。
     *
     * <p>不在事务里时**立即分发** —— 而不是报错。理由:同一段业务代码
     * 可能既被包在事务里调用、又被直接调用(比如一个 CLI 命令),
     * 为此要求调用方先判断事务状态是把框架的问题推给用户。
     *
     * @return 恒为 true 时表示「已安排」。真在事务里时监听器还没跑,
     *         所以这里**不可能**返回中止结果 —— 想要中止语义就用
     *         {@link #dispatch} 并自己管事务边界
     */
    public boolean dispatchAfterCommit(Object event) {
        if (fake != null) {
            fake.record(event);
            return true;
        }

        if (!transactions.inTransaction()) {
            return deliver(event);
        }

        transactions.afterCommit(() -> deliver(event));
        return true;
    }

    private boolean deliver(Object event) {
        notifyWildcards(event);

        // 按事件的**实际类型**查。不做父类/接口匹配 ——
        // 见 listenersFor 的注释
        for (Registered registered : listenersFor(event.getClass())) {
            if (registered.queued()) {
                dispatchToQueue(registered, event);
                continue;
            }

            Object listener = registered.isClosure() ? null
                    : resolveListener(registered.binding().listenerType());

            if (!registered.binding().invoke(listener, event)) {
                // 中止。已经跑过的监听器不回滚,已入队的也不取消 ——
                // 见类注释
                return false;
            }
        }
        return true;
    }

    /**
     * 这个事件类型有哪些监听器。
     *
     * <p>**只按精确类型匹配**,不往父类/接口上找。
     *
     * <p>这是一个刻意的限制。支持继承匹配的话,
     * {@code listen(Object.class, ...)} 会收到所有事件 ——
     * 那正是通配监听的用途,而通配监听有明确的「不参与中止、
     * 拿不到类型」的约束。让继承匹配走具体类型的路径会让一个
     * {@code listen(BaseEvent.class, ...)} 悄悄获得中止所有事件的能力。
     *
     * <p>事件是 record,record 不能被继承,所以这个限制在实践中
     * 几乎不会被碰到 —— 这也是「事件用 record」的一个附带好处。
     */
    private List<Registered> listenersFor(Class<?> eventType) {
        return byType.getOrDefault(eventType, List.of());
    }

    private void notifyWildcards(Object event) {
        if (wildcards.isEmpty()) return;

        String fqn = event.getClass().getName();
        String simple = event.getClass().getSimpleName();

        for (WildcardListener w : wildcards) {
            if (w.pattern().matcher(fqn).matches()
                    || w.pattern().matcher(simple).matches()) {
                w.handler().accept(fqn, event);
            }
        }
    }

    private Object resolveListener(Class<?> type) {
        try {
            return resolver.apply(type);
        } catch (RuntimeException e) {
            throw new ListenerResolutionException("""
                    无法实例化监听器 %s:%s
                      监听器由容器创建 —— 给它加 @Component,
                      或在 @Configuration 里提供一个 @Bean。"""
                    .formatted(type.getName(), e.getMessage()), e);
        }
    }

    private void dispatchToQueue(Registered registered, Object event) {
        if (queueDispatcher == null) {
            throw new IllegalStateException("""
                    监听器 %s 声明了 ShouldQueue,但事件总线没有队列通道。
                      原因通常是 jaravel-queue 不在 classpath 上。
                      修复:引入 jaravel-queue,或者把这个监听器的
                            ShouldQueue 去掉(它会变成同步的)。
                      这里报错而不是退回同步执行 —— 一个本该异步的监听器
                      悄悄变成同步的,症状是接口莫名变慢,极难归因。"""
                    .formatted(registered.binding().listenerType().getName()));
        }
        queueDispatcher.dispatch(registered.binding(), event);
    }

    // ================= 测试 =================

    /**
     * 进入 fake 模式:记录分发,**不执行任何监听器**。
     *
     * <pre>{@code
     * Event.fake();
     * service.pay(order);
     * Event.assertDispatched(OrderPaid.class);
     * }</pre>
     *
     * <p>连队列化监听器也不投 —— 否则 {@code Event.fake()} 的主要用途
     * (「测试里别真的发邮件」)就失效了。
     */
    public EventBus fake() {
        this.fake = new FakeRecorder();
        return this;
    }

    /** 只假装一部分事件,其余照常分发 */
    public EventBus fakeOnly(List<Class<?>> types) {
        this.fake = new FakeRecorder(types, this::deliver);
        return this;
    }

    public EventBus stopFaking() {
        this.fake = null;
        return this;
    }

    public boolean isFaking() {
        return fake != null;
    }

    public FakeRecorder recorder() {
        if (fake == null) {
            throw new IllegalStateException("""
                    当前不在 fake 模式,没有记录可查。
                      在被测代码**之前**调 Event.fake() ——
                      之后调的话那次分发已经真的发生了。""");
        }
        return fake;
    }

    // ================= 内部类型 =================

    /** 一条注册。闭包绑定与 APT 绑定走同一条路径 */
    private record Registered(ListenerBinding binding, boolean isClosure) {
        static Registered of(ListenerBinding binding) {
            return new Registered(binding, binding instanceof ClosureBinding);
        }

        int priority() {
            return binding.priority();
        }

        boolean queued() {
            return binding.queued();
        }
    }

    /** 闭包监听器。{@code listener} 参数被忽略 —— 闭包自带上下文 */
    private record ClosureBinding(Class<?> eventType, int priority,
                                  java.util.function.BiFunction<Object, Object, Boolean> body)
            implements ListenerBinding {

        @Override
        public Class<?> listenerType() {
            // 闭包没有监听器类。返回事件类型让错误消息至少能定位到事件
            return eventType;
        }

        @Override
        public boolean invoke(Object listener, Object event) {
            return body.apply(listener, event);
        }

        @Override
        public String describe() {
            return "闭包监听器(" + eventType.getSimpleName() + ")";
        }
    }

    private record WildcardListener(Pattern pattern, BiConsumer<String, Object> handler) {
    }

    /** 事务钩子。{@code jaravel-database} 不在 classpath 时用 NONE */
    public interface TransactionHook {

        TransactionHook NONE = new TransactionHook() {
            @Override public boolean inTransaction() { return false; }

            @Override public void afterCommit(Runnable action) { action.run(); }
        };

        boolean inTransaction();

        void afterCommit(Runnable action);
    }

    /** 把队列化监听器投进队列。由 starter 装配 */
    public interface QueuedListenerDispatcher {
        void dispatch(ListenerBinding binding, Object event);
    }

    /** 监听器实例化不出来。**不可重试** —— 代码不改,再试也一样 */
    public static class ListenerResolutionException extends RuntimeException {
        public ListenerResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * fake 模式下的记录。
     *
     * <p>按类型分组存着,{@code assertDispatched} 直接查。
     */
    public static final class FakeRecorder {

        private final Map<Class<?>, List<Object>> dispatched = new LinkedHashMap<>();

        /** 只假装这些类型;null = 全部 */
        private final List<Class<?>> only;
        private final Consumer<Object> passthrough;

        FakeRecorder() {
            this(null, null);
        }

        FakeRecorder(List<Class<?>> only, Function<Object, Boolean> passthrough) {
            this.only = only;
            this.passthrough = passthrough == null ? null : passthrough::apply;
        }

        synchronized void record(Object event) {
            boolean faked = only == null || only.contains(event.getClass());

            if (!faked) {
                // 不在 fake 列表里 —— 照常分发。
                // 仍然记一笔:assertDispatched 对它也该成立
                dispatched.computeIfAbsent(event.getClass(), t -> new ArrayList<>()).add(event);
                if (passthrough != null) passthrough.accept(event);
                return;
            }
            dispatched.computeIfAbsent(event.getClass(), t -> new ArrayList<>()).add(event);
        }

        public synchronized List<Object> of(Class<?> type) {
            return List.copyOf(dispatched.getOrDefault(type, List.of()));
        }

        public synchronized int count(Class<?> type) {
            return of(type).size();
        }

        public synchronized List<Class<?>> types() {
            return List.copyOf(dispatched.keySet());
        }

        public synchronized Optional<Object> first(Class<?> type) {
            var list = of(type);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }
}
