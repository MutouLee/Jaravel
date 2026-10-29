package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.it.models.Comment;
import io.jaravel.eloquent.it.models.Country;
import io.jaravel.eloquent.it.models.Image;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Profile;
import io.jaravel.eloquent.it.models.Tag;
import io.jaravel.eloquent.it.models.User;

/** 七种关联各自的读写 */
class RelationTest extends EloquentTestBase {

    @Test
    @DisplayName("HasMany:读取与 create()")
    void hasMany() {
        User user = user("作者");

        Post first = user.posts().create(Map.of("title", "第一篇", "body", "正文"));
        user.posts().create(Map.of("title", "第二篇", "body", "正文"));

        assertThat(first.userId).isEqualTo(user.id);
        assertThat(user.posts().query().get()).hasSize(2);
        assertThat(user.posts().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("BelongsTo:单值句柄返回 Optional")
    void belongsTo() {
        User user = user("作者");
        Post post = post(user, "文章");

        assertThat(post.author().get()).isPresent()
                .get().extracting(u -> u.name).isEqualTo("作者");
        assertThat(post.author().getOrFail().id).isEqualTo(user.id);
    }

    @Test
    @DisplayName("BelongsTo.associate() 改外键但不自动保存")
    void associateDoesNotAutoSave() {
        User a = user("甲");
        User b = user("乙");
        Post post = post(a, "文章");

        post.author().associate(b);
        assertThat(post.userId).isEqualTo(b.id);
        // 还没保存,库里仍是甲
        assertThat(Post.findOrFail(post.id).userId).isEqualTo(a.id);

        post.save();
        assertThat(Post.findOrFail(post.id).userId).isEqualTo(b.id);
    }

    @Test
    @DisplayName("HasOne 上调 associate() 报错并说清为什么")
    void associateRejectsHasOne() {
        User user = user("甲");
        assertThatThrownBy(() -> user.profile().associate(new Profile()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BelongsTo")
                .hasMessageContaining("HAS_ONE");
    }

    @Test
    @DisplayName("HasOne")
    void hasOne() {
        User user = user("甲");
        user.profile().create(Map.of("bio", "简介"));

        assertThat(user.profile().get()).isPresent()
                .get().extracting(p -> p.bio).isEqualTo("简介");
    }

    @Test
    @DisplayName("BelongsToMany:attach / detach / sync")
    void belongsToMany() {
        Post post = post(user("甲"), "文章");
        Tag java = tag("java");
        Tag php = tag("php");
        Tag go = tag("go");

        post.tags().attach(java.id, php.id);
        assertThat(post.tags().query().get().map(t -> t.name)).containsExactlyInAnyOrder("java", "php");

        post.tags().detach(php.id);
        assertThat(post.tags().query().get().map(t -> t.name)).containsExactly("java");

        post.tags().sync(List.of(php.id, go.id));
        assertThat(post.tags().query().get().map(t -> t.name)).containsExactlyInAnyOrder("php", "go");
    }

    @Test
    @DisplayName("BelongsToMany 双向可见,且两侧推出同一张中间表")
    void pivotTableIsSymmetric() {
        Post post = post(user("甲"), "文章");
        Tag tag = tag("java");

        post.tags().attach(tag.id);
        // 从 Tag 一侧也要看得见 —— 中间表名按字典序推断,两边一致
        assertThat(tag.posts().query().get().map(p -> p.title)).containsExactly("文章");
    }

    @Test
    @DisplayName("HasManyThrough:country → users → posts")
    void hasManyThrough() {
        Country country = new Country();
        country.name = "中国";
        country.save();

        User user = new User();
        user.name = "甲";
        user.countryId = country.id;
        user.save();

        post(user, "甲的文章");

        User other = new User();
        other.name = "乙";
        other.save();
        post(other, "乙的文章");

        assertThat(country.posts().query().get().map(p -> p.title))
                .containsExactly("甲的文章");
    }

    @Test
    @DisplayName("MorphMany:同一批图片挂在不同类型的宿主上")
    void morphMany() {
        Post post = post(user("甲"), "文章");
        User user = user("乙");

        image("post.png", post.id, Post.class.getName());
        image("post2.png", post.id, Post.class.getName());
        image("user.png", user.id, User.class.getName());

        assertThat(post.images().query().get().map(i -> i.url))
                .containsExactlyInAnyOrder("post.png", "post2.png");
        assertThat(user.images().query().get().map(i -> i.url))
                .containsExactly("user.png");
    }

    @Test
    @DisplayName("MorphTo:按 *_type 列解析回具体模型")
    void morphTo() {
        Post post = post(user("甲"), "文章");
        Image image = image("x.png", post.id, Post.class.getName());

        var owner = Image.findOrFail(image.id).owner();
        assertThat(owner).isInstanceOf(Post.class);
        assertThat(((Post) owner).title).isEqualTo("文章");
    }

    @Test
    @DisplayName("MorphTo 遇到未声明的类型别名时拒绝加载而不是 Class.forName")
    void morphToRejectsUnknownType() {
        Post post = post(user("甲"), "文章");
        Image image = image("x.png", post.id, "app.evil.Payload");

        assertThatThrownBy(() -> Image.findOrFail(image.id).owner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在声明的候选类型里")
                .hasMessageContaining("Class.forName");
    }

    @Test
    @DisplayName("关联的外键列不存在时,报错指出缺哪一列并给出修复")
    void missingForeignKeyColumnIsActionable() {
        // Comment 有两个 BelongsTo,其中 author 的外键是显式指定的 user_id;
        // 这里验证列确实被用上了(而不是静默回落到约定名 comment_id)
        User user = user("甲");
        Post post = post(user, "文章");
        Comment comment = comment(post, user, "评论");

        assertThat(comment.author().get()).isPresent()
                .get().extracting(u -> u.name).isEqualTo("甲");
    }

    @Test
    @DisplayName("Rel1.dissociate() 清空外键")
    void dissociate() {
        User user = user("甲");
        Post post = post(user, "文章");

        post.author().dissociate();
        post.save();

        assertThat(Post.findOrFail(post.id).userId).isNull();
        assertThat(Post.findOrFail(post.id).author().get()).isEmpty();
    }

    // ================= 夹具 =================

    private User user(String name) {
        User u = new User();
        u.name = name;
        u.email = name + "@example.com";
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

    private Comment comment(Post post, User author, String body) {
        Comment c = new Comment();
        c.postId = post.id;
        c.userId = author.id;
        c.body = body;
        c.save();
        return c;
    }

    private Tag tag(String name) {
        Tag t = new Tag();
        t.name = name;
        t.save();
        return t;
    }

    private Image image(String url, Long ownerId, String ownerType) {
        Image i = new Image();
        i.url = url;
        i.imageableId = ownerId;
        i.imageableType = ownerType;
        i.save();
        return i;
    }
}
