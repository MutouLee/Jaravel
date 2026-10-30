package io.jaravel.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.notifications.events.NotificationEvents;
import io.jaravel.support.Arr;

/**
 * 按名字管理渠道,并执行投递(docs/11 §2)。
 *
 * <h2>★ 每个渠道独立 —— docs/11 §2.6 的那条要求</h2>
 * <p>「单个渠道失败不影响其他渠道」。所以投递是一个**循环**,
 * 每个渠道各自 try/catch:
 *
 * <pre>
 * for (channel : via(notifiable)) {
 *     try   { channel.send(...) }        → NotificationSent
 *     catch { 记录 + 事件,**继续下一个** } → NotificationFailed
 * }
 * </pre>
 *
 * <p>于是一条通知可能同时产生一个 Failed 与两个 Sent。
 *
 * <p>**全部渠道都失败时才抛异常**给调用方 —— 部分成功不抛:
 * 抛的话调用方分不清「全挂了」与「Slack 挂了但邮件发出去了」,
 * 而后者通常不需要处理。
 *
 * <h2>shouldQueue 时每个渠道一条队列任务</h2>
 * <p>同一条要求的另一面。放一个任务里的话,第一个渠道的失败会让
 * 后两个永远不执行,而重试时又从第一个开始 ——
 * 于是邮件被发了三遍而短信一次都没发。
 */
public final class NotificationManager {

    private final Map<String, NotificationChannel> channels = new LinkedHashMap<>();

    /** 入队投递的通道。没装时 shouldQueue 退化为同步并警告一次 */
    private QueueDispatcher queue;

    private FakeRecorder fake;

    // ================= 注册 =================

    public NotificationManager register(NotificationChannel channel) {
        channels.put(channel.name(), channel);
        return this;
    }

    /** docs/11 §2.5 的 {@code Notification.extend("voice", VoiceChannel.class)} */
    public NotificationManager extend(String name, NotificationChannel channel) {
        channels.put(name, channel);
        return this;
    }

    public NotificationManager queueDispatcher(QueueDispatcher dispatcher) {
        this.queue = dispatcher;
        return this;
    }

    public java.util.Set<String> registeredChannels() {
        return java.util.Set.copyOf(channels.keySet());
    }

    private NotificationChannel channel(String name, Notification notification) {
        NotificationChannel channel = channels.get(name);
        if (channel != null) return channel;

        throw new IllegalStateException("""
                通知 %s 的 via() 里有 "%s",但没有这个渠道。%s
                  已注册:%s
                  自定义渠道用 Notifications.extend("%s", channel) 注册。
                  docs/11 §2.5 里的 broadcast(v0.6)与
                  dingtalk / wechat-work / feishu(扩展包)尚未实现。"""
                .formatted(notification.displayName(), name,
                           Arr.closest(name, channels.keySet().stream().toList())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           channels.keySet(), name));
    }

    // ================= 投递 =================

    /**
     * 发给一个收件人。
     *
     * @return 成功的渠道数
     */
    public int send(Notifiable notifiable, Notification notification) {
        List<String> via = notification.via(notifiable);

        if (via == null || via.isEmpty()) {
            warnEmptyVia(notification);
            return 0;
        }

        if (fake != null) {
            fake.record(notifiable, notification, via);
            return via.size();
        }

        if (notification.shouldQueue()) {
            return enqueue(notifiable, notification, via);
        }
        return deliverAll(notifiable, notification, via);
    }

    /** 发给一组收件人 —— 一人一条,互不影响 */
    public int send(Iterable<? extends Notifiable> notifiables, Notification notification) {
        int total = 0;
        for (Notifiable notifiable : notifiables) {
            total += send(notifiable, notification);
        }
        return total;
    }

    /**
     * 跳过队列,立即投递。
     *
     * <p>docs/11 §2.2 的 {@code Notification.sendNow}。
     * 用途:测试、以及「这条通知必须在返回前发出去」。
     */
    public int sendNow(Notifiable notifiable, Notification notification) {
        List<String> via = notification.via(notifiable);
        if (via == null || via.isEmpty()) {
            warnEmptyVia(notification);
            return 0;
        }
        if (fake != null) {
            fake.record(notifiable, notification, via);
            return via.size();
        }
        return deliverAll(notifiable, notification, via);
    }

    /**
     * 投递单个渠道 —— worker 从队列里取出来后走这条路径。
     *
     * @return 真的发出去了吗
     */
    public boolean deliverOne(Notifiable notifiable, Notification notification,
                              String channelName) {
        return deliver(notifiable, notification, channelName);
    }

    private int deliverAll(Notifiable notifiable, Notification notification,
                           List<String> via) {
        int succeeded = 0;
        RuntimeException lastFailure = null;
        int attempted = 0;

        for (String name : via) {
            attempted++;
            try {
                if (deliver(notifiable, notification, name)) succeeded++;
            } catch (RuntimeException e) {
                // **继续下一个渠道** —— docs/11 §2.6
                lastFailure = e;
            }
        }

        // 全挂了才抛。部分成功不抛 —— 调用方分不清「全挂了」与
        // 「Slack 挂了但邮件发出去了」,而后者通常不需要处理
        if (succeeded == 0 && lastFailure != null && attempted > 0) {
            throw lastFailure;
        }
        return succeeded;
    }

    private boolean deliver(Notifiable notifiable, Notification notification,
                            String channelName) {
        NotificationChannel channel = channel(channelName, notification);
        String type = notifiable.notifiableType();
        String id = safeId(notifiable);

        // shouldSend 在**真正要发的那一刻**判 —— 见它的 javadoc
        if (!notification.shouldSend(notifiable, channelName)) {
            skip(notification, channelName, type, id, "shouldSend() 返回 false");
            return false;
        }

        if (channel.requiresRoute()) {
            String route = notifiable.routeNotificationFor(channelName);
            if (route == null || route.isBlank()) {
                // 跳过而不是报错 —— 「有邮箱但没填手机号」是正常的。
                // 但一定发事件:否则它与「发了但没收到」分不开
                skip(notification, channelName, type, id,
                        "收件人在这个渠道上没有地址(routeNotificationFor 返回空)");
                return false;
            }
        }

        boolean proceed = io.jaravel.events.Event.listening(
                NotificationEvents.NotificationSending.class)
                ? io.jaravel.events.Event.bus().dispatch(
                        new NotificationEvents.NotificationSending(
                                notification.displayName(), channelName, type, id))
                : true;

        if (!proceed) {
            skip(notification, channelName, type, id, "被 NotificationSending 监听器挡下");
            return false;
        }

        long start = System.nanoTime();
        try {
            channel.send(notifiable, notification);
        } catch (RuntimeException e) {
            io.jaravel.events.Event.tryDispatchIfListening(
                    NotificationEvents.NotificationFailed.class,
                    () -> new NotificationEvents.NotificationFailed(
                            notification.displayName(), channelName, type, id, e));
            throw e;
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        io.jaravel.events.Event.tryDispatchIfListening(
                NotificationEvents.NotificationSent.class,
                () -> new NotificationEvents.NotificationSent(
                        notification.displayName(), channelName, type, id, elapsed));
        return true;
    }

    private int enqueue(Notifiable notifiable, Notification notification,
                        List<String> via) {
        if (queue == null) {
            warnNoQueue(notification);
            return deliverAll(notifiable, notification, via);
        }

        // **每个渠道一条任务** —— docs/11 §2.6
        for (String name : via) {
            queue.dispatch(notifiable, notification, name,
                    notification.queue(), notification.delay());
        }
        return via.size();
    }

    private void skip(Notification notification, String channel,
                      String type, String id, String reason) {
        io.jaravel.events.Event.tryDispatchIfListening(
                NotificationEvents.NotificationSkipped.class,
                () -> new NotificationEvents.NotificationSkipped(
                        notification.displayName(), channel, type, id, reason));
    }

    /**
     * {@code notifiableId()} 的默认实现会抛 —— 只有 database 渠道需要它。
     *
     * <p>所以事件里的 id 要容错:一个只用 mail 渠道的模型没实现它,
     * 而那不该让事件的构造失败(进而让整条通知失败)。
     */
    private static String safeId(Notifiable notifiable) {
        try {
            return String.valueOf(notifiable.notifiableId());
        } catch (RuntimeException e) {
            return "(未实现 notifiableId)";
        }
    }

    private static volatile boolean emptyViaWarned;

    private static void warnEmptyVia(Notification notification) {
        if (!io.jaravel.core.Application.isDebug() || emptyViaWarned) return;
        emptyViaWarned = true;

        System.err.printf("""

                [jaravel] 提示:通知 %s 的 via() 返回了空列表 —— 它什么都不会做。
                          这是合法的(「这个收件人不需要被通知」),
                          但一个**永远**返回空的 via() 通常是写错了。
                          (这条提示只打一次)
                %n""", notification.displayName());
    }

    private static volatile boolean noQueueWarned;

    private static void warnNoQueue(Notification notification) {
        if (noQueueWarned) return;
        noQueueWarned = true;

        System.err.printf("""

                [jaravel] 警告:通知 %s 声明了 shouldQueue(),但通知模块没有队列通道
                          (通常是 jaravel-queue 不在 classpath 上)。
                          它会**同步**投递 —— 一条通知可能要发三个渠道,
                          每个都是一次网络往返,于是请求会被加上数百毫秒。
                          引入 jaravel-queue,或者把 shouldQueue() 改成 false
                          (那样至少意图是显式的)。
                          (这条警告只打一次)
                %n""", notification.displayName());
    }

    // ================= 测试 =================

    public NotificationManager fake() {
        this.fake = new FakeRecorder();
        return this;
    }

    public NotificationManager stopFaking() {
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
                      在被测代码**之前**调 Notifications.fake()。""");
        }
        return fake;
    }

    /**
     * 把一个「收件人 + 通知 + 渠道」投进队列。
     *
     * <p>⚠ 这里的 {@code Notifiable} 与 {@code Notification}
     * 都要能穿过队列,而它们是**用户的对象**。
     *
     * <p>这与邮件的「投递时渲染」形成对比:邮件能在投递时把一切
     * 渲染完,而通知不能 —— 渠道的 {@code toMail} / {@code toSms}
     * 产出的是不同类型的东西,没有一个统一的「渲染结果」。
     *
     * <p>所以这里不得不序列化通知对象,于是**通知的字段要能 JSON 往返**
     * ——「只存主键」那条规矩在通知这里仍然适用。
     * 这个差异写在 starter 的实现里。
     */
    public interface QueueDispatcher {
        void dispatch(Notifiable notifiable, Notification notification,
                      String channel, String queueName, Duration delay);
    }

    /** fake 模式下的记录 */
    public static final class FakeRecorder {

        public record Entry(Notifiable notifiable, Notification notification,
                            List<String> channels) {
        }

        private final List<Entry> entries =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        void record(Notifiable notifiable, Notification notification,
                    List<String> channels) {
            entries.add(new Entry(notifiable, notification, List.copyOf(channels)));
        }

        public List<Entry> all() {
            return List.copyOf(entries);
        }

        public List<Entry> of(Class<? extends Notification> type) {
            return entries.stream().filter(e -> type.isInstance(e.notification())).toList();
        }

        public List<String> typeNames() {
            var out = new java.util.ArrayList<String>();
            for (Entry entry : entries) {
                String name = entry.notification().displayName();
                if (!out.contains(name)) out.add(name);
            }
            return List.copyOf(out);
        }
    }
}
