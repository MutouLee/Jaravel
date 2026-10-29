package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/**
 * Todo 模型。
 *
 * <p>标量列是 **public 字段**(ADR-010 方案 E')—— 读写就是
 * {@code todo.title} 与 {@code todo.title = "x"},与 PHP 的
 * {@code $todo->title} 等长。
 *
 * <p>v0.1 时这里还要配一个 43 行的 {@code TodoRepository};Eloquent 落地后
 * 那个类整个删掉了,字段声明**一行没改** —— 这正是方案 E' 当初选它的理由。
 */
@Model
@Fillable({"title", "done"})
public class Todo extends TodoBase {

    @Id public Long id;
    public String title;
    public boolean done;
    public Instant createdAt;
    public Instant updatedAt;

    public Todo() {}

    public Todo(String title) {
        this.title = title;
    }

    /** 模板里直接调用:{@code {{ todo.statusLabel() }}} */
    public String statusLabel() {
        return done ? "已完成" : "待办";
    }
}
