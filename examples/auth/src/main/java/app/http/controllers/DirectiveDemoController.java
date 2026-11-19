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
}
