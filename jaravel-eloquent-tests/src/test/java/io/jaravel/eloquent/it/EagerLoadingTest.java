package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.it.models.Comment;
import io.jaravel.eloquent.it.models.Comment_;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Post_;
import io.jaravel.eloquent.it.models.Tag;
import io.jaravel.eloquent.it.models.User;
import io.jaravel.eloquent.it.models.User_;
import io.jaravel.eloquent.relations.Rel;

/**
 * N+1 的三道防线。
 *
 * <p>这组测试断言的是**SQL 条数**而不只是结果正确 —— N+1 的症状恰恰是
 * 「结果全对,但发了 200 条查询」,只断言数据的测试抓不到它。
 */
class EagerLoadingTest extends EloquentTestBase {

    @Test
    @DisplayName("预加载:3 个作者 + 各自的文章 = 2 条查询,而不是 4 条")
    void eagerLoadIsOneQueryPerLevel() {
        seed(3, 2);

        clearSql();
        var users = User.query().load(User_.posts).get();

        assertThat(users).hasSize(3);
        assertThat(selectCount()).isEqualTo(2);
        // 每个作者都已填好,访问不再发查询
        clearSql();
        for (User u : users) {
            assertThat(u.posts().getLoaded()).hasSize(2);
        }
        assertThat(selectCount()).isZero();
    }

    @Test
    @DisplayName("不预加载时就是 N+1 —— 作为对照,证明上面的断言有意义")
    void withoutEagerLoadingItIsNPlusOne() {
        seed(3, 2);

        clearSql();
        var users = User.all();
        for (User u : users) {
            u.posts().get();
        }
        // 1 条查用户 + 3 条查文章
        assertThat(selectCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("嵌套预加载 posts.comments:每层一条,共 3 条")
    void nestedEagerLoading() {
        seed(2, 2);
        for (Post post : Post.all()) {
            for (int i = 0; i < 3; i++) {
                Comment c = new Comment();
                c.postId = post.id;
                c.body = "评论" + i;
                c.save();
            }
        }

        clearSql();
        var users = User.query().load(User_.posts.then(Post_.comments)).get();

        assertThat(selectCount()).isEqualTo(3);
        clearSql();
        int total = 0;
        for (User u : users) {
            for (Post p : u.posts().getLoaded()) {
                total += p.comments().getLoaded().size();
            }
        }
        assertThat(total).isEqualTo(12);          // 2 作者 × 2 文章 × 3 评论
        assertThat(selectCount()).isZero();
    }

    @Test
    @DisplayName("同一层的多条路径共享一条查询")
    void sameLevelPathsShareOneQuery() {
        seed(2, 2);
        for (Post post : Post.all()) {
            Comment c = new Comment();
            c.postId = post.id;
            c.body = "评论";
            c.save();
        }

        clearSql();
        // posts.comments 与 posts 都以 posts 开头,posts 那层只应查一次
        Post.query().load(Post_.comments, Post_.author).get();
        assertThat(selectCount()).isEqualTo(3);   // posts + comments + users
    }

    @Test
    @DisplayName("预加载可以带约束")
    void eagerLoadWithConstraint() {
        User user = user("甲");
        Post post = post(user, "文章");
        for (int i = 0; i < 4; i++) {
            Comment c = new Comment();
            c.postId = post.id;
            c.body = "评论" + i;
            c.approved = i % 2 == 0;
            c.save();
        }

        var posts = Post.query()
                .withComments(q -> q.where(Comment_.approved, true))
                .get();

        assertThat(posts.first().orElseThrow().comments().getLoaded()).hasSize(2);
    }

    @Test
    @DisplayName("BelongsTo 预加载:子查询用目标主键 IN,不是每行一条")
    void eagerLoadBelongsTo() {
        User a = user("甲");
        User b = user("乙");
        for (int i = 0; i < 5; i++) post(i % 2 == 0 ? a : b, "文章" + i);

        clearSql();
        var posts = Post.query().load(Post_.author).get();

        assertThat(selectCount()).isEqualTo(2);
        clearSql();
        assertThat(posts.map(p -> p.author().getLoaded().orElseThrow().name))
                .containsOnly("甲", "乙");
        assertThat(selectCount()).isZero();
    }

    @Test
    @DisplayName("BelongsToMany 预加载:中间表一条 + 目标表一条")
    void eagerLoadBelongsToMany() {
        Tag java = tag("java");
        Tag php = tag("php");
        for (int i = 0; i < 3; i++) {
            Post p = post(user("作者" + i), "文章" + i);
            p.tags().attach(java.id, php.id);
        }

        clearSql();
        var posts = Post.query().load(Post_.tags).get();

        // posts + pivot + tags = 3
        assertThat(selectCount()).isEqualTo(3);
        clearSql();
        for (Post p : posts) {
            assertThat(p.tags().getLoaded()).hasSize(2);
        }
        assertThat(selectCount()).isZero();
    }

    @Test
    @DisplayName("prevent-lazy-loading 打开时,未预加载的访问直接报错并给出可粘贴的修复")
    void preventLazyLoadingIsActionable() {
        seed(1, 1);
        Eloquent.preventLazyLoading(true);
        try {
            User user = User.all().first().orElseThrow();
            assertThatThrownBy(() -> user.posts().get())
                    .isInstanceOf(Rel.LazyLoadingViolationException.class)
                    // 报错必须包含能直接粘走的修复表达式,否则等于只说「你错了」
                    .hasMessageContaining("User_.posts")
                    .hasMessageContaining("posts");
        } finally {
            Eloquent.preventLazyLoading(false);
        }
    }

    @Test
    @DisplayName("allowingLazyLoading 能在禁用区内临时放行")
    void allowingLazyLoadingEscapeHatch() {
        seed(1, 1);
        Eloquent.preventLazyLoading(true);
        try {
            User user = User.all().first().orElseThrow();
            var posts = Eloquent.allowingLazyLoading(() -> user.posts().get());
            assertThat(posts).hasSize(1);
        } finally {
            Eloquent.preventLazyLoading(false);
        }
    }

    @Test
    @DisplayName("预加载空结果集不发子查询")
    void emptyParentSkipsChildQuery() {
        clearSql();
        var users = User.query().whereName("不存在").load(User_.posts).get();
        assertThat(users).isEmpty();
        assertThat(selectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("父集合里有空外键时不会把 null 塞进 IN")
    void nullForeignKeysAreSkipped() {
        Post orphan = new Post();
        orphan.title = "无主";
        orphan.body = "正文";
        orphan.save();                             // user_id 为 null
        post(user("甲"), "有主");

        clearSql();
        var posts = Post.query().load(Post_.author).get();
        assertThat(posts).hasSize(2);
        assertThat(selectCount()).isEqualTo(2);

        var orphanLoaded = posts.filter(p -> p.userId == null).first().orElseThrow();
        assertThat(orphanLoaded.author().getLoaded()).isEmpty();
    }

    @Test
    @DisplayName("load() 可以在取回之后补加载")
    void deferredLoad() {
        seed(2, 2);

        var users = User.all().toList();
        clearSql();
        User.query().meta();                        // 不发查询
        io.jaravel.eloquent.Model.loadAll(users, User_.posts);

        assertThat(selectCount()).isEqualTo(1);
        assertThat(users.get(0).posts().getLoaded()).hasSize(2);
    }

    // ================= 夹具 =================

    private void seed(int users, int postsEach) {
        for (int i = 0; i < users; i++) {
            User u = user("作者" + i);
            for (int j = 0; j < postsEach; j++) post(u, "文章" + i + "-" + j);
        }
    }

    private User user(String name) {
        User u = new User();
        u.name = name;
        u.save();
        return u;
    }

    private Post post(User author, String title) {
        Post p = new Post();
        p.title = title;
        p.body = "正文";
        p.userId = author.id;
        p.save();
        return p;
    }

    private Tag tag(String name) {
        Tag t = new Tag();
        t.name = name;
        t.save();
        return t;
    }

    @SuppressWarnings("unused")
    private static List<String> unused() { return List.of(); }
}
