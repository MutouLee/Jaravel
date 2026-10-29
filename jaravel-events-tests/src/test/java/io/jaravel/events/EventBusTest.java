package io.jaravel.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 事件总线:优先级、中止传播、事务后分发、通配、fake */
class EventBusTest {

    record OrderPaid(Long orderId, int amount) {}

    record OrderRefunded(Long orderId) {}

    static final List<String> LOG = new ArrayList<>();

    private EventBus bus;

    @BeforeEach
    void setUp() {
        LOG.clear();
        bus = EventBus.standalone();
        Event.use(bus);
    }

    @AfterEach
    void tearDown() {
        Event.reset();
    }

    // ================= 基本分发 =================

    @Nested
    @DisplayName("分发")
    class Dispatching {

        @Test
        @DisplayName("监听器收到事件")
        void listenersReceiveEvents() {
            bus.listen(OrderPaid.class, e -> LOG.add("paid:" + e.orderId()));

            assertThat(Event.dispatch(new OrderPaid(1L, 100))).isTrue();
            assertThat(LOG).containsExactly("paid:1");
        }

        @Test
        @DisplayName("★ 只按精确类型匹配,不往父类/接口上找")
        void noInheritanceMatching() {
            bus.listen(Object.class, e -> LOG.add("object:" + e));

            Event.dispatch(new OrderPaid(1L, 100));

            assertThat(LOG)
                    .as("支持继承匹配的话 listen(Object.class) 会悄悄获得"
                            + "中止所有事件的能力 —— 那是通配监听的用途,"
                            + "而通配监听明确不参与中止")
                    .isEmpty();
        }

        @Test
        @DisplayName("没有监听器时安静返回")
        void noListenersIsFine() {
            assertThat(Event.dispatch(new OrderPaid(1L, 100))).isTrue();
        }

        @Test
        @DisplayName("分发 null 报错")
        void nullEventFails() {
            assertThatThrownBy(() -> Event.dispatch(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("别调 dispatch");
        }

        @Test
        @DisplayName("dispatchIf / dispatchUnless")
        void conditionalDispatch() {
            bus.listen(OrderPaid.class, e -> LOG.add("paid"));

            Event.dispatchIf(false, new OrderPaid(1L, 1));
            Event.dispatchUnless(true, new OrderPaid(1L, 1));
            assertThat(LOG).isEmpty();

            Event.dispatchIf(true, new OrderPaid(1L, 1));
            Event.dispatchUnless(false, new OrderPaid(1L, 1));
            assertThat(LOG).hasSize(2);
        }
    }

    // ================= 优先级与中止 =================

    @Nested
    @DisplayName("优先级与中止传播")
    class Ordering {

        @Test
        @DisplayName("★ 优先级大的先跑")
        void higherPriorityFirst() {
            bus.listen(OrderPaid.class, 0, e -> LOG.add("normal"));
            bus.listen(OrderPaid.class, 100, e -> LOG.add("critical"));
            bus.listen(OrderPaid.class, 50, e -> LOG.add("middle"));

            Event.dispatch(new OrderPaid(1L, 1));

            assertThat(LOG).containsExactly("critical", "middle", "normal");
        }

        @Test
        @DisplayName("★ 返回 false 中止后续监听器")
        void falseAborts() {
            bus.listenAbortable(OrderPaid.class, 100, e -> {
                LOG.add("fraud-check");
                return false;                       // 拦下
            });
            bus.listen(OrderPaid.class, 0, e -> LOG.add("ship"));

            assertThat(Event.dispatch(new OrderPaid(1L, 1)))
                    .as("dispatch 要把「被中止」告诉调用方")
                    .isFalse();
            assertThat(LOG).containsExactly("fraud-check");
        }

        @Test
        @DisplayName("★ 中止不会回滚已经跑过的监听器")
        void abortDoesNotUndo() {
            bus.listen(OrderPaid.class, 100, e -> LOG.add("already-ran"));
            bus.listenAbortable(OrderPaid.class, 50, e -> false);
            bus.listen(OrderPaid.class, 0, e -> LOG.add("never-ran"));

            Event.dispatch(new OrderPaid(1L, 1));

            assertThat(LOG)
                    .as("所以「可能中止」的监听器要排在最前面 —— "
                            + "这正是 priority 存在的理由")
                    .containsExactly("already-ran");
        }

        @Test
        @DisplayName("★ 监听器的异常原样抛出,不被吞")
        void exceptionsPropagate() {
            bus.listen(OrderPaid.class, 100, e -> {
                throw new IllegalStateException("监听器炸了");
            });
            bus.listen(OrderPaid.class, 0, e -> LOG.add("after"));

            assertThatThrownBy(() -> Event.dispatch(new OrderPaid(1L, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("监听器炸了");

            assertThat(LOG)
                    .as("「记日志并继续」会让 dispatch 这一行在监听器坏掉时"
                            + "看起来成功了,而两边状态从此不一致")
                    .isEmpty();
        }
    }

    // ================= 事务后分发 =================

    @Nested
    @DisplayName("事务后分发")
    class AfterCommit {

        @Test
        @DisplayName("★ 在事务里时推迟到提交后")
        void deferredInsideTransaction() {
            var pending = new ArrayList<Runnable>();
            bus.transactions(new EventBus.TransactionHook() {
                @Override public boolean inTransaction() { return true; }

                @Override public void afterCommit(Runnable action) { pending.add(action); }
            });
            bus.listen(OrderPaid.class, e -> LOG.add("paid"));

            Event.dispatchAfterCommit(new OrderPaid(1L, 1));

            assertThat(LOG)
                    .as("事务还没提交 —— 那件事可能根本没发生")
                    .isEmpty();

            pending.forEach(Runnable::run);
            assertThat(LOG).containsExactly("paid");
        }

        @Test
        @DisplayName("★ 不在事务里时立即分发,不报错")
        void immediateOutsideTransaction() {
            bus.listen(OrderPaid.class, e -> LOG.add("paid"));

            Event.dispatchAfterCommit(new OrderPaid(1L, 1));

            assertThat(LOG)
                    .as("同一段业务代码可能既在事务里调、也被 CLI 直接调,"
                            + "为此要求调用方先判断事务状态是把问题推给用户")
                    .containsExactly("paid");
        }

        @Test
        @DisplayName("没装事务钩子时等价于立即分发")
        void noHookMeansImmediate() {
            bus.listen(OrderPaid.class, e -> LOG.add("paid"));
            bus.transactions(null);

            Event.dispatchAfterCommit(new OrderPaid(1L, 1));
            assertThat(LOG).containsExactly("paid");
        }
    }

    // ================= 通配 =================

    @Nested
    @DisplayName("通配监听")
    class Wildcards {

        @Test
        @DisplayName("★ 按类名通配(与文档里的 order.* 的偏差)")
        void matchesByClassName() {
            bus.listen("Order*", (name, event) -> LOG.add("wild:" + name));

            Event.dispatch(new OrderPaid(1L, 1));
            Event.dispatch(new OrderRefunded(1L));

            assertThat(LOG).hasSize(2);
            assertThat(LOG.get(0)).endsWith("OrderPaid");
            assertThat(LOG.get(1)).endsWith("OrderRefunded");
        }

        @Test
        @DisplayName("完全限定名也能匹配")
        void matchesFullyQualified() {
            bus.listen("io.jaravel.events.*", (name, event) -> LOG.add("fqn"));

            Event.dispatch(new OrderPaid(1L, 1));
            assertThat(LOG).containsExactly("fqn");
        }

        @Test
        @DisplayName("不匹配的不触发")
        void nonMatchingIsSkipped() {
            bus.listen("Invoice*", (name, event) -> LOG.add("wild"));

            Event.dispatch(new OrderPaid(1L, 1));
            assertThat(LOG).isEmpty();
        }

        @Test
        @DisplayName("★ 通配监听不参与中止传播")
        void wildcardsCannotAbort() {
            bus.listen("Order*", (name, event) -> LOG.add("wild"));
            bus.listen(OrderPaid.class, e -> LOG.add("typed"));

            assertThat(Event.dispatch(new OrderPaid(1L, 1))).isTrue();
            assertThat(LOG).containsExactly("wild", "typed");
        }
    }

    // ================= APT 生成的绑定 =================

    @Nested
    @DisplayName("APT 生成的绑定")
    class GeneratedBindings {

        @Test
        @DisplayName("★ 索引里的绑定能被装上并真的跑起来")
        void indexedBindingsWork() {
            var bindings = ListenerIndex.load(getClass().getClassLoader());

            assertThat(bindings)
                    .as("APT 应当为本模块测试里的 @Listener 生成绑定")
                    .isNotEmpty();

            var registry = new java.util.HashMap<Class<?>, Object>();
            registry.put(Warehouse.class, new Warehouse());
            registry.put(Auditor.class, new Auditor());
            registry.put(FraudCheck.class, new FraudCheck());

            var wired = new EventBus(registry::get).register(bindings);

            wired.dispatch(new OrderPaid(7L, 100));

            assertThat(LOG)
                    .as("生成的 invoke 是一次直接调用,运行时零反射")
                    .contains("warehouse:7", "audit-paid:7");
        }

        @Test
        @DisplayName("★ 一个类监听多个事件 —— 多个 handle 各自一个绑定")
        void multipleHandlesPerListener() {
            var bindings = ListenerIndex.load(getClass().getClassLoader());

            var auditorBindings = bindings.stream()
                    .filter(b -> b.listenerType().equals(Auditor.class))
                    .toList();

            assertThat(auditorBindings)
                    .as("Auditor 有两个 handle,应当生成两个绑定 —— "
                            + "做成「一个类带一个 switch」就要在运行时按类型分派,"
                            + "那又回到反射方案的问题")
                    .hasSize(2);
            assertThat(auditorBindings.stream().map(ListenerBinding::eventType))
                    .containsExactlyInAnyOrder(OrderPaid.class, OrderRefunded.class);
        }

        @Test
        @DisplayName("★ void handle 由 APT 生成 return true,不会误中止")
        void voidHandleNeverAborts() {
            var binding = bindingFor(Warehouse.class, OrderPaid.class);

            assertThat(binding.invoke(new Warehouse(), new OrderPaid(1L, 1)))
                    .as("手写绑定时「忘了 return true」会让后续监听器全被跳过,"
                            + "而那个 bug 的症状是「某些监听器有时不触发」")
                    .isTrue();
        }

        @Test
        @DisplayName("★ boolean handle 的返回值被如实传递")
        void booleanHandleIsHonoured() {
            var binding = bindingFor(FraudCheck.class, OrderPaid.class);

            assertThat(binding.invoke(new FraudCheck(), new OrderPaid(1L, 1)))
                    .as("金额正常 → 放行")
                    .isTrue();
            assertThat(binding.invoke(new FraudCheck(), new OrderPaid(1L, 999_999)))
                    .as("金额异常 → 中止")
                    .isFalse();
        }

        @Test
        @DisplayName("优先级从注解读出来写死在绑定里")
        void priorityComesFromAnnotation() {
            assertThat(bindingFor(FraudCheck.class, OrderPaid.class).priority())
                    .isEqualTo(100);
            assertThat(bindingFor(Warehouse.class, OrderPaid.class).priority())
                    .isZero();
        }

        @Test
        @DisplayName("非队列化监听器的 queued() 是 false")
        void notQueuedByDefault() {
            assertThat(bindingFor(Warehouse.class, OrderPaid.class).queued())
                    .isFalse();
        }

        private ListenerBinding bindingFor(Class<?> listener, Class<?> event) {
            return ListenerIndex.load(getClass().getClassLoader()).stream()
                    .filter(b -> b.listenerType().equals(listener)
                            && b.eventType().equals(event))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "没有找到 " + listener.getSimpleName() + " → "
                                    + event.getSimpleName() + " 的绑定"));
        }
    }

    // ================= 队列化监听器 =================

    @Nested
    @DisplayName("队列化监听器")
    class Queued {

        @Test
        @DisplayName("★ 没有队列通道时报错,不退回同步执行")
        void missingQueueChannelFails() {
            var fake = new ListenerBinding() {
                @Override public Class<?> eventType() { return OrderPaid.class; }

                @Override public Class<?> listenerType() { return Warehouse.class; }

                @Override public boolean queued() { return true; }

                @Override public boolean invoke(Object l, Object e) { return true; }
            };
            bus.register(fake);

            assertThatThrownBy(() -> Event.dispatch(new OrderPaid(1L, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有队列通道")
                    .hasMessageContaining("接口莫名变慢");
        }

        @Test
        @DisplayName("★ 队列化监听器走投递通道,不在当前线程执行")
        void queuedListenersAreDispatched() {
            var dispatched = new ArrayList<String>();
            var fake = new ListenerBinding() {
                @Override public Class<?> eventType() { return OrderPaid.class; }

                @Override public Class<?> listenerType() { return Warehouse.class; }

                @Override public boolean queued() { return true; }

                @Override public boolean invoke(Object l, Object e) {
                    LOG.add("ran-inline");          // 不该发生
                    return true;
                }
            };
            bus.register(fake)
               .queueDispatcher((binding, event) ->
                       dispatched.add(binding.listenerType().getSimpleName()));

            Event.dispatch(new OrderPaid(1L, 1));

            assertThat(dispatched).containsExactly("Warehouse");
            assertThat(LOG).as("它该在 worker 里跑,不在这里").isEmpty();
        }
    }

    // ================= fake =================

    @Nested
    @DisplayName("Event.fake()")
    class Faking {

        @Test
        @DisplayName("★ fake 后监听器不执行,但断言能看到分发")
        void fakeRecordsWithoutRunning() {
            bus.listen(OrderPaid.class, e -> LOG.add("should-not-run"));

            Event.fake();
            Event.dispatch(new OrderPaid(3L, 100));

            assertThat(LOG).isEmpty();
            Event.assertDispatched(OrderPaid.class);
        }

        @Test
        @DisplayName("★ 连队列化监听器也不投")
        void fakeAlsoSkipsQueued() {
            var dispatched = new ArrayList<String>();
            bus.register(new ListenerBinding() {
                @Override public Class<?> eventType() { return OrderPaid.class; }

                @Override public Class<?> listenerType() { return Warehouse.class; }

                @Override public boolean queued() { return true; }

                @Override public boolean invoke(Object l, Object e) { return true; }
            }).queueDispatcher((b, e) -> dispatched.add("queued"));

            Event.fake();
            Event.dispatch(new OrderPaid(1L, 1));

            assertThat(dispatched)
                    .as("否则 Event.fake() 的主要用途(别真的发邮件)就失效了")
                    .isEmpty();
        }

        @Test
        @DisplayName("assertDispatched 带条件")
        void assertWithPredicate() {
            Event.fake();
            Event.dispatch(new OrderPaid(42L, 100));

            Event.assertDispatched(OrderPaid.class, (OrderPaid e) -> e.orderId() == 42L);

            assertThatThrownBy(() -> Event.assertDispatched(
                    OrderPaid.class, (OrderPaid e) -> e.orderId() == 99L))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有一次满足给定条件");
        }

        @Test
        @DisplayName("assertDispatched 的次数断言")
        void assertCount() {
            Event.fake();
            Event.dispatch(new OrderPaid(1L, 1));
            Event.dispatch(new OrderPaid(2L, 1));

            Event.assertDispatched(OrderPaid.class, 2);
            assertThatThrownBy(() -> Event.assertDispatched(OrderPaid.class, 3))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("实际 2 次");
        }

        @Test
        @DisplayName("assertNotDispatched / assertNothingDispatched")
        void negativeAssertions() {
            Event.fake();
            Event.assertNothingDispatched();
            Event.assertNotDispatched(OrderPaid.class);

            Event.dispatch(new OrderPaid(1L, 1));

            assertThatThrownBy(Event::assertNothingDispatched)
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> Event.assertNotDispatched(OrderPaid.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("被分发了 1 次");
        }

        @Test
        @DisplayName("★ 失败消息提醒「可能 fake 调晚了」")
        void messageHintsAboutLateFake() {
            Event.fake();

            assertThatThrownBy(() -> Event.assertDispatched(OrderPaid.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("之前**分发的");
        }

        @Test
        @DisplayName("fake 部分事件,其余照常分发")
        void partialFake() {
            bus.listen(OrderPaid.class, e -> LOG.add("paid-ran"));
            bus.listen(OrderRefunded.class, e -> LOG.add("refunded-ran"));

            Event.fake(OrderPaid.class);

            Event.dispatch(new OrderPaid(1L, 1));
            Event.dispatch(new OrderRefunded(1L));

            assertThat(LOG).containsExactly("refunded-ran");
            Event.assertDispatched(OrderPaid.class);
            Event.assertDispatched(OrderRefunded.class);
        }

        @Test
        @DisplayName("不在 fake 模式时查记录会报错而不是返回空")
        void assertingWithoutFakeFails() {
            assertThatThrownBy(() -> Event.assertDispatched(OrderPaid.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不在 fake 模式");
        }

        @Test
        @DisplayName("stopFaking 之后恢复真实分发")
        void stopFaking() {
            bus.listen(OrderPaid.class, e -> LOG.add("ran"));

            Event.fake();
            Event.dispatch(new OrderPaid(1L, 1));
            assertThat(LOG).isEmpty();

            Event.stopFaking();
            Event.dispatch(new OrderPaid(1L, 1));
            assertThat(LOG).containsExactly("ran");
        }
    }

    // ================= 容器解析 =================

    @Test
    @DisplayName("监听器实例化不出来时给出可操作的错误")
    void unresolvableListener() {
        bus.register(new ListenerBinding() {
            @Override public Class<?> eventType() { return OrderPaid.class; }

            @Override public Class<?> listenerType() { return Warehouse.class; }

            @Override public boolean invoke(Object l, Object e) { return true; }
        });

        assertThatThrownBy(() -> Event.dispatch(new OrderPaid(1L, 1)))
                .isInstanceOf(EventBus.ListenerResolutionException.class)
                .hasMessageContaining("@Component");
    }

    // ================= 测试用监听器 =================

    @Listener
    public static class Warehouse {
        public void handle(OrderPaid e) {
            LOG.add("warehouse:" + e.orderId());
        }
    }

    @Listener
    public static class Auditor {
        public void handle(OrderPaid e) {
            LOG.add("audit-paid:" + e.orderId());
        }

        public void handle(OrderRefunded e) {
            LOG.add("audit-refunded:" + e.orderId());
        }
    }

    /** 能中止传播的监听器 —— 所以排在最前面 */
    @Listener(priority = 100)
    public static class FraudCheck {
        public boolean handle(OrderPaid e) {
            return e.amount() < 500_000;
        }
    }

    /** 装箱 Boolean 返回 null 时不该中止 */
    @Listener
    public static class LenientCheck {
        public Boolean handle(OrderRefunded e) {
            LOG.add("lenient");
            return null;
        }
    }

    @Test
    @DisplayName("★ Boolean 返回 null 时不中止(把 null 当 false 会静默断链)")
    void nullBooleanDoesNotAbort() {
        var binding = ListenerIndex.load(getClass().getClassLoader()).stream()
                .filter(b -> b.listenerType().equals(LenientCheck.class))
                .findFirst().orElseThrow();

        assertThat(binding.invoke(new LenientCheck(), new OrderRefunded(1L))).isTrue();
    }

    /** 计数用,验证绑定对象是无状态的、可复用 */
    static final AtomicInteger UNUSED = new AtomicInteger();
}
