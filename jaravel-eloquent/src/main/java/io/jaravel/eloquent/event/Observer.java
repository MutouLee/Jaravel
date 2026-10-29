package io.jaravel.eloquent.event;

/**
 * 模型观察者。只覆写关心的钩子。
 *
 * <pre>{@code
 * public class PostObserver implements Observer<Post> {
 *     @Override public boolean creating(Post post) {
 *         post.slug = Str.slug(post.title);
 *         return true;                      // 返回 false 可取消插入
 *     }
 *     @Override public void created(Post post) {
 *         DB.afterCommit(() -> Queue.dispatch(new NotifySubscribers(post.id)));
 *     }
 * }
 * // 注册:Observers.register(Post.class, new PostObserver());
 * }</pre>
 *
 * <p>可中止的钩子返回 {@code boolean};其余返回 {@code void}。
 * 这让「能不能取消」在签名上就看得出来,不需要查文档。
 */
public interface Observer<M> {

    default void retrieved(M model) {}

    /** @return false 取消插入 */
    default boolean creating(M model) { return true; }
    default void created(M model) {}

    /** @return false 取消更新 */
    default boolean updating(M model) { return true; }
    default void updated(M model) {}

    /** @return false 取消保存 */
    default boolean saving(M model) { return true; }
    default void saved(M model) {}

    /** @return false 取消删除 */
    default boolean deleting(M model) { return true; }
    default void deleted(M model) {}

    default void restored(M model) {}
}
