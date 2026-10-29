package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.eloquent.event.Observer;
import io.jaravel.eloquent.event.Observers;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.query.IdentityMap;

/** 身份映射与模型事件 */
class IdentityMapAndEventTest extends EloquentTestBase {

    @AfterEach
    void clearObservers() {
        Observers.clear();
        IdentityMap.clear();
    }

    // ================= 身份映射 =================

    @Test
    @DisplayName("同一作用域内同主键返回同一个实例")
    void sameScopeSameInstance() throws Exception {
        Post saved = post("文章");

        ExecutionScope.runIn("test", () -> {
            Post a = Post.findOrFail(saved.id);
            Post b = Post.findOrFail(saved.id);
            // 同一实例 —— 否则「改了 a 却读到 b 的旧值」这类 bug 无法避免
            assertThat(a).isSameAs(b);
            return null;
        });
    }

    @Test
    @DisplayName("不同作用域各自独立 —— 身份映射不能跨请求泄漏")
    void differentScopesAreIsolated() throws Exception {
        Post saved = post("文章");

        Post first = ExecutionScope.runIn("req1", () -> Post.findOrFail(saved.id));
        Post second = ExecutionScope.runIn("req2", () -> Post.findOrFail(saved.id));

        assertThat(first).isNotSameAs(second);
        assertThat(first.id).isEqualTo(second.id);
    }

    @Test
    @DisplayName("身份映射不会用库里的值覆盖已改脏的实例")
    void dirtyInstanceIsNotOverwritten() throws Exception {
        Post saved = post("原标题");

        ExecutionScope.runIn("test", () -> {
            Post a = Post.findOrFail(saved.id);
            a.title = "改了但没存";

            Post b = Post.findOrFail(saved.id);
            // 重新查一次不能把未保存的修改冲掉 —— 那是「数据无声消失」类 bug
            assertThat(b).isSameAs(a);
            assertThat(b.title).isEqualTo("改了但没存");
            return null;
        });
    }

    @Test
    @DisplayName("作用域外也能用,只是不缓存")
    void worksOutsideScope() {
        Post saved = post("文章");

        Post a = Post.findOrFail(saved.id);
        Post b = Post.findOrFail(saved.id);

        // 没有作用域时退化成「每次新实例」,而不是报错 ——
        // 命令行脚本、单元测试都在作用域外
        assertThat(a.id).isEqualTo(b.id);
    }

    // ================= 模型事件 =================

    @Test
    @DisplayName("事件按 creating → saving → created → saved 的顺序触发")
    void createEventOrder() {
        var fired = new ArrayList<String>();
        Observers.register(Post.class, recorder(fired));

        post("文章");

        assertThat(fired).containsExactly("creating", "saving", "created", "saved");
    }

    @Test
    @DisplayName("更新走 updating → saving → updated → saved")
    void updateEventOrder() {
        Post post = post("文章");

        var fired = new ArrayList<String>();
        Observers.register(Post.class, recorder(fired));
        post.title = "改了";
        post.save();

        assertThat(fired).containsExactly("updating", "saving", "updated", "saved");
    }

    @Test
    @DisplayName("无变化的 save():触发 updating/saving 但不触发 updated/saved,也不发 SQL")
    void cleanSaveFiresOnlyPreHooks() {
        Post post = post("文章");

        var fired = new ArrayList<String>();
        Observers.register(Post.class, recorder(fired));
        clearSql();
        assertThat(post.save()).isFalse();

        // updating/saving 必须触发:它们是**可以改模型**的钩子。
        // 若先判脏再触发,观察者里的 `model.slug = slugify(model.title)`
        // 就会被静默丢掉 —— 所以顺序只能是「先给机会,再判脏」。
        assertThat(fired).containsExactly("updating", "saving");
        // 而 updated/saved 表示「真的写了库」,没写就不该触发
        assertThat(fired).doesNotContain("updated", "saved");
        assertThat(sql()).isEmpty();
    }

    @Test
    @DisplayName("观察者在 saving 里改字段,改动会被脏检查看到并写入")
    void observerMutationInSavingIsPersisted() {
        Post post = post("文章");

        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean saving(Post model) {
                model.views = model.views + 1;
                return true;
            }
        });

        assertThat(post.save()).isTrue();
        assertThat(Post.findOrFail(post.id).views).isEqualTo(1);
    }

    @Test
    @DisplayName("creating 返回 false 能中止插入,且库里没有记录")
    void creatingCanAbort() {
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean creating(Post model) { return false; }
        });

        Post post = new Post();
        post.title = "不该进库";
        post.body = "正文";

        assertThat(post.save()).isFalse();
        assertThat(post.id).isNull();
        assertThat(Post.count()).isZero();
    }

    @Test
    @DisplayName("deleting 返回 false 能中止删除")
    void deletingCanAbort() {
        Post post = post("留着");
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean deleting(Post model) { return false; }
        });

        assertThat(post.delete()).isFalse();
        assertThat(Post.find(post.id)).isPresent();
    }

    @Test
    @DisplayName("retrieved 在取回时触发")
    void retrievedFires() {
        post("文章");

        var fired = new ArrayList<String>();
        Observers.register(Post.class, recorder(fired));
        Post.all();

        assertThat(fired).containsExactly("retrieved");
    }

    @Test
    @DisplayName("观察者可以在 creating 里改字段,改动会进 INSERT")
    void observerCanMutateBeforeInsert() {
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean creating(Post model) {
                model.title = "[审核中] " + model.title;
                return true;
            }
        });

        Post post = post("文章");
        assertThat(post.title).isEqualTo("[审核中] 文章");
        assertThat(Post.findOrFail(post.id).title).isEqualTo("[审核中] 文章");
    }

    @Test
    @DisplayName("多个观察者按注册顺序执行,任一个否决即中止")
    void multipleObserversAnyVeto() {
        var fired = new ArrayList<String>();
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean creating(Post m) { fired.add("first"); return true; }
        });
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean creating(Post m) { fired.add("second"); return false; }
        });
        Observers.register(Post.class, new Observer<Post>() {
            @Override public boolean creating(Post m) { fired.add("third"); return true; }
        });

        Post p = new Post();
        p.title = "x";
        assertThat(p.save()).isFalse();
        // 第二个否决后不该再走第三个 —— 否则「中止」只是个建议
        assertThat(fired).containsExactly("first", "second");
    }

    // ================= 工具 =================

    private static Observer<Post> recorder(List<String> fired) {
        return new Observer<>() {
            @Override public void retrieved(Post m) { fired.add("retrieved"); }
            @Override public boolean creating(Post m) { fired.add("creating"); return true; }
            @Override public void created(Post m)   { fired.add("created"); }
            @Override public boolean updating(Post m) { fired.add("updating"); return true; }
            @Override public void updated(Post m)   { fired.add("updated"); }
            @Override public boolean saving(Post m) { fired.add("saving"); return true; }
            @Override public void saved(Post m)     { fired.add("saved"); }
            @Override public boolean deleting(Post m) { fired.add("deleting"); return true; }
            @Override public void deleted(Post m)   { fired.add("deleted"); }
            @Override public void restored(Post m)  { fired.add("restored"); }
        };
    }

    private Post post(String title) {
        Post p = new Post();
        p.title = title;
        p.body = "正文";
        p.save();
        return p;
    }
}
