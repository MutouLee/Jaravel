package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.it.models.Draft;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.User;

/** 作用域、全局作用域、软删除 */
class ScopeAndSoftDeleteTest extends EloquentTestBase {

    // ================= 作用域 =================

    @Test
    @DisplayName("@Scope 生成的实例方法可以出现在链条任何位置")
    void scopeComposesAnywhere() {
        post("已发布", true, 100);
        post("草稿", false, 500);
        post("冷门", true, 1);

        // 作用域在 where 之后 —— 这要求 where 返回 PostQuery 而非 Query<Post>
        assertThat(Post.query().whereViewsGt(10).published().get().map(p -> p.title))
                .containsExactly("已发布");

        // 作用域在最前
        assertThat(Post.query().published().whereViewsGt(10).get()).hasSize(1);

        // 两个作用域串联
        assertThat(Post.query().published().popular(50).get().map(p -> p.title))
                .containsExactly("已发布");
    }

    @Test
    @DisplayName("@Scope 也生成模型上的静态入口")
    void scopeHasStaticEntry() {
        post("已发布", true, 1);
        post("草稿", false, 1);

        assertThat(Post.published().get()).hasSize(1);
        assertThat(Post.popular(0).get()).hasSize(2);
    }

    @Test
    @DisplayName("带参作用域")
    void parameterizedScope() {
        user("甲");
        user("乙");

        assertThat(User.named("甲").get()).hasSize(1);
        assertThat(User.query().named("乙").withEmail().get()).hasSize(1);
    }

    // ================= 全局作用域 =================

    @Test
    @DisplayName("全局作用域默认生效")
    void globalScopeAppliesByDefault() {
        draft("可见的", true);
        draft("隐藏的", false);

        assertThat(Draft.all().map(d -> d.title)).containsExactly("可见的");
    }

    @Test
    @DisplayName("withoutGlobalScope 能按名字关掉 —— 全局作用域最大的风险是关不掉")
    void globalScopeCanBeDisabledByName() {
        draft("可见的", true);
        draft("隐藏的", false);

        assertThat(Draft.query().withoutGlobalScope("visible").get()).hasSize(2);
        assertThat(Draft.query().withoutGlobalScopes().get()).hasSize(2);
    }

    @Test
    @DisplayName("全局作用域只套一次,不会因为多次链式调用叠加条件")
    void globalScopeAppliedOnce() {
        draft("可见的", true);

        clearSql();
        Draft.query().whereTitle("可见的").orderByKey().limit(10).get();

        String select = sql().stream().filter(s -> s.startsWith("select")).findFirst().orElseThrow();
        // "visible" = ? 只应出现一次
        int occurrences = select.split("\"visible\"", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @DisplayName("count() 等聚合也受全局作用域约束")
    void globalScopeAppliesToAggregates() {
        draft("可见的", true);
        draft("隐藏的", false);

        assertThat(Draft.count()).isEqualTo(1);
        assertThat(Draft.query().withoutGlobalScopes().count()).isEqualTo(2);
    }

    // ================= 软删除 =================

    @Test
    @DisplayName("delete() 对 @SoftDeletes 模型写 deleted_at 而不是 DELETE")
    void softDeleteSetsTimestamp() {
        Draft draft = draft("要删的", true);

        clearSql();
        draft.delete();

        assertThat(sql()).noneMatch(s -> s.startsWith("delete"));
        assertThat(sql()).anyMatch(s -> s.contains("deleted_at"));
        assertThat(draft.deletedAt).isNotNull();
        assertThat(draft.isTrashed()).isTrue();
    }

    @Test
    @DisplayName("软删后默认查不到,withTrashed/onlyTrashed 能看到")
    void softDeletedRowsAreFilteredOut() {
        Draft kept = draft("留着", true);
        Draft gone = draft("删了", true);
        gone.delete();

        assertThat(Draft.all().map(d -> d.title)).containsExactly("留着");
        assertThat(Draft.withTrashed().get()).hasSize(2);
        assertThat(Draft.onlyTrashed().get().map(d -> d.title)).containsExactly("删了");
        assertThat(Draft.find(kept.id)).isPresent();
        assertThat(Draft.find(gone.id)).isEmpty();
    }

    @Test
    @DisplayName("restore() 还原")
    void restore() {
        Draft draft = draft("删了又回来", true);
        draft.delete();
        draft.restore();

        assertThat(draft.deletedAt).isNull();
        assertThat(draft.isTrashed()).isFalse();
        assertThat(Draft.find(draft.id)).isPresent();
    }

    @Test
    @DisplayName("forceDelete() 真删")
    void forceDelete() {
        Draft draft = draft("彻底删", true);
        Long id = draft.id;

        clearSql();
        draft.forceDelete();

        assertThat(sql()).anyMatch(s -> s.startsWith("delete"));
        assertThat(Draft.withTrashed().whereId(id).first()).isEmpty();
    }

    @Test
    @DisplayName("软删除与全局作用域叠加时两个条件都在")
    void softDeleteAndGlobalScopeCompose() {
        draft("可见未删", true);
        Draft hiddenDeleted = draft("隐藏已删", false);
        hiddenDeleted.delete();

        clearSql();
        Draft.all();
        String select = sql().stream().filter(s -> s.startsWith("select")).findFirst().orElseThrow();
        assertThat(select).contains("\"visible\"").contains("deleted_at");
    }

    @Test
    @DisplayName("User(也有 @SoftDeletes)的软删不影响无软删的 Post")
    void softDeleteIsPerModel() {
        User user = user("甲");
        user.delete();

        assertThat(User.find(user.id)).isEmpty();
        assertThat(User.withTrashed().whereId(user.id).first()).isPresent();

        Post post = post("硬删", true, 0);
        post.delete();
        assertThat(Post.find(post.id)).isEmpty();
    }

    // ================= 夹具 =================

    private Draft draft(String title, boolean visible) {
        Draft d = new Draft();
        d.title = title;
        d.visible = visible;
        d.save();
        return d;
    }

    private Post post(String title, boolean published, int views) {
        Post p = new Post();
        p.title = title;
        p.body = "正文";
        p.published = published;
        p.views = views;
        p.save();
        return p;
    }

    private User user(String name) {
        User u = new User();
        u.name = name;
        u.email = name + "@example.com";
        u.save();
        return u;
    }
}
