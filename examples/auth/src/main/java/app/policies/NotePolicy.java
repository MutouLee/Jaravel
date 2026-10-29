package app.policies;

import org.springframework.stereotype.Component;

import app.models.Note;
import app.models.User;
import io.jaravel.auth.access.Policy;

/**
 * 笔记的授权策略。
 *
 * <p>{@code @Policy(Note.class)} 让它自动注册 —— 不需要在别处维护
 * 一张「模型 → Policy」的映射表(那张表的问题是新增时会忘,
 * 而忘了的症状是「授权规则不生效」)。
 *
 * <h2>方法名就是 ability</h2>
 * <p>{@code Gate.allows("update", note)} 走到 {@link #update};
 * 路由上的 {@code middleware("can:update,note")} 也是同一条路径。
 *
 * <p>带模型参数的方法用于「对这一条做什么」;不带的用于
 * 「能不能新建」之类还没有实例的操作 —— 见 {@link #create}。
 */
@Component
@Policy(Note.class)
public class NotePolicy {

    public boolean view(User user, Note note) {
        return owns(user, note);
    }

    public boolean update(User user, Note note) {
        return owns(user, note);
    }

    public boolean delete(User user, Note note) {
        return owns(user, note);
    }

    /** 不带模型 —— 新建时还没有实例可判 */
    public boolean create(User user) {
        // 要求邮箱已验证才能建笔记。这里能读到模型是因为 provider
        // 用 byUsername/byId 查了真正的 User,而不是一个只有 id 的包装体
        return user.hasVerifiedEmail();
    }

    /**
     * 比较 Long 用 equals 而不是 ==。
     *
     * <p>{@code user_id} 是 {@code Long},而 {@code ==} 比的是引用 ——
     * 小于 128 的值因为 Long 缓存碰巧相等,大于 128 的就不等了。
     * 症状是「测试环境好的,上线后用户看不到自己的笔记」。
     */
    private static boolean owns(User user, Note note) {
        return note.userId != null && note.userId.equals(user.id);
    }
}
