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

        // create 没有实例可判 —— 传**类型**让 Gate 找到 NotePolicy。
        // 不传的话 Gate 无从分派,会按「权限未定义」拒绝,
        // 而那条消息看起来像「Policy 没注册」
        Gate.authorize("create", Note.class);

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
     * GET /api/notes/{note}
     *
     * <p>参数直接是 {@code Note} 实例 —— 隐式模型绑定。
     * 授权在路由上({@code can:view,note}),这里不用再写一次:
     * {@code SubstituteBindings} 把模型解析好放进请求级缓存,
     * {@code Authorize} 与本方法**拿到的是同一个实例**。
     *
     * <p>同一个实例这一点不只是省一次查库:两次查询之间数据可能变了,
     * 于是「授权时看到的状态」与「实际被返回/修改的状态」会不一致。
     */
    public Object show(Note note) {
        return note;
    }

    /** PUT /api/notes/{note} */
    public Object update(Note note, Request request) {
        var data = Validator.make(request.all(), Map.of(
                "title", "nullable|string|max:200",
                "body", "nullable|string")).validate();

        note.fill(data.only("title", "body"));
        note.save();
        return note;
    }

    /** DELETE /api/notes/{note} */
    public Object destroy(Note note) {
        Long id = note.id;
        note.delete();
        return Map.of("deleted", true, "id", id);
    }

    /** GET /api/notes/count —— 证明 Note_ 元模型在示例里真的被用到 */
    public Object count() {
        User me = Auth.userOrFail(User.class);
        return Map.of("count", Note.query().where(Note_.userId, me.id).count());
    }
}
