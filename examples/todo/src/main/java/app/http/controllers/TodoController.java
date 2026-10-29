package app.http.controllers;

import org.springframework.stereotype.Component;

import app.models.TodoRepository;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.View;

/**
 * Todo 控制器。
 *
 * <p>控制器实例由容器解析,因此**构造注入直接可用**。
 * 返回值可以是 {@link View} / {@link Redirect} / 任意 POJO —— 由返回值归一化处理。
 */
@Component
public class TodoController {

    private final TodoRepository todos;

    public TodoController(TodoRepository todos) {
        this.todos = todos;
    }

    /** GET / */
    public View index() {
        return View.of("todos.index")
                   .with("todos", todos.all().toList())
                   .with("pending", todos.countPending());
    }

    /** POST /todos */
    public Response store(Request request) {
        String title = request.input("title", "").trim();

        // v0.1 还没有验证器模块(v0.2),先手工校验
        if (title.isEmpty()) {
            return Redirect.to("/").with("error", "标题不能为空");
        }
        if (title.length() > 100) {
            return Redirect.to("/").with("error", "标题不能超过 100 个字符");
        }

        todos.create(title);
        return Redirect.to("/").with("status", "已添加");
    }

    /** POST /todos/{id}/toggle */
    public Response toggle(Long id) {
        todos.toggle(id);
        return Redirect.to("/");
    }

    /** POST /todos/{id}/delete */
    public Response destroy(Long id) {
        todos.delete(id);
        return Redirect.to("/").with("status", "已删除");
    }

    /** GET /api/todos —— 返回 POJO 列表,自动序列化为 JSON */
    public Object apiIndex() {
        return todos.all().toList();
    }

    /** GET /api/todos/{id} */
    public Object apiShow(Long id) {
        return todos.find(id)
                .orElseThrow(() -> new io.jaravel.http.HttpException(404, "Todo " + id + " 不存在"));
    }
}
