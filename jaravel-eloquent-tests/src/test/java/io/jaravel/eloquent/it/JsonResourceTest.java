package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.api.JsonResource;
import io.jaravel.api.ResourceCollection;
import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Post_;
import io.jaravel.eloquent.it.models.User;
import io.jaravel.http.Request;
import io.jaravel.support.Paginated;

/**
 * API 资源(docs/12 §1)。
 *
 * <p>放在 {@code jaravel-eloquent-tests} 而不是 {@code jaravel-api} 自己的
 * 测试目录里,是因为这一节最重要的那条规则只有**真库**能验证:
 * 「资源层绝不触发懒加载」。拿替身测的话,关联的 {@code isLoaded()}
 * 想让它返回什么就返回什么 —— 而真正要防的是
 * 「一个列表接口发了 51 条查询」,那只有数 SQL 才看得出来。
 */
class JsonResourceTest extends EloquentTestBase {

    @AfterEach
    void resetResourceState() {
        JsonResource.resetWrapping();
        Paginated.reset();
    }

    /** 测试里不需要真的 Request —— 资源只在条件字段上用到它 */
    private static final Request NO_REQUEST = null;

    // ================= 夹具 =================

    /** 文章资源:两个关联字段都只在**已预加载**时输出 */
    static final class PostResource extends JsonResource<Post> {

        PostResource(Post post) {
            super(post);
        }

        @Override
        public Map<String, Object> toArray(Request request) {
            return map(request)
                    .put("id", model.id)
                    .put("title", model.title)
                    .put("views", model.views)
                    .putWhen(model.published, "published_note", () -> "已发布")
                    .putWhenLoadedOne("author", model.author(), UserResource::new)
                    .putWhenLoaded("comments", model.comments(),
                            comments -> comments.size())
                    .build();
        }
    }

    static final class UserResource extends JsonResource<User> {

        UserResource(User user) {
            super(user);
        }

        @Override
        public Map<String, Object> toArray(Request request) {
            return map(request).put("id", model.id).put("name", model.name).build();
        }
    }

    /** 带集合级元数据的集合 */
    static final class PostCollection extends ResourceCollection<Post, PostResource> {

        PostCollection(List<PostResource> items) {
            super(items);
        }

        @Override
        public Map<String, Object> with(Request request) {
            return Map.of("stats", Map.of("count", size()));
        }
    }

    private Post seedPost(String title, boolean published) {
        User author = new User();
        author.name = "作者-" + title;
        author.save();

        Post post = new Post();
        post.title = title;
        post.body = "正文";
        post.userId = author.id;
        post.published = published;
        post.views = 10;
        post.save();
        return post;
    }

    // ================= 懒加载禁止 =================

    @Nested
    @DisplayName("★ 资源层绝不触发懒加载")
    class NeverLazyLoads {

        @Test
        @DisplayName("★ 没预加载时:跳过那个字段,并且**一条查询都不发**")
        void skipsUnloadedRelationsWithoutQuerying() {
            seedPost("文章1", true);
            Post post = Post.query().firstOrFail();
            clearSql();

            Map<String, Object> json = new PostResource(post).toArray(NO_REQUEST);

            assertThat(selectCount())
                    .as("""
                            这是整节最重要的断言。一个列表接口返回 50 条,
                            而资源里碰了一下没预加载的关联 —— 就是 51 条查询。
                            那个 bug 在开发期的三条测试数据上完全看不出来,
                            它只在生产的分页列表上出现。""")
                    .isZero();
            assertThat(json)
                    .as("没加载的关联不输出 —— 前端拿不到 author 就知道这个接口没预加载它")
                    .doesNotContainKeys("author", "comments");
            assertThat(json).containsKeys("id", "title", "views");
        }

        @Test
        @DisplayName("预加载过的关联正常输出,而且仍然不额外查")
        void outputsLoadedRelations() {
            seedPost("文章2", true);
            Post post = Post.query().load(Post_.author).firstOrFail();
            clearSql();

            Map<String, Object> json = new PostResource(post).toArray(NO_REQUEST);

            assertThat(selectCount()).isZero();
            @SuppressWarnings("unchecked")
            var author = (Map<String, Object>) json.get("author");
            assertThat(author.get("name")).isEqualTo("作者-文章2");
        }

        @Test
        @DisplayName("★ 「加载了但是空的」输出 null,而不是跳过 —— 两件事必须分开")
        void loadedButEmptyIsNull() {
            Post post = new Post();
            post.title = "没有作者";
            post.body = "正文";
            post.userId = null;
            post.save();

            Post loaded = Post.query().load(Post_.author).whereId(post.id).firstOrFail();
            Map<String, Object> json = new PostResource(loaded).toArray(NO_REQUEST);

            assertThat(json)
                    .as("""
                            「没预加载」是接口的实现细节(跳过);
                            「加载了但没有」是数据的事实(null)。
                            前端看到 author: null 知道这篇没有作者;
                            看不到 author 这个键只能去问后端。""")
                    .containsKey("author");
            assertThat(json.get("author")).isNull();
        }
    }

    // ================= 条件字段 =================

    @Nested
    @DisplayName("条件字段")
    class Conditional {

        @Test
        @DisplayName("条件成立才有那个键")
        void putWhen() {
            Post published = seedPost("已发布", true);
            Post draft = seedPost("草稿", false);

            assertThat(new PostResource(published).toArray(NO_REQUEST))
                    .containsEntry("published_note", "已发布");
            assertThat(new PostResource(draft).toArray(NO_REQUEST))
                    .doesNotContainKey("published_note");
        }

        @Test
        @DisplayName("★ 条件不成立时那个值**根本不求值** —— 它可能有代价")
        void valueIsNotEvaluatedWhenSkipped() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();

            Map<String, Object> json = new JsonResource<String>("x") {
                @Override
                public Map<String, Object> toArray(Request request) {
                    return map(request)
                            .putWhen(false, "expensive", () -> {
                                calls.incrementAndGet();
                                return "算出来的";
                            })
                            .build();
                }
            }.toArray(NO_REQUEST);

            assertThat(json).isEmpty();
            assertThat(calls.get())
                    .as("""
                            条件字段的取值常常有代价或有前提(解密、再查一次、
                            访问另一个对象)。直接传值会让它无论条件成不成立
                            都被求值一次 —— 而那种「只给管理员看」的字段,
                            恰恰是不该为普通用户去算的。""")
                    .isZero();
        }

        @Test
        @DisplayName("mergeWhen 合并一组字段")
        void mergeWhen() {
            Map<String, Object> json = new JsonResource<String>("x") {
                @Override
                public Map<String, Object> toArray(Request request) {
                    return map(request)
                            .put("id", 1)
                            .mergeWhen(true, () -> Map.of("a", 1, "b", 2))
                            .mergeWhen(false, () -> Map.of("c", 3))
                            .build();
                }
            }.toArray(NO_REQUEST);

            assertThat(json).containsKeys("id", "a", "b").doesNotContainKey("c");
        }
    }

    // ================= 包裹 =================

    @Nested
    @DisplayName("data 包裹")
    class Wrapping {

        @Test
        @DisplayName("默认包一层 data(与 Laravel 一致)")
        void wrapsByDefault() {
            Post post = seedPost("文章", true);

            Map<String, Object> root = new PostResource(post).toRootBody(NO_REQUEST);

            assertThat(root).containsOnlyKeys("data");
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) root.get("data");
            assertThat(data).containsKey("title");
        }

        @Test
        @DisplayName("withoutWrapping 之后字段直接在顶层")
        void canBeDisabled() {
            Post post = seedPost("文章", true);
            JsonResource.withoutWrapping();

            assertThat(new PostResource(post).toRootBody(NO_REQUEST))
                    .containsKey("title")
                    .doesNotContainKey("data");
        }

        @Test
        @DisplayName("★ 嵌套位置不包裹 —— 否则会变成 data 里又一层 data")
        void nestedResourcesAreNotWrapped() {
            seedPost("文章", true);
            Post post = Post.query().load(Post_.author).firstOrFail();

            @SuppressWarnings("unchecked")
            var author = (Map<String, Object>) new PostResource(post)
                    .toArray(NO_REQUEST).get("author");

            assertThat(author)
                    .as("toArray 永远是不带包裹的那一份;包裹只由最外层加")
                    .containsOnlyKeys("id", "name");
        }

        @Test
        @DisplayName("with 的顶层附加信息与 data 并列")
        void topLevelExtras() {
            Map<String, Object> root = new JsonResource<String>("x") {
                @Override
                public Map<String, Object> toArray(Request request) {
                    return map(request).put("id", 1).build();
                }

                @Override
                public Map<String, Object> with(Request request) {
                    return Map.of("meta", Map.of("version", "1.0"));
                }
            }.toRootBody(NO_REQUEST);

            assertThat(root).containsOnlyKeys("data", "meta");
        }
    }

    // ================= 集合 =================

    @Nested
    @DisplayName("集合")
    class Collections {

        @Test
        @DisplayName("collection 把一批模型变成一批资源")
        void collectionOfModels() {
            seedPost("A", true);
            seedPost("B", false);

            var resources = JsonResource.collection(
                    Post.query().orderBy(Post_.title).get(), PostResource::new);

            assertThat(resources.size()).isEqualTo(2);
            assertThat(resources.toArray(NO_REQUEST).get(0).get("title")).isEqualTo("A");
        }

        @Test
        @DisplayName("根位置:data 是一个**数组**")
        void rootIsAnArray() {
            seedPost("A", true);

            Object root = JsonResource.collection(Post.query().get(), PostResource::new)
                    .toRootBody(NO_REQUEST);

            assertThat(root).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) root;
            assertThat(map.get("data")).isInstanceOf(List.class);
        }

        @Test
        @DisplayName("★ 关掉包裹且没有元数据时,响应体是一个裸数组")
        void bareArrayWhenUnwrapped() {
            seedPost("A", true);
            JsonResource.withoutWrapping();

            Object root = JsonResource.collection(Post.query().get(), PostResource::new)
                    .toRootBody(NO_REQUEST);

            assertThat(root)
                    .as("""
                            这正是 withoutWrapping 的全部意义。
                            第一版让 toRootBody 固定返回 Map,于是这种情况只能塞进
                            一个假键再让序列化器认出来 —— 返回类型如实说出
                            「两种形状」更好。""")
                    .isInstanceOf(List.class);
        }

        @Test
        @DisplayName("集合级元数据与 data 并列")
        void collectionMetadata() {
            seedPost("A", true);
            seedPost("B", true);

            var collection = new PostCollection(
                    Post.query().get().stream().map(PostResource::new).toList());

            @SuppressWarnings("unchecked")
            var root = (Map<String, Object>) collection.toRootBody(NO_REQUEST);
            assertThat(root).containsOnlyKeys("data", "stats");
            assertThat(root.get("stats")).isEqualTo(Map.of("count", 2));
        }
    }

    // ================= 与分页结合 =================

    @Test
    @DisplayName("★ 分页 + 资源:links/meta 一字不差,只有 data 变成资源(docs/12 §2.3)")
    void paginationPlusResources() {
        for (int i = 1; i <= 7; i++) seedPost("文章" + i, true);
        Paginated.pathResolver(() -> "https://api.example.com/posts");

        Paginated<Post> page = Post.query().orderBy(Post_.title).paginate(5, 1);
        Paginated<PostResource> resources = JsonResource.collection(page, PostResource::new);

        assertThat(resources.toMap().get("links")).isEqualTo(page.toMap().get("links"));
        assertThat(resources.toMap().get("meta")).isEqualTo(page.toMap().get("meta"));
        assertThat(resources.items()).hasSize(5).allMatch(r -> r instanceof PostResource);
    }
}
