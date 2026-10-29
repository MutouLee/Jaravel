package app.models;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import io.jaravel.facades.DB;
import io.jaravel.support.Coll;

/**
 * Todo 的数据访问。
 *
 * <p><b>v0.1 的临时产物:</b>Eloquent(v0.2)落地后这个类会整个消失 ——
 * 届时直接 {@code Todo.query().where(...).get()} 与 {@code todo.save()}。
 * 保留它是为了让 v0.1 的切片能端到端跑通,同时让 parity 对比诚实:
 * 这 40 行正是 Eloquent 要消除的样板。
 */
@Repository
public class TodoRepository {

    private static final String TABLE = "todos";

    public Coll<Todo> all() {
        return DB.table(TABLE).orderByDesc("id").get(Todo.class);
    }

    public Coll<Todo> pending() {
        return DB.table(TABLE).where("done", false).orderByDesc("id").get(Todo.class);
    }

    public Optional<Todo> find(long id) {
        return DB.table(TABLE).where("id", id).first(Todo.class);
    }

    public Todo create(String title) {
        var values = new LinkedHashMap<String, Object>();
        values.put("title", title);
        values.put("done", false);
        values.put("created_at", Instant.now().toString());

        long id = DB.table(TABLE).insertGetId(values)
                .orElseThrow(() -> new IllegalStateException("插入 todo 后未取到自增主键"));
        return find(id).orElseThrow();
    }

    public void updateTitle(long id, String title) {
        DB.table(TABLE).where("id", id).update(Map.of("title", title));
    }

    public void toggle(long id) {
        Todo todo = find(id).orElseThrow(() -> new IllegalArgumentException("todo " + id + " 不存在"));
        DB.table(TABLE).where("id", id).update(Map.of("done", !todo.done));
    }

    public void delete(long id) {
        DB.table(TABLE).where("id", id).delete();
    }

    public long countPending() {
        return DB.table(TABLE).where("done", false).count();
    }
}
