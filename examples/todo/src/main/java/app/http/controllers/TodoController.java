package app.http.controllers;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.Todo;
import app.models.Todo_;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.View;
import io.jaravel.validation.Validator;

/**
 * Todo 控制器。
 *
 * <p>与 v0.1 的差别:不再注入 Repository —— 查询与持久化都在模型上
 * ({@code Todo.latest()} / {@code todo.save()}),校验交给 {@code Validator}。
 */
@Component
public class TodoController {

    /** GET / */
    public View index() {
        return View.of("todos.index")
                   .with("todos", Todo.query().orderByKeyDesc().get().toList())
                   .with("pending", Todo.query().whereDone(false).count());
    }

    /** POST /todos */
    public Response store(Request request) {
        var validated = Validator.make(request.all(), Map.of(
                "title", "required|string|max:100")).validate();

        Todo.create(validated.only("title"));
        return Redirect.to("/").with("status", "已添加");
    }

    /** POST /todos/{id}/toggle */
    public Response toggle(Long id) {
        Todo todo = Todo.findOrFail(id);
        todo.done = !todo.done;
        todo.save();
        return Redirect.to("/");
    }

    /** POST /todos/{id}/delete */
    public Response destroy(Long id) {
        Todo.findOrFail(id).delete();
        return Redirect.to("/").with("status", "已删除");
    }

    /** GET /api/todos —— 返回 POJO 列表,自动序列化为 JSON */
    public Object apiIndex() {
        return Todo.query().orderByKeyDesc().get().toList();
    }

    /** GET /api/todos/{id} */
    public Object apiShow(Long id) {
        return Todo.findOrFail(id);
    }
}
