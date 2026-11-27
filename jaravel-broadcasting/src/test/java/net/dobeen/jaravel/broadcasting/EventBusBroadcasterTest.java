package net.dobeen.jaravel.broadcasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.events.Event;
import net.dobeen.jaravel.events.EventBus;
import net.dobeen.jaravel.queue.JobSerializer;
import net.dobeen.jaravel.queue.Queue;
import net.dobeen.jaravel.queue.QueueManager;
import net.dobeen.jaravel.queue.QueueStore;
import net.dobeen.jaravel.queue.QueuedJob;
import net.dobeen.jaravel.queue.ShouldQueue;

/**
 * 事件 → 广播的桥(docs/19 §2)。
 */
class EventBusBroadcasterTest {

    record OrderPaid(Long orderId) implements ShouldBroadcast {
        @Override public List<String> broadcastOn() {
            return List.of("orders");
        }
    }

    record PlainEvent(String what) {
    }

    record QuietEvent(Long id) implements ShouldBroadcast {
        @Override public List<String> broadcastOn() {
            return List.of("orders");
        }

        @Override public boolean broadcastWhen() {
            return false;
        }
    }

    record FullEvent(Long id) implements ShouldBroadcast {
        @Override public List<String> broadcastOn() {
            return List.of("orders", "private-users." + id);
        }

        @Override public Map<String, Object> broadcastWith() {
            return Map.of("only", id);
        }
    }

    record EmptyPayload(Long id) implements ShouldBroadcast {
        @Override public List<String> broadcastOn() {
            return List.of("orders");
        }

        @Override public Map<String, Object> broadcastWith() {
            return Map.of();
        }
    }

    private BroadcastManager manager;
    private EventBus bus;

    @BeforeEach
    void setUp() {
        manager = new BroadcastManager();
        bus = EventBus.standalone()
                .broadcaster(new EventBusBroadcaster(manager));
        Event.use(bus);
    }

    @AfterEach
    void tearDown() {
        Event.reset();
        Broadcast.reset();
        Queue.reset();
    }

    @Nested
    @DisplayName("过滤")
    class Filtering {

        @Test
        @DisplayName("不是 ShouldBroadcast 的事件不广播")
        void plainEventPassesThrough() {
            bus.dispatch(new PlainEvent("x"));

            assertThat(manager.recorderOrNull()).isNull();
        }

        @Test
        @DisplayName("broadcastWhen() = false 时不广播")
        void quietEventNotBroadcast() {
            bus.dispatch(new QuietEvent(1L));

            assertThat(manager.recorderOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("payload")
    class Payload {

        @Test
        @DisplayName("默认取事件全部字段")
        void allFieldsByDefault() {
            manager.fake();
            bus.dispatch(new OrderPaid(7L));

            var entry = manager.recorder().all().get(0);
            assertThat(entry.event()).isEqualTo("OrderPaid");
            assertThat(entry.channel()).isEqualTo("orders");
            assertThat(entry.data()).containsEntry("orderId", 7L);
        }

        @Test
        @DisplayName("broadcastWith() 接管 payload,哪怕是空 Map")
        void explicitPayloadWins() {
            manager.fake();
            bus.dispatch(new FullEvent(7L));
            bus.dispatch(new EmptyPayload(7L));

            // FullEvent 广播到两个频道,占前两条;EmptyPayload 是第三条
            assertThat(manager.recorder().all().get(0).data()).containsEntry("only", 7L);
            assertThat(manager.recorder().all().get(2).data()).isEmpty();
        }

        @Test
        @DisplayName("推送到 broadcastOn() 声明的每个频道")
        void multipleChannels() {
            manager.fake();
            bus.dispatch(new FullEvent(7L));

            assertThat(manager.recorder().all()).hasSize(2);
            assertThat(manager.recorder().all().get(0).channel()).isEqualTo("orders");
            assertThat(manager.recorder().all().get(1).channel()).isEqualTo("private-users.7");
        }

        @Test
        @DisplayName("不可序列化的字段在分发时抛,错误信息给出修复指引")
        void unserializableFailsAtDispatch() {
            // new Object() 是「空 bean」的经典形态 —— FAIL_ON_EMPTY_BEANS
            // 一定会拒绝它。lambda 反而会被静默序列化成 {},
            // Thread 会变成一片 null —— 都测不出这个失败路径
            record Bad(Object o) implements ShouldBroadcast {
                @Override public List<String> broadcastOn() {
                    return List.of("orders");
                }
            }

            assertThatThrownBy(() -> bus.dispatch(new Bad(new Object())))
                    .isInstanceOf(PayloadJson.UnserializablePayloadException.class)
                    .hasMessageContaining("只存主键");
        }
    }

    @Nested
    @DisplayName("与事件总线的语义联动")
    class EventBusSemantics {

        @Test
        @DisplayName("被监听器中止 → 不广播")
        void abortSkipsBroadcast() {
            bus.listenAbortable(OrderPaid.class, 10, e -> false);
            manager.fake();

            bus.dispatch(new OrderPaid(7L));

            assertThat(manager.recorder().all()).isEmpty();
        }

        @Test
        @DisplayName("Event.fake() → 不广播")
        void eventFakeSkipsBroadcast() {
            bus.fake();

            bus.dispatch(new OrderPaid(7L));

            Event.assertDispatched(OrderPaid.class);
        }

        @Test
        @DisplayName("Broadcast.fake() → 事件真跑,广播被记录")
        void broadcastFakeRecords() {
            var listenerRan = new boolean[1];
            bus.listen(OrderPaid.class, e -> listenerRan[0] = true);
            manager.fake();

            bus.dispatch(new OrderPaid(7L));

            assertThat(listenerRan[0]).isTrue();
            assertThat(manager.recorder().to("orders")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("ShouldQueue 时入队")
    class Queued {

        record QueuedOrderPaid(Long orderId) implements ShouldBroadcast, ShouldQueue {
            @Override public List<String> broadcastOn() {
                return List.of("orders", "private-users." + orderId);
            }

            @Override public String queue() {
                return "broadcasts";
            }
        }

        record NullableEvent(Long id, String note) implements ShouldBroadcast, ShouldQueue {
            @Override public List<String> broadcastOn() {
                return List.of("orders");
            }
        }

        static class CapturingQueue implements QueueStore {
            final List<QueuedJob> pushed = new ArrayList<>();

            @Override public String name() { return "capture"; }
            @Override public String push(QueuedJob job) { pushed.add(job); return job.uuid(); }
            @Override public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
                return Optional.empty();
            }
            @Override public void delete(QueuedJob job) { }
            @Override public void release(QueuedJob job, Duration delay) { }
            @Override public long size(String queue) { return pushed.size(); }
            @Override public void clear(String queue) { pushed.clear(); }
        }

        @Test
        @DisplayName("★ 事件实现 ShouldQueue → 不在请求线程发布,而是入队")
        void queuedInsteadOfSynchronousPublish() {
            var queue = new CapturingQueue();
            Queue.use(new QueueManager(new JobSerializer())
                    .register("capture", queue)
                    .defaultConnection("capture"));
            manager.fake();

            bus.dispatch(new QueuedOrderPaid(7L));

            assertThat(manager.recorder().all())
                    .as("队列化之后 HTTP 请求线程一次发布会都不做")
                    .isEmpty();
            assertThat(queue.pushed).hasSize(1);

            QueuedJob envelope = queue.pushed.get(0);
            assertThat(envelope.jobClass()).isEqualTo(BroadcastJob.class.getName());
            assertThat(envelope.queue()).isEqualTo("broadcasts");
            assertThat(envelope.data().get("event")).isEqualTo("QueuedOrderPaid");
            assertThat(envelope.data().get("channels"))
                    .isEqualTo(List.of("orders", "private-users.7"));
        }

        @Test
        @DisplayName("★ payload 里有 null 值也能入队")
        void nullPayloadValuesSurvive() {
            var queue = new CapturingQueue();
            Queue.use(new QueueManager(new JobSerializer())
                    .register("capture", queue)
                    .defaultConnection("capture"));

            // Map.copyOf 在这里会 NPE —— 事件的可选字段为 null 很常见
            bus.dispatch(new NullableEvent(1L, null));

            assertThat(queue.pushed).hasSize(1);
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) queue.pushed.get(0).data().get("data");
            assertThat(data).containsKey("note");
            assertThat(data.get("note")).isNull();
        }

        @Test
        @DisplayName("worker 执行 BroadcastJob 时才逐频道发布")
        void jobPublishesOnHandle() {
            var queue = new CapturingQueue();
            var serializer = new JobSerializer();
            Queue.use(new QueueManager(serializer)
                    .register("capture", queue)
                    .defaultConnection("capture"));
            manager.fake();

            bus.dispatch(new QueuedOrderPaid(7L));

            Broadcast.use(manager);
            var job = (BroadcastJob) serializer.toJob(queue.pushed.get(0));
            job.handle();

            assertThat(manager.recorder().all())
                    .extracting(BroadcastManager.FakeRecorder.Entry::channel)
                    .containsExactly("orders", "private-users.7");
            assertThat(manager.recorder().all().get(0).data())
                    .containsEntry("orderId", 7L);
        }
    }
}
