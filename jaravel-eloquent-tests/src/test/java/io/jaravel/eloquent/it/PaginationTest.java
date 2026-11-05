package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Post_;
import io.jaravel.eloquent.it.models.User;
import io.jaravel.support.Paginated;

/**
 * 分页(docs/12 §2)。
 *
 * <p>真 SQLite —— 分页的全部内容是**两条 SQL 的配合**
 * (一条 count、一条带 limit/offset),而那种配合只有真执行才看得出来:
 * 「count 受不受 limit 影响」这件事在替身上永远是对的。
 */
class PaginationTest extends EloquentTestBase {

    @AfterEach
    void clearResolvers() {
        Paginated.reset();
    }

    private void seed(int posts) {
        User author = new User();
        author.name = "作者";
        author.save();

        for (int i = 1; i <= posts; i++) {
            Post p = new Post();
            p.title = "文章" + i;
            p.body = "正文";
            p.userId = author.id;
            p.views = i;
            p.published = i % 2 == 0;
            p.save();
        }
    }

    // ================= paginate =================

    @Nested
    @DisplayName("paginate(带总数)")
    class WithTotal {

        @Test
        @DisplayName("★ 第一页:拿到 per_page 条,而总数是**全部**符合条件的")
        void firstPage() {
            seed(25);

            Paginated<Post> page = Post.query().orderBy(Post_.views).paginate(10, 1);

            assertThat(page.size()).isEqualTo(10);
            assertThat(page.total()).contains(25L);
            assertThat(page.lastPage()).contains(3);
            assertThat(page.from()).contains(1);
            assertThat(page.to()).contains(10);
            assertThat(page.hasMorePages()).isTrue();
            assertThat(page.items().get(0).title).isEqualTo("文章1");
        }

        @Test
        @DisplayName("最后一页:只剩 5 条,hasMorePages 为 false")
        void lastPage() {
            seed(25);

            Paginated<Post> page = Post.query().orderBy(Post_.views).paginate(10, 3);

            assertThat(page.size()).isEqualTo(5);
            assertThat(page.from()).contains(21);
            assertThat(page.to()).contains(25);
            assertThat(page.hasMorePages()).isFalse();
        }

        @Test
        @DisplayName("★ count 不受调用方已经写的 limit 影响")
        void countIgnoresAnExistingLimit() {
            seed(25);

            Paginated<Post> page = Post.query().limit(3).paginate(10, 1);

            assertThat(page.total())
                    .as("""
                            总数是「符合条件的全部」,不是「limit 之后的」。
                            QueryBuilder.aggregate 在编译 count 之前会临时摘掉
                            order / limit / offset —— 这条测试钉住那个行为,
                            因为它在替身上永远是对的,只有真执行才看得出来。""")
                    .contains(25L);
        }

        @Test
        @DisplayName("★ 条件与分页配合:where 之后的总数是**筛选之后**的")
        void totalRespectsWhere() {
            seed(25);

            Paginated<Post> page = Post.query()
                    .where(Post_.published, true)
                    .paginate(5, 1);

            assertThat(page.total())
                    .as("25 条里偶数 views 的有 12 条")
                    .contains(12L);
            assertThat(page.lastPage()).contains(3);
        }

        @Test
        @DisplayName("一条都没有:空页而不是报错,total = 0")
        void emptyResult() {
            Paginated<Post> page = Post.query().paginate(10, 1);

            assertThat(page.isEmpty()).isTrue();
            assertThat(page.total()).contains(0L);
            assertThat(page.lastPage())
                    .as("一页都没有时 last_page 仍然是 1 —— 0 会让前端的页码渲染成空")
                    .contains(1);
            assertThat(page.from()).isEmpty();
        }

        @Test
        @DisplayName("页码超出范围:空的 data,而不是 404 或者回落到第一页")
        void pageBeyondTheEnd() {
            seed(5);

            Paginated<Post> page = Post.query().paginate(10, 99);

            assertThat(page.isEmpty())
                    .as("""
                            与 Laravel 一致。回落到第一页会更糟:
                            用户点了「第 99 页」却看到第 1 页的内容,
                            而 URL 上还写着 99。""")
                    .isTrue();
            assertThat(page.total()).contains(5L);
            assertThat(page.currentPage()).isEqualTo(99);
        }

        @Test
        @DisplayName("每页 0 条直接报错 —— 它会让调用方的循环跑不完")
        void perPageMustBePositive() {
            assertThatThrownBy(() -> Post.query().paginate(0, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("perPage");
        }

        @Test
        @DisplayName("只发两条 SQL:一条 count,一条取页")
        void exactlyTwoQueries() {
            seed(25);
            clearSql();

            Post.query().paginate(10, 1);

            assertThat(selectCount())
                    .as("分页不该顺带把别的东西查一遍")
                    .isEqualTo(2);
        }
    }

    // ================= simplePaginate =================

    @Nested
    @DisplayName("simplePaginate(不查总数)")
    class WithoutTotal {

        @Test
        @DisplayName("★ 只发一条 SQL —— 省掉的那条 count 在大表上是一次全扫")
        void onlyOneQuery() {
            seed(25);
            clearSql();

            Post.query().simplePaginate(10, 1);

            assertThat(selectCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 多取一条来判断「还有下一页」,而那一条不会出现在 data 里")
        void extraRowIsNotLeaked() {
            seed(25);

            Paginated<Post> page = Post.query().orderBy(Post_.views).simplePaginate(10, 1);

            assertThat(page.size())
                    .as("取了 11 条,但只能给出 10 条 —— 多的那条是用来探路的")
                    .isEqualTo(10);
            assertThat(page.hasMorePages()).isTrue();
            assertThat(page.items().get(9).title).isEqualTo("文章10");
        }

        @Test
        @DisplayName("刚好一页时 hasMorePages 为 false")
        void exactlyOnePage() {
            seed(10);

            Paginated<Post> page = Post.query().simplePaginate(10, 1);

            assertThat(page.size()).isEqualTo(10);
            assertThat(page.hasMorePages())
                    .as("第 11 条取不到 —— 这正是「多取一条」能分辨的情形")
                    .isFalse();
        }

        @Test
        @DisplayName("没有 total,所以 total() 与 lastPage() 都是 empty,而不是猜一个值")
        void totalIsAbsent() {
            seed(25);

            Paginated<Post> page = Post.query().simplePaginate(10, 1);

            assertThat(page.total()).isEmpty();
            assertThat(page.lastPage())
                    .as("一个编出来的 last_page 会让前端渲染出一堆点不动的页码")
                    .isEmpty();
        }
    }

    // ================= resolver =================

    @Nested
    @DisplayName("当前页与链接从 resolver 来")
    class Resolvers {

        @Test
        @DisplayName("没装 resolver 时当成第 1 页 —— 队列任务与测试里那正是对的")
        void defaultsToPageOne() {
            seed(25);

            assertThat(Post.query().paginate(10).currentPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 装上之后 paginate(15) 就能读到 ?page=N")
        void pageComesFromTheResolver() {
            seed(25);
            Paginated.pageResolver(() -> 2);

            Paginated<Post> page = Post.query().orderBy(Post_.views).paginate(10);

            assertThat(page.currentPage()).isEqualTo(2);
            assertThat(page.items().get(0).title).isEqualTo("文章11");
        }

        @Test
        @DisplayName("?page=abc 这种不该让请求炸掉 —— 当成第 1 页")
        void brokenResolverFallsBack() {
            seed(5);
            Paginated.pageResolver(() -> {
                throw new NumberFormatException("abc");
            });

            assertThat(Post.query().paginate(10).currentPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 链接里要保留其它查询参数 —— 丢了它们翻页会把筛选条件弄丢")
        void linksKeepOtherQueryParameters() {
            seed(25);
            Paginated.pathResolver(() -> "https://api.example.com/posts");
            Paginated.queryResolver(() -> Map.of("status", "published"));

            Map<String, Object> json = Post.query().paginate(10, 2).toMap();

            @SuppressWarnings("unchecked")
            var links = (Map<String, Object>) json.get("links");
            assertThat((String) links.get("next"))
                    .contains("status=published")
                    .contains("page=3");
            assertThat((String) links.get("prev")).contains("page=1");
        }

        @Test
        @DisplayName("没有 path 时 links 全是 null,而不是半截 URL")
        void noPathMeansNoLinks() {
            seed(5);

            @SuppressWarnings("unchecked")
            var links = (Map<String, Object>) Post.query().paginate(10, 1).toMap().get("links");

            assertThat(links.get("first")).isNull();
            assertThat(links.get("next")).isNull();
        }
    }

    // ================= JSON 形状 =================

    @Nested
    @DisplayName("JSON 形状(与 Laravel 逐字段一致)")
    class JsonShape {

        @Test
        @DisplayName("★ 字段名与 Laravel 一字不差 —— 前端的分页组件读的就是这些名字")
        void fieldNamesMatchLaravel() {
            seed(25);
            Paginated.pathResolver(() -> "https://api.example.com/posts");

            Map<String, Object> json = Post.query().paginate(10, 1).toMap();

            assertThat(json).containsOnlyKeys("data", "links", "meta");

            @SuppressWarnings("unchecked")
            var meta = (Map<String, Object>) json.get("meta");
            assertThat(meta)
                    .as("少一个下划线就等于要用户自己写适配层")
                    .containsKeys("current_page", "from", "last_page", "path",
                            "per_page", "to", "total");
            assertThat(meta.get("current_page")).isEqualTo(1);
            assertThat(meta.get("per_page")).isEqualTo(10);
            assertThat(meta.get("total")).isEqualTo(25L);
            assertThat(meta.get("path")).isEqualTo("https://api.example.com/posts");

            @SuppressWarnings("unchecked")
            var links = (Map<String, Object>) json.get("links");
            assertThat(links).containsOnlyKeys("first", "last", "prev", "next");
            assertThat(links.get("prev")).as("第一页没有上一页").isNull();
        }

        @Test
        @DisplayName("simplePaginate 的 JSON 不输出 total / last_page")
        void simpleShapeOmitsTotals() {
            seed(25);

            @SuppressWarnings("unchecked")
            var meta = (Map<String, Object>) Post.query()
                    .simplePaginate(10, 1).toMap().get("meta");

            assertThat(meta)
                    .as("输出一个猜的 total 比不输出更糟")
                    .doesNotContainKeys("total", "last_page");
            assertThat(meta).containsKeys("current_page", "per_page");
        }

        @Test
        @DisplayName("字段顺序稳定 —— 把响应存下来 diff 不该全是噪音")
        void fieldOrderIsStable() {
            seed(5);

            assertThat(Post.query().paginate(10, 1).toMap().keySet())
                    .containsExactly("data", "links", "meta");
        }
    }

    // ================= map =================

    @Test
    @DisplayName("★ map 换掉元素类型,分页信息一字不差 —— 这是「分页 + 资源」的接缝")
    void mapKeepsPaginationIntact() {
        seed(25);
        Paginated.pathResolver(() -> "https://api.example.com/posts");

        Paginated<Post> page = Post.query().orderBy(Post_.views).paginate(10, 2);
        Paginated<String> titles = page.map(post -> post.title);

        assertThat(titles.items()).hasSize(10).first().isEqualTo("文章11");
        assertThat(titles.currentPage()).isEqualTo(2);
        assertThat(titles.total()).contains(25L);
        assertThat(titles.toMap().get("links"))
                .as("path 与查询参数也要跟过去,否则 links 会变成 null")
                .isEqualTo(page.toMap().get("links"));
    }
}
