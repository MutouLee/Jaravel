package app.models;

import java.time.Instant;

/**
 * Todo 模型。
 *
 * <p>标量列是 **public 字段**(ADR-010 方案 E')——
 * 读写就是 {@code todo.title} 与 {@code todo.title = "x"},与 PHP 的
 * {@code $todo->title} 等长。
 *
 * <p>v0.1 还没有 Eloquent,所以持久化走查询构造器(见 TodoRepository)。
 * v0.2 会让这个类加上 {@code @Model} 注解并继承 {@code Model<Todo>},
 * 届时 {@code todo.save()} 可用,本文件的字段声明不需要改动 ——
 * 这正是方案 E' 的好处。
 */
public class Todo {

    public Long id;
    public String title;
    public boolean done;
    public Instant createdAt;

    public Todo() {}

    public Todo(String title) {
        this.title = title;
    }

    /** 模板里直接调用:{@code {{ todo.statusLabel() }}} */
    public String statusLabel() {
        return done ? "已完成" : "待办";
    }
}
