package io.jaravel.eloquent.event;

/**
 * 模型生命周期的**事件总线**形态(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class AuditAllWrites {
 *     public void handle(ModelEvents.Created e) {
 *         audit("created", e.modelType().getSimpleName(), e.model());
 *     }
 * }
 * }</pre>
 *
 * <h2>与 {@link Observer} 的分工 —— 这是这组类型唯一需要想清的事</h2>
 * <p>两条路径都存在,各有各的用途:
 *
 * <table>
 *   <caption>选哪个</caption>
 *   <tr><th></th><th>{@code Observer<User>}</th><th>总线事件</th></tr>
 *   <tr><td>拿到的类型</td><td>{@code User}(强类型)</td><td>{@code Object}</td></tr>
 *   <tr><td>关心的模型</td><td>**一个**</td><td>**全部**</td></tr>
 *   <tr><td>注册</td><td>{@code Observers.register(User.class, …)}</td>
 *       <td>{@code @Listener},自动装</td></tr>
 *   <tr><td>适合</td><td>某个模型的领域规则</td>
 *       <td>横切关注:审计、缓存失效、指标</td></tr>
 * </table>
 *
 * <p>所以事件里的 {@code model} 是 {@code Object} **不是一个缺陷**。
 * 一个「审计所有写入」的监听器本来就不关心具体类型;而关心具体类型的
 * 场景有 {@code Observer},它是强类型且零反射的。
 *
 * <p>这一点要说清,因为按这个项目自己的标准,「在监听器里做
 * {@code instanceof}」通常是把编译器能查的东西挪到运行时 ——
 * 而这里恰好是例外:横切关注的**本质**就是不区分类型。
 * 真要按类型分支时该用的是 {@code Observer}。
 *
 * <h2>可中止的那四个,在总线上也可中止</h2>
 * <p>{@code Creating} / {@code Updating} / {@code Saving} / {@code Deleting}
 * 的监听器写成 {@code boolean handle(...)} 并返回 false,就能**取消**
 * 那次写入。用途:「只读模式挡掉所有写入」这类全局开关。
 *
 * <p>这件事之所以敢做,是因为 APT 为 {@code void handle} 生成了
 * {@code return true} —— 所以「忘了 return true 导致写入被静默取消」
 * 这个事故在这里不可能发生。手写绑定的方案里它会。
 *
 * <h2>它们在事务内同步触发</h2>
 * <p>与 {@code Observer} 一样。需要「提交后才做」的副作用用
 * {@code Event.dispatchAfterCommit(...)} 或 {@code DB.afterCommit(...)} ——
 * 否则会出现「事务还没提交就发了通知」这个经典 bug。
 */
public final class ModelEvents {

    private ModelEvents() {
    }

    /** 共同的形状 —— 便于横切监听器统一处理 */
    public interface OfModel {
        Object model();

        default Class<?> modelType() {
            return model().getClass();
        }
    }

    /**
     * 从数据库读出来了。
     *
     * <p>⚠ **这是最热的那个** —— 每读出一行一次。一个返回 1000 行的
     * 查询会发 1000 个事件。发出它的地方先问
     * {@code Event.listening(...)},没人监听时连 record 都不构造。
     *
     * <p>即便如此,监听它仍然要小心:一个在 {@code Retrieved} 里查库的
     * 监听器会把一次查询变成 N+1。
     */
    public record Retrieved(Object model) implements OfModel {
    }

    /** 插入前。**可中止** */
    public record Creating(Object model) implements OfModel {
    }

    public record Created(Object model) implements OfModel {
    }

    /** 更新前。**可中止** */
    public record Updating(Object model) implements OfModel {
    }

    public record Updated(Object model) implements OfModel {
    }

    /** 插入或更新前,在 Creating/Updating 之后。**可中止** */
    public record Saving(Object model) implements OfModel {
    }

    public record Saved(Object model) implements OfModel {
    }

    /** 删除前。**可中止** */
    public record Deleting(Object model) implements OfModel {
    }

    public record Deleted(Object model) implements OfModel {
    }

    /** 软删除恢复后 */
    public record Restored(Object model) implements OfModel {
    }

    /**
     * 某个生命周期阶段对应的事件类型。
     *
     * <p>{@link Observers} 用它做「有人监听吗」的判断 ——
     * 那个判断要在构造 record **之前**做。
     */
    static Class<?> typeOf(ModelEvent event) {
        return switch (event) {
            case RETRIEVED -> Retrieved.class;
            case CREATING  -> Creating.class;
            case CREATED   -> Created.class;
            case UPDATING  -> Updating.class;
            case UPDATED   -> Updated.class;
            case SAVING    -> Saving.class;
            case SAVED     -> Saved.class;
            case DELETING  -> Deleting.class;
            case DELETED   -> Deleted.class;
            case RESTORED  -> Restored.class;
        };
    }

    static Object wrap(ModelEvent event, Object model) {
        return switch (event) {
            case RETRIEVED -> new Retrieved(model);
            case CREATING  -> new Creating(model);
            case CREATED   -> new Created(model);
            case UPDATING  -> new Updating(model);
            case UPDATED   -> new Updated(model);
            case SAVING    -> new Saving(model);
            case SAVED     -> new Saved(model);
            case DELETING  -> new Deleting(model);
            case DELETED   -> new Deleted(model);
            case RESTORED  -> new Restored(model);
        };
    }
}
