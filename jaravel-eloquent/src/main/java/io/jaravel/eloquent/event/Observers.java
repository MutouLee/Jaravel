package io.jaravel.eloquent.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 观察者注册表与事件分派。
 *
 * <p>按模型类型查表,**无反射**:钩子是接口方法的直接调用。
 */
public final class Observers {

    private Observers() {}

    private static final Map<Class<?>, List<Observer<?>>> REGISTRY = new ConcurrentHashMap<>();

    public static <M> void register(Class<M> modelType, Observer<M> observer) {
        REGISTRY.computeIfAbsent(modelType, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(observer);
    }

    public static void unregister(Class<?> modelType) {
        REGISTRY.remove(modelType);
    }

    /** 测试的 @AfterEach 应调用 */
    public static void clear() {
        REGISTRY.clear();
    }

    public static boolean hasObservers(Class<?> modelType) {
        List<Observer<?>> list = REGISTRY.get(modelType);
        return list != null && !list.isEmpty();
    }

    /**
     * 分派事件。
     *
     * @return 对可中止事件,任一观察者返回 false 即为 false(并**停止**后续分派);
     *         不可中止事件恒为 true。
     */
    @SuppressWarnings("unchecked")
    public static <M> boolean fire(ModelEvent event, M model) {
        if (!fireObservers(event, model)) return false;

        return fireOnBus(event, model);
    }

    /**
     * 把事件转给**事件总线**(docs/10 §2.5 的 ModelEvents)。
     *
     * <p>两条路径的分工见 {@link ModelEvents} 的类注释 ——
     * 简短版:Observer 是「某个模型的领域规则」,总线是「横切关注」。
     *
     * <p>**先跑 Observer 再上总线**。理由:Observer 是模型专属的,
     * 它否决时的原因通常是领域规则(「已发货的订单不能改」),
     * 而那时这次写入**不会发生** —— 再去通知一个审计监听器
     * 「某个订单正要被更新」是在报告一件没发生的事。
     *
     * <p>热路径:RETRIEVED 每读一行一次。所以先问
     * {@code Event.listening(...)},没人监听时连 record 都不构造。
     */
    private static boolean fireOnBus(ModelEvent event, Object model) {
        Class<?> busType = ModelEvents.typeOf(event);
        if (!io.jaravel.events.Event.listening(busType)) return true;

        boolean proceed = io.jaravel.events.Event.bus()
                .dispatch(ModelEvents.wrap(event, model));

        // 不可中止的事件忽略总线的返回值 —— 一个 Created 监听器返回
        // false 不该把「已经插进库里」这件事回滚掉(它也回滚不了)。
        // 不做这个区分的话,一个写成 boolean 的 Created 监听器
        // 会让 save() 返回 false,而数据其实已经写进去了
        return !event.isAbortable() || proceed;
    }

    @SuppressWarnings("unchecked")
    private static <M> boolean fireObservers(ModelEvent event, M model) {
        List<Observer<?>> list = REGISTRY.get(model.getClass());
        if (list == null || list.isEmpty()) return true;

        for (Observer<?> raw : list) {
            Observer<M> o = (Observer<M>) raw;
            boolean proceed = switch (event) {
                case RETRIEVED -> { o.retrieved(model); yield true; }
                case CREATING  -> o.creating(model);
                case CREATED   -> { o.created(model); yield true; }
                case UPDATING  -> o.updating(model);
                case UPDATED   -> { o.updated(model); yield true; }
                case SAVING    -> o.saving(model);
                case SAVED     -> { o.saved(model); yield true; }
                case DELETING  -> o.deleting(model);
                case DELETED   -> { o.deleted(model); yield true; }
                case RESTORED  -> { o.restored(model); yield true; }
            };
            // 一旦被否决就停止 —— 后续观察者不该在「操作已取消」时还收到通知
            if (!proceed && event.isAbortable()) return false;
        }
        return true;
    }

    /** 轻量注册:只关心一个钩子时不必实现整个接口 */
    public static <M> void on(Class<M> modelType, ModelEvent event, Consumer<M> handler) {
        register(modelType, hookOnly(event, handler));
    }

    private static <M> Observer<M> hookOnly(ModelEvent event, Consumer<M> handler) {
        return new Observer<>() {
            @Override public void retrieved(M m) { if (event == ModelEvent.RETRIEVED) handler.accept(m); }
            @Override public boolean creating(M m) { if (event == ModelEvent.CREATING) handler.accept(m); return true; }
            @Override public void created(M m)   { if (event == ModelEvent.CREATED) handler.accept(m); }
            @Override public boolean updating(M m) { if (event == ModelEvent.UPDATING) handler.accept(m); return true; }
            @Override public void updated(M m)   { if (event == ModelEvent.UPDATED) handler.accept(m); }
            @Override public boolean saving(M m) { if (event == ModelEvent.SAVING) handler.accept(m); return true; }
            @Override public void saved(M m)     { if (event == ModelEvent.SAVED) handler.accept(m); }
            @Override public boolean deleting(M m) { if (event == ModelEvent.DELETING) handler.accept(m); return true; }
            @Override public void deleted(M m)   { if (event == ModelEvent.DELETED) handler.accept(m); }
            @Override public void restored(M m)  { if (event == ModelEvent.RESTORED) handler.accept(m); }
        };
    }

    /** 调试用:已注册观察者的模型类型 */
    public static List<Class<?>> observedTypes() {
        return new ArrayList<>(REGISTRY.keySet());
    }
}
