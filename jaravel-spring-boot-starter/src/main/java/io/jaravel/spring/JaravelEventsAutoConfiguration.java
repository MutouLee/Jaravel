package io.jaravel.spring;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.database.Connection;
import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.events.ListenerBinding;
import io.jaravel.events.ListenerIndex;

/**
 * 事件的自动装配(docs/10 §2)。
 *
 * <pre>{@code
 * events:
 *   bridge-spring: false      # 让 Spring 的 @EventListener 也能收到 Jaravel 事件
 * }</pre>
 *
 * <h2>装配顺序里的一个真实约束</h2>
 * <p>总线必须在**任何可能分发事件的东西**之前就绪。而「可能分发事件」
 * 的范围比直觉大:模型的 saved 钩子、认证的 Login、缓存的 CacheHit ——
 * 它们都在请求路径上,但也可能在启动期被触发(比如一个
 * {@code @PostConstruct} 里查了一次库)。
 *
 * <p>所以 {@code Event.use(bus)} 在 bean 建好时立刻调用,
 * 而不是等一个 {@code ApplicationReadyEvent}。
 */
@AutoConfiguration(after = {JaravelAutoConfiguration.class, JaravelQueueAutoConfiguration.class})
public class JaravelEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventBus jaravelEventBus(
            Container container, ConfigRepository config,
            ObjectProvider<Connection> connection,
            ObjectProvider<ApplicationEventPublisher> springPublisher,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var bus = new EventBus(container::make);

        // APT 生成的绑定。读一个文本文件 + newInstance,
        // 没有包扫描也没有方法反射
        List<ListenerBinding> bindings = ListenerIndex.load(
                getClass().getClassLoader());
        bus.register(bindings);

        wireTransactions(bus, connection);
        wireQueue(bus, container);
        bridgeToSpring(bus, config, springPublisher);

        Event.use(bus);
        return bus;
    }

    /**
     * 事务钩子。
     *
     * <p>无库应用拿不到 {@link Connection} —— 那时
     * {@code dispatchAfterCommit} 退化成立即分发(见
     * {@link EventBus#dispatchAfterCommit})。不报错:
     * 一个无库应用用事件是完全合理的。
     */
    private void wireTransactions(EventBus bus, ObjectProvider<Connection> connection) {
        Connection db = connection.getIfAvailable();
        if (db == null) return;

        bus.transactions(new EventBus.TransactionHook() {
            @Override
            public boolean inTransaction() {
                return db.inTransaction();
            }

            @Override
            public void afterCommit(Runnable action) {
                db.afterCommit(action);
            }
        });
    }

    /**
     * 队列化监听器的投递通道。
     *
     * <p>用反射判断 {@code jaravel-queue} 在不在 classpath 上 ——
     * **不能**直接 import：starter 对 queue 是可选依赖,
     * 直接引用会在 queue 缺失时让这个类加载失败,
     * 而那会连带整个事件系统起不来。
     *
     * <p>没有队列时**不装通道**:总线在真遇到队列化监听器时报错
     * (见 {@link EventBus} 里那条消息),而不是悄悄同步执行。
     */
    private void wireQueue(EventBus bus, Container container) {
        if (!classPresent("io.jaravel.queue.Queue")) return;

        bus.queueDispatcher(QueuedListenerBridge.create(container));
    }

    /**
     * 把 Jaravel 事件转发给 Spring 的事件机制(docs/10 §2.4)。
     *
     * <p>默认**关闭**。理由:开着的话每个 Jaravel 事件都会多一次
     * Spring 的发布开销,而大多数应用不需要两套事件系统互通。
     *
     * <p>开启后 Spring 的 {@code @EventListener} 能收到 Jaravel 事件。
     * 反方向(Jaravel 监听器收 Spring 事件)**不做** ——
     * 那需要一个 Spring 监听器把所有事件灌进 Jaravel 总线,
     * 于是 Spring 自己的框架事件(ContextRefreshed 等)
     * 也会进来,把 Jaravel 侧的通配监听淹掉。
     *
     * <p>用通配监听实现:它天然不参与中止传播,正好符合
     * 「桥接不该改变主路径的行为」。
     */
    private void bridgeToSpring(EventBus bus, ConfigRepository config,
                                ObjectProvider<ApplicationEventPublisher> publisher) {
        if (!config.bool("events.bridge-spring", false)) return;

        ApplicationEventPublisher spring = publisher.getIfAvailable();
        if (spring == null) return;

        bus.listen("*", (name, event) -> spring.publishEvent(event));
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, JaravelEventsAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 队列化监听器 → 队列任务的适配。
     *
     * <p>单独一个类,好让它只在 {@code jaravel-queue} 在 classpath 上时
     * 才被加载 —— 它直接引用了 queue 的类型。
     */
    static final class QueuedListenerBridge {

        private QueuedListenerBridge() {
        }

        static EventBus.QueuedListenerDispatcher create(Container container) {
            return (binding, event) -> {
                var serializer = container.make(io.jaravel.queue.JobSerializer.class);

                Map<String, Object> data;
                try {
                    data = serializer.toMap(event);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("""
                            事件 %s 无法序列化,而监听器 %s 声明了 ShouldQueue ——
                            队列化监听器要求事件能 JSON 往返。
                              原始错误:%s

                              两条出路:
                                1. 把事件里的模型换成主键
                                   (record OrderPaid(Long orderId) 而不是
                                    record OrderPaid(Order order))——
                                   推荐,与 Job「只存主键」的理由相同:
                                   模型快照在几分钟后就不是库里的状态了;
                                2. 去掉这个监听器的 ShouldQueue,让它同步执行。

                              注意这个事件作为**同步**事件是完全正常的 ——
                              是这个监听器让它需要被序列化。"""
                            .formatted(event.getClass().getName(),
                                       binding.listenerType().getName(),
                                       e.getMessage()), e);
                }

                io.jaravel.queue.Queue.dispatch(new ListenerJob(
                        binding.listenerType().getName(),
                        event.getClass().getName(),
                        data));
            };
        }
    }

    /**
     * 执行一个队列化监听器的任务。
     *
     * <p>字段是 public 且非 final 的,因为它要被 Jackson 反序列化出来 ——
     * 而 {@code JobSerializer} 的可见性设成了 ANY,所以不需要 setter。
     *
     * <p>**不复用 {@code QueuedListenerJob} record** 作为任务本体:
     * 那个 record 在 jaravel-events 里,而 events 对 queue 是可选依赖,
     * 它没法 implements ShouldQueue。这个类在 starter 里,
     * 两边都能看到。
     */
    public static class ListenerJob implements io.jaravel.queue.ShouldQueue {

        public String listenerClass;
        public String eventClass;
        public Map<String, Object> eventData;

        /** Jackson 需要 —— 反序列化走无参构造 + 字段注入 */
        public ListenerJob() {
        }

        public ListenerJob(String listenerClass, String eventClass,
                           Map<String, Object> eventData) {
            this.listenerClass = listenerClass;
            this.eventClass = eventClass;
            this.eventData = eventData;
        }

        @Override
        public String displayName() {
            return "Listener " + new io.jaravel.events.QueuedListenerJob(
                    listenerClass, eventClass, eventData).displayName();
        }

        /**
         * 在 worker 里跑那个监听器。
         *
         * <p>这里**必须**重新找一遍绑定,不能把绑定对象序列化进 payload ——
         * 绑定是 APT 生成的类,它在 worker 进程里一样存在,
         * 按「监听器类 + 事件类」查回来比序列化一个类引用更稳:
         * 后者在类重命名后会变成一个无法反序列化的 payload。
         */
        public void handle(Container container, io.jaravel.queue.JobSerializer serializer) {
            Class<?> eventType = loadClass(eventClass, "事件");
            Class<?> listenerType = loadClass(listenerClass, "监听器");

            Object event = serializer.convert(eventData, eventType);

            ListenerBinding binding = ListenerIndex.load(getClass().getClassLoader())
                    .stream()
                    .filter(b -> b.listenerType().equals(listenerType)
                            && b.eventType().equals(eventType))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("""
                            找不到 %s ← %s 的监听器绑定。
                              这条任务是用一个旧版本的代码投递的,而那之后
                              这个监听器的 handle 签名变了或被删了。
                              处理:jaravel queue:forget 掉它,
                                    或者把那个 handle 加回来。"""
                            .formatted(listenerClass, eventClass)));

            // 返回值(中止传播)在这里**没有意义** —— 同步监听器早就跑完了,
            // 这条任务是异步的。所以忽略它,而不是假装它有作用
            binding.invoke(container.make(listenerType), event);
        }

        private static Class<?> loadClass(String name, String what) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("""
                        队列里的%s类 %s 不存在了。
                          它被重命名或删除了,而队列里还有引用它的任务。
                          处理:jaravel queue:forget,或把类名改回来。"""
                        .formatted(what, name), e);
            }
        }
    }
}
