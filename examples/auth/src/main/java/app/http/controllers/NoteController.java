package app.http.controllers;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.Note;
import app.models.Note_;
import app.models.User;
import io.jaravel.auth.Auth;
import io.jaravel.auth.access.Gate;
import io.jaravel.http.Request;
import io.jaravel.validation.Validator;

/**
 * 笔记:用来演示「认证之上还有授权」。
 *
 * <p>路由上挂 {@code auth} 保证「是谁」,Policy 回答「能不能」。
 * 两者缺一不可:只有 auth 时任何登录用户都能改别人的笔记。
 */
@Component
public class NoteController {

    /** GET /api/notes —— 只返回自己的 */
    public Object index() {
        User me = Auth.userOrFail(User.class);
        return Note.query().ownedBy(me.id).orderByKeyDesc().get().toList();
    }

    /** POST /api/notes */
    public Object store(Request request) {
        User me = Auth.userOrFail(User.class);

        // create 不带模型参数 —— 它问的是「这个人能不能新建」。
        // 失败抛 403,由 JaravelExceptionHandler 转成统一格式
        Gate.authorize("create");

        var data = Validator.make(request.all(), Map.of(
                "title", "required|string|max:200",
                "body", "required|string")).validate();

        Note note = new Note();
        note.userId = me.id;
        note.fill(data.only("title", "body"));
        note.save();
        return note;
    }

    /**
     * GET /api/notes/{id}
     *
     * <p>路由上写的是 {@code can:view,note} —— 但 v0.3 的隐式模型绑定
     * 还没有,中间件拿到的是字符串 "42"。所以这里的授权在**控制器里**
     * 显式做,路由上不挂 can。
     *
     * <p>这个限制写在 {@code Authorize} 的类注释里,隐式绑定排在 v0.4。
     */
    public Object show(Long id) {
        Note note = Note.findOrFail(id);
        Gate.authorize("view", note);
        return note;
    }

    /** PUT /api/notes/{id} */
    public Object update(Long id, Request request) {
        Note note = Note.findOrFail(id);
        Gate.authorize("update", note);

        var data = Validator.make(request.all(), Map.of(
                "title", "nullable|string|max:200",
                "body", "nullable|string")).validate();

        note.fill(data.only("title", "body"));
        note.save();
        return note;
    }

    /** DELETE /api/notes/{id} */
    public Object destroy(Long id) {
        Note note = Note.findOrFail(id);
        Gate.authorize("delete", note);
        note.delete();
        return Map.of("deleted", true, "id", id);
    }

    /** GET /api/notes/count —— 证明 Note_ 元模型在示例里真的被用到 */
    public Object count() {
        User me = Auth.userOrFail(User.class);
        return Map.of("count", Note.query().where(Note_.userId, me.id).count());
    }
}
