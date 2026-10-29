package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.ModelNotFoundException;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Post_;
import io.jaravel.eloquent.it.models.User;
import io.jaravel.eloquent.it.models.User_;

/** Active Record 的基本读写 + 快照脏检查 */
class ModelCrudTest extends EloquentTestBase {

    @Test
    @DisplayName("save() 插入后回填自增主键")
    void insertFillsKey() {
        Post post = new Post();
        post.title = "你好";
        post.body = "正文";
        post.save();

        assertThat(post.id).isNotNull().isPositive();
        assertThat(post.exists()).isTrue();
    }

    @Test
    @DisplayName("快照脏检查:只 UPDATE 真正变过的列")
    void updateOnlyDirtyColumns() {
        Post post = new Post();
        post.title = "原标题";
        post.body = "正文";
        post.save();

        clearSql();
        post.title = "新标题";
        assertThat(post.isDirty()).isTrue();
        assertThat(post.changes().keySet()).containsExactly("title");
        post.save();

        String update = sql().stream().filter(s -> s.startsWith("update")).findFirst().orElseThrow();
        assertThat(update).contains("\"title\"");
        // body 没动,不应出现在 SET 里 —— 这是 public 字段 + 快照方案的核心收益
        assertThat(update).doesNotContain("\"body\"");
    }

    @Test
    @DisplayName("没有变化时 save() 不发 SQL")
    void cleanSaveIsNoop() {
        Post post = new Post();
        post.title = "T";
        post.save();

        clearSql();
        post.save();
        assertThat(sql()).isEmpty();
    }

    @Test
    @DisplayName("getOriginal() 返回加载时的值")
    void originalKeepsLoadedValue() {
        Post post = new Post();
        post.title = "原";
        post.save();

        post.title = "改";
        assertThat(post.getOriginal(Post_.title)).isEqualTo("原");
        assertThat(post.title).isEqualTo("改");
    }

    @Test
    @DisplayName("refresh() 丢弃本地修改,重新读库")
    void refreshDiscardsLocalChanges() {
        Post post = new Post();
        post.title = "库里的值";
        post.save();

        post.title = "没保存的修改";
        post.refresh();

        assertThat(post.title).isEqualTo("库里的值");
        assertThat(post.isDirty()).isFalse();
    }

    @Test
    @DisplayName("find / findOrFail")
    void findById() {
        Post post = new Post();
        post.title = "找我";
        post.body = "正文";
        post.save();

        assertThat(Post.find(post.id)).isPresent()
                .get().extracting(p -> p.title).isEqualTo("找我");
        assertThat(Post.find(999_999L)).isEmpty();

        assertThatThrownBy(() -> Post.findOrFail(999_999L))
                .isInstanceOf(ModelNotFoundException.class)
                .hasMessageContaining("Post");
    }

    @Test
    @DisplayName("create(Map) 受 @Guarded 约束")
    void guardedBlocksMassAssignment() {
        Post post = Post.create(Map.of("title", "批量创建", "id", 123L));

        assertThat(post.title).isEqualTo("批量创建");
        // id 被 @Guarded({"id"}) 挡住,不该是 123
        assertThat(post.id).isNotEqualTo(123L);
    }

    @Test
    @DisplayName("@Fillable 白名单外的字段被忽略")
    void fillableWhitelist() {
        User user = User.create(Map.of("name", "阿花", "password", "明文密码"));

        assertThat(user.name).isEqualTo("阿花");
        // password 不在 @Fillable 列表里
        assertThat(user.password).isNull();
    }

    @Test
    @DisplayName("fillStrict() 对未知/被挡住的键报错而不是静默忽略")
    void fillStrictFailsLoud() {
        User user = new User();
        assertThatThrownBy(() -> user.fillStrict(Map.of("name", "ok", "password", "x")))
                .isInstanceOf(io.jaravel.eloquent.Model.MassAssignmentException.class)
                .hasMessageContaining("password")
                // 报错要同时告诉「有哪些列」和「哪些能批量赋值」,否则用户不知道往哪改
                .hasMessageContaining("可批量赋值的列");
    }

    @Test
    @DisplayName("fill() 用错列名时给出「是否想写」提示")
    void fillSuggestsClosestColumn() {
        assertThatThrownBy(() -> new Post().fillStrict(Map.of("titel", "拼错了")))
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("delete() 真删(无 @SoftDeletes 的模型)")
    void hardDelete() {
        Post post = new Post();
        post.title = "删我";
        post.save();
        Long id = post.id;

        assertThat(post.delete()).isTrue();
        assertThat(Post.find(id)).isEmpty();
    }

    @Test
    @DisplayName("replicate() 复制属性但不复制主键")
    void replicateDropsKey() {
        Post post = new Post();
        post.title = "原件";
        post.views = 7;
        post.save();

        Post copy = post.replicate();
        assertThat(copy.id).isNull();
        assertThat(copy.title).isEqualTo("原件");
        assertThat(copy.views).isEqualTo(7);
        assertThat(copy.exists()).isFalse();
    }

    @Test
    @DisplayName("toMap() 隐藏 @Hidden 列")
    void hiddenColumnsExcluded() {
        User user = new User();
        user.name = "小明";
        user.password = "秘密";
        user.save();

        Map<String, Object> map = user.toMap();
        assertThat(map).containsKey("name").doesNotContainKey("password");
    }

    @Test
    @DisplayName("@Cast.Use 在读写两侧都生效")
    void castRoundTrips() {
        User user = new User();
        user.name = "管理员";
        user.roles = List.of("admin", "editor");
        user.save();

        // 数据库里存的是逗号分隔文本
        var raw = io.jaravel.eloquent.Eloquent.connection()
                .table("users").where("id", user.id).get().first().orElseThrow();
        assertThat(raw.get("roles")).isEqualTo("admin,editor");

        // 读回来还是 List
        User reloaded = User.query().whereId(user.id).first().orElseThrow();
        assertThat(reloaded.roles).containsExactly("admin", "editor");
    }

    @Test
    @DisplayName("timestamps 自动维护")
    void timestampsManaged() {
        Post post = new Post();
        post.title = "计时";
        post.save();

        assertThat(post.createdAt).isNotNull();
        assertThat(post.updatedAt).isNotNull();

        var firstUpdate = post.updatedAt;
        post.title = "改了";
        post.save();
        assertThat(post.updatedAt).isAfterOrEqualTo(firstUpdate);
        assertThat(post.createdAt).isNotNull();
    }

    @Test
    @DisplayName("firstOrCreate 第二次不再插入")
    void firstOrCreateIsIdempotent() {
        User first = User.firstOrCreate(Map.of("name", "唯一"), Map.of("email", "a@b.c"));
        User second = User.firstOrCreate(Map.of("name", "唯一"), Map.of("email", "改不掉"));

        assertThat(second.id).isEqualTo(first.id);
        assertThat(second.email).isEqualTo("a@b.c");
        assertThat(User.query().whereName("唯一").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("firstOrCreate 列名拼错立刻报错,而不是让数据库报 no such column")
    void firstOrCreateValidatesColumnNames() {
        assertThatThrownBy(() -> User.firstOrCreate(Map.of("nmae", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("布尔与 int 在 SQLite 的 0/1 存储下往返正确")
    void primitiveRoundTrip() {
        Post post = new Post();
        post.title = "原始类型";
        post.body = "正文";
        post.published = true;
        post.views = 42;
        post.save();

        Post reloaded = Post.findOrFail(post.id);
        assertThat(reloaded.published).isTrue();
        assertThat(reloaded.views).isEqualTo(42);
    }

    @Test
    @DisplayName("元模型的列顺序与声明顺序一致")
    void metamodelPreservesDeclarationOrder() {
        assertThat(Post_.META.columnNames())
                .startsWith("id", "title", "body", "user_id", "published", "views");
        assertThat(Post_.META.table()).isEqualTo("posts");
        assertThat(User_.META.table()).isEqualTo("users");
    }
}
