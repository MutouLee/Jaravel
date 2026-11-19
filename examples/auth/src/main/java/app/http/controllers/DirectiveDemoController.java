package app.http.controllers;

import org.springframework.stereotype.Component;

import app.models.Note;
import io.jaravel.http.View;

/**
 * jblade 条件指令的演示页(docs/04 §1.8)。
 *
 * <p>**单独一个控制器**,不放进 {@link NoteController}:那个文件是
 * parity 切片 03 的被测对象,而这个页面是**框架特性的演示** ——
 * Laravel 参照实现里没有对应的东西,把它算进 parity 等于拿一个
 * 没有对照物的页面去抬比值。同样的理由见
 * {@code routes/PermissionDemoRoutes} 的类注释。
 */
@Component
public class DirectiveDemoController {

    /**
     * GET /notes/page —— 游客与登录用户都能看。
     *
     * <p>刻意列出**所有人**的笔记:看点在于模板里的
     * {@code @can('update', note)} 让「编辑」只出现在自己的那几条上。
     * 接口那边({@link NoteController#index})仍然只返回自己的 ——
     * 授权与「查什么」是两件事,页面上少显示一个按钮不等于接口安全。
     */
    public Object page() {
        return View.of("notes.page")
                .with("notes", Note.query().orderByKeyDesc().get().toList());
    }

    /**
     * GET /notes/{id}/row —— 只回那一行(docs/04 §1.8 的 {@code @fragment})。
     *
     * <p>htmx / turbo 的局部刷新:改完一条笔记只换掉表格里那一行,
     * 而回的 HTML 与整页渲染出来的**是同一段模板代码** ——
     * 另写一个 partial 模板的话两份会分叉,症状是「整页对、局部不对」
     * 或者反过来。
     *
     * <p>数据仍然要齐全:片段的渲染路径与整页完全一样(整页照样渲染
     * 一遍,再把那一段取出来),理由见 {@code JbladeEngine.renderFragment}。
     */
    public Object row(Long id) {
        return View.of("notes.page")
                .with("notes", Note.query().orderByKeyDesc().get().toList())
                .fragment("note-" + id);
    }

    /**
     * DELETE /notes/{id} —— 页面上那个 POST + {@code @method('DELETE')} 表单
     * 最终落在这里。
     *
     * <p>授权仍然走 Gate:方法伪装只解决「浏览器发不出 DELETE」,
     * 与「谁能删」无关。模板里 {@code @cannot('delete', note)} 不显示按钮,
     * 而这里是真正的那道门 —— 少显示一个按钮不等于接口安全。
     */
    public io.jaravel.http.Response destroy(Long id) {
        Note note = Note.findOrFail(id);
        io.jaravel.auth.access.Gate.authorize("delete", note);
        note.delete();

        return io.jaravel.http.Redirect.to("/notes/page").with("status", "已删除");
    }
}
