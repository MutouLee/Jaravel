package io.jaravel.eloquent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.eloquent.it.models.Post;
import io.jaravel.eloquent.it.models.Post_;
import io.jaravel.eloquent.it.models.User;
import io.jaravel.support.Cursor;
import io.jaravel.support.Cursored;
import io.jaravel.support.Paginated;

/**
 * 游标分页(docs/12 §2.1 的第三种)。
 *
 * <p>这一组里最重要的两条测试都需要**有重复排序值的真数据** ——
 * 「翻页时跳行 / 重复」是游标分页最常见的 bug,而它只在那种数据上出现。
 * 开发期的几条各不相同的测试数据上完全看不见。
 */
class CursorPaginationTest extends EloquentTestBase {

    @AfterEach
    void clearResolvers() {
        Cursored.reset();
        Paginated.reset();
    }

    /** views 刻意重复 —— 用来测「同值内靠主键定序」 */
    private void seed(int count, boolean duplicateViews) {
        User author = new User();
        author.name = "作者";
        author.save();

        for (int i = 1; i <= count; i++) {
            Post p = new Post();
            p.title = "文章" + i;
            p.body = "正文";
            p.userId = author.id;
            p.views = duplicateViews ? i % 3 : i;
            p.save();
        }
    }

    /** 顺着游标一直翻,把看到的标题收集起来 */
    private List<String> walkForward(int perPage) {
        var seen = new ArrayList<String>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            Cursored<Post> page = Post.query().orderBy(Post_.views).cursorPaginate(perPage, cursor);
            page.items().forEach(p -> seen.add(p.title));
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        return seen;
    }

    // ================= 翻页的正确性 =================

    @Nested
    @DisplayName("★ 翻页既不跳行也不重复")
    class Walking {

        @Test
        @DisplayName("★ 排序值有重复时也要走完全部 —— 这是游标分页最常见的 bug")
        void coversEveryRowWithDuplicateSortValues() {
            seed(10, true);   // views 只有 0/1/2 三种值

            List<String> seen = walkForward(3);

            assertThat(seen)
                    .as("""
                            按 views 排而同一个值有三四条时,
                            where views > 2 会把那一批剩下的整个跳过;
                            而 >= 又会重复给出。
                            所以主键被追加为最后一级排序 ——
                            (views, id) 唯一,字典序比较就不跳也不重。""")
                    .hasSize(10)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("每页刚好取满,最后一页不多不少")
        void pageSizes() {
            seed(7, false);

            Cursored<Post> first = Post.query().orderBy(Post_.views).cursorPaginate(3);
            assertThat(first.size()).isEqualTo(3);
            assertThat(first.hasMorePages()).isTrue();

            Cursored<Post> second = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3, first.nextCursor());
            Cursored<Post> third = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3, second.nextCursor());

            assertThat(third.size()).isEqualTo(1);
            assertThat(third.hasMorePages())
                    .as("多取一条探路:第 4 条取不到,所以知道到底了")
                    .isFalse();
            assertThat(third.nextCursor()).isNull();
        }

        @Test
        @DisplayName("★ 往回翻拿到的是正着的顺序,而不是倒的")
        void walkingBackwards() {
            seed(9, false);

            Cursored<Post> first = Post.query().orderBy(Post_.views).cursorPaginate(3);
            Cursored<Post> second = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3, first.nextCursor());
            Cursored<Post> back = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3, second.prevCursor());

            assertThat(back.items().stream().map(p -> p.title).toList())
                    .as("""
                            往回翻时查询是**反着排**的(否则取不到「前面那一批」),
                            所以结果必须翻回来 —— 不翻的话用户看到的列表
                            在往回翻之后顺序是乱的。""")
                    .containsExactlyElementsOf(
                            first.items().stream().map(p -> p.title).toList());
        }

        @Test
        @DisplayName("倒序排也一样走得完")
        void descendingOrder() {
            seed(8, false);
            var seen = new ArrayList<String>();
            String cursor = null;

            for (int guard = 0; guard < 20; guard++) {
                Cursored<Post> page = Post.query().orderByDesc(Post_.views)
                        .cursorPaginate(3, cursor);
                page.items().forEach(p -> seen.add(p.title));
                cursor = page.nextCursor();
                if (cursor == null) break;
            }

            assertThat(seen).hasSize(8).doesNotHaveDuplicates();
            assertThat(seen.get(0)).as("倒序,所以 views 最大的那条在最前").isEqualTo("文章8");
        }

        @Test
        @DisplayName("一个 orderBy 都没写时按主键排 —— 而不是给出不确定的顺序")
        void withoutExplicitOrder() {
            seed(5, false);

            Cursored<Post> page = Post.query().cursorPaginate(2);

            assertThat(page.size()).isEqualTo(2);
            assertThat(page.items().get(0).title)
                    .as("没有确定的排序,游标分页就没有意义 —— 所以兜底按主键")
                    .isEqualTo("文章1");
        }

        @Test
        @DisplayName("条件与游标共存:where 之后仍然只走符合条件的")
        void respectsWhere() {
            seed(10, false);
            var seen = new ArrayList<String>();
            String cursor = null;

            for (int guard = 0; guard < 20; guard++) {
                Cursored<Post> page = Post.query()
                        .where(Post_.views, io.jaravel.database.query.QueryState.Op.GT, 5)
                        .orderBy(Post_.views)
                        .cursorPaginate(2, cursor);
                page.items().forEach(p -> seen.add(p.title));
                cursor = page.nextCursor();
                if (cursor == null) break;
            }

            assertThat(seen).hasSize(5);   // views 6..10
        }

        @Test
        @DisplayName("一条都没有:空页,两个游标都是 null")
        void emptyResult() {
            Cursored<Post> page = Post.query().cursorPaginate(10);

            assertThat(page.isEmpty()).isTrue();
            assertThat(page.nextCursor()).isNull();
            assertThat(page.prevCursor()).isNull();
        }

        @Test
        @DisplayName("第一页没有 prevCursor;第二页有")
        void previousCursorOnlyAfterTheFirstPage() {
            seed(6, false);

            Cursored<Post> first = Post.query().orderBy(Post_.views).cursorPaginate(3);
            assertThat(first.prevCursor())
                    .as("「有没有上一页」只能看「我是不是带着游标来的」—— 本页数据判断不出来")
                    .isNull();

            Cursored<Post> second = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3, first.nextCursor());
            assertThat(second.prevCursor()).isNotNull();
        }
    }

    // ================= 游标本身 =================

    @Nested
    @DisplayName("游标的编码")
    class Encoding {

        @Test
        @DisplayName("★ 带冒号的值(时间戳!)不会把游标切坏")
        void escapesSeparators() {
            var position = new java.util.LinkedHashMap<String, Object>();
            position.put("created_at", java.time.Instant.parse("2026-11-05T10:00:00Z"));
            position.put("id", 42L);

            Cursor decoded = Cursor.decode(Cursor.at(position, true).encode());

            assertThat(decoded.valueOf("created_at"))
                    .as("""
                            2026-11-05T10:00:00Z 里有三个冒号,而冒号是段内分隔符。
                            不转义的话那一段会被切成五块 —— 而时间戳恰恰是
                            最常见的排序列值,所以这不是防御性编程。""")
                    .isEqualTo(java.time.Instant.parse("2026-11-05T10:00:00Z"));
            assertThat(decoded.valueOf("id")).isEqualTo(42L);
            assertThat(decoded.pointsToNext()).isTrue();
        }

        @Test
        @DisplayName("★ 类型要带着走 —— 全当字符串的话翻页条件永远不成立")
        void preservesTypes() {
            var position = new java.util.LinkedHashMap<String, Object>();
            position.put("id", 42L);
            position.put("title", "文章:一");
            position.put("published", true);

            Cursor decoded = Cursor.decode(Cursor.at(position, true).encode());

            assertThat(decoded.valueOf("id"))
                    .as("""
                            SQLite 的类型亲和性会把 "42" 与 42 当成不同的东西比较,
                            于是翻页条件永远不成立 —— 列表卡在第一页。""")
                    .isInstanceOf(Long.class).isEqualTo(42L);
            assertThat(decoded.valueOf("title")).isEqualTo("文章:一");
            assertThat(decoded.valueOf("published")).isEqualTo(true);
        }

        @Test
        @DisplayName("URL 安全的 base64 —— 它要进查询串")
        void urlSafeEncoding() {
            var position = new java.util.LinkedHashMap<String, Object>();
            for (int i = 0; i < 20; i++) position.put("c" + i, "值" + i);

            assertThat(Cursor.at(position, true).encode())
                    .as("标准 base64 里的 + 与 / 在查询串里要转义,忘了就是「偶尔翻页失败」")
                    .doesNotContain("+").doesNotContain("/").doesNotContain("=");
        }

        @Test
        @DisplayName("★ 解不开时**报错**,而不是当成第一页")
        void brokenCursorFailsLoudly() {
            assertThatThrownBy(() -> Cursor.decode("这不是游标"))
                    .as("""
                            当成第一页的后果:客户端一直翻页,服务端一直返回第一页,
                            于是那个列表永远滚不动 —— 而且不报错。""")
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Cursor.decode(
                    java.util.Base64.getUrlEncoder().withoutPadding()
                            .encodeToString("v0|id:l:1|next".getBytes())))
                    .as("版本对不上也要报 —— 服务端改过排序列之后旧游标就是解不开的")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("版本");
        }

        @Test
        @DisplayName("不支持的排序列类型:现在就报错,并说清出路")
        void unsupportedTypeFailsEarly() {
            var position = new java.util.LinkedHashMap<String, Object>();
            position.put("weird", new Object());

            assertThatThrownBy(() -> Cursor.at(position, true).encode())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不支持")
                    .hasMessageContaining("paginate");
        }
    }

    // ================= JSON 形状 =================

    @Nested
    @DisplayName("JSON 形状")
    class JsonShape {

        @Test
        @DisplayName("★ 与 Laravel 的 CursorPaginator 同构,而且**没有** total / last_page")
        void matchesLaravel() {
            seed(7, false);
            Paginated.pathResolver(() -> "https://api.example.com/posts");

            Map<String, Object> json = Post.query().orderBy(Post_.views)
                    .cursorPaginate(3).toMap();

            assertThat(json).containsOnlyKeys("data", "path", "per_page",
                    "next_cursor", "next_page_url", "prev_cursor", "prev_page_url");
            assertThat(json)
                    .as("""
                            游标分页**不知道**自己在第几页(那需要数过前面所有行,
                            正是它要避免的事)。输出一个编出来的 current_page
                            会让前端渲染出一堆点不动的页码。""")
                    .doesNotContainKeys("total", "last_page", "current_page", "links", "meta");
            assertThat((String) json.get("next_page_url")).contains("cursor=");
            assertThat(json.get("prev_cursor")).isNull();
        }

        @Test
        @DisplayName("链接里保留其它查询参数")
        void keepsOtherQueryParameters() {
            seed(7, false);
            Paginated.pathResolver(() -> "https://api.example.com/posts");
            Paginated.queryResolver(() -> Map.of("status", "published"));

            String url = (String) Post.query().orderBy(Post_.views)
                    .cursorPaginate(3).toMap().get("next_page_url");

            assertThat(url).contains("status=published").contains("cursor=");
        }

        @Test
        @DisplayName("map 换元素类型,游标不变 —— 与资源结合的接缝")
        void mapKeepsCursors() {
            seed(7, false);

            Cursored<Post> page = Post.query().orderBy(Post_.views).cursorPaginate(3);
            Cursored<String> titles = page.map(p -> p.title);

            assertThat(titles.items()).hasSize(3).first().isEqualTo("文章1");
            assertThat(titles.nextCursor()).isEqualTo(page.nextCursor());
        }
    }

    // ================= resolver =================

    @Test
    @DisplayName("游标从 resolver 取 —— 不在请求里时是第一页")
    void cursorComesFromTheResolver() {
        seed(6, false);

        Cursored<Post> first = Post.query().orderBy(Post_.views).cursorPaginate(3);
        Cursored.cursorResolver(first::nextCursor);

        Cursored<Post> second = Post.query().orderBy(Post_.views).cursorPaginate(3);

        assertThat(second.items().get(0).title).isEqualTo("文章4");
    }

    @Test
    @DisplayName("每页 0 条直接报错")
    void perPageMustBePositive() {
        assertThatThrownBy(() -> Post.query().cursorPaginate(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPage");
    }
}
