package apptest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import app.models.Author;
import app.models.Comment;
import app.models.Post;
import app.models.Tag;
import net.dobeen.jaravel.testing.AppTest;

/**
 * 生成式模型工厂的验收(docs/14 §3)。
 *
 * <p>钉住的是**生成出来的那一面**:类型化 setter、自类型链式方法、
 * 关联构建器、序列与种子。它们都是 APT 的产物,而生成器的单元测试
 * (比较输出字符串)证明不了「生成的代码真的跑得对」——
 * 那个差距正是这个文件要补的。
 *
 * <p>用 {@link AppTest} 而不是 FeatureTest:造数据不需要 HTTP,
 * 一次失败的原因就只剩工厂本身。
 */
class FactoryFeatureTest extends AppTest {

    private Author author() {
        return Author.factory().createOne();
    }

    @Nested
    @DisplayName("默认值与状态")
    class Defaults {

        @Test
        @DisplayName("definition 里的默认值生效")
        void definitionApplies() {
            Post post = Post.factory().forAuthor(author()).createOne();

            assertThat(post.title).as("默认值来自 PostFactory.definition").isNotBlank();
            assertThat(post.published).as("默认是已发布").isTrue();
            assertThat(post.id).as("入库了,主键回填").isNotNull();
        }

        @Test
        @DisplayName("具名状态压过默认值 —— draft() 把 published 翻过来")
        void namedStateOverridesDefaults() {
            Post post = Post.factory().draft().forAuthor(author()).createOne();

            assertThat(post.published).isFalse();
        }

        @Test
        @DisplayName("★ 类型化 setter:.title(\"X\") 而不是 Map.of(\"title\", \"X\")")
        void typedSettersApply() {
            Post post = Post.factory()
                    .title("指定标题")
                    .views(777)
                    .forAuthor(author())
                    .createOne();

            assertThat(post.title).isEqualTo("指定标题");
            assertThat(post.views).isEqualTo(777);
            assertDatabaseHas("posts", java.util.Map.of("title", "指定标题"));
        }

        @Test
        @DisplayName("状态与 setter 的叠加顺序:后写的赢")
        void laterStatesWin() {
            Post post = Post.factory()
                    .popular()
                    .views(1)
                    .forAuthor(author())
                    .createOne();

            assertThat(post.views).as("views(1) 写在 popular() 之后").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("数量与产出")
    class Counts {

        @Test
        @DisplayName("count(n).create() 造 n 条并入库")
        void createsMany() {
            var posts = Post.factory().count(3).forAuthor(author()).create();

            assertThat(posts.size()).isEqualTo(3);
            assertThat(posts.toList()).allSatisfy(p -> assertThat(p.id).isNotNull());
        }

        @Test
        @DisplayName("make() 不入库 —— 主键还是 null")
        void makeDoesNotPersist() {
            long before = Post.query().count();

            Post post = Post.factory().makeOne();

            assertThat(post.id).isNull();
            assertThat(post.title).as("默认值照样填").isNotBlank();
            assertThat(Post.query().count()).isEqualTo(before);
        }

        @Test
        @DisplayName("★ count 不是 1 时 createOne() 报错,而不是静默只给第一条")
        void createOneRefusesManyLoudly() {
            assertThatThrownBy(() -> Post.factory().count(3).forAuthor(author()).createOne())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("count = 3")
                    .hasMessageContaining("create()");
        }
    }

    @Nested
    @DisplayName("序列与种子")
    class Randomness {

        @Test
        @DisplayName("sequence 按顺序轮转 —— 两草稿一发布")
        void sequenceRotates() {
            var posts = Post.factory()
                    .count(4)
                    .forAuthor(author())
                    .sequence(p -> p.published = false, p -> p.published = true)
                    .create()
                    .toList();

            assertThat(posts.stream().map(p -> p.published).toList())
                    .containsExactly(false, true, false, true);
        }

        @Test
        @DisplayName("★ 同一个种子造出同一批数据 —— 随机失败必须可复现")
        void seedIsReproducible() {
            var first = Post.factory().count(3).seed(1234).make().toList();
            var second = Post.factory().count(3).seed(1234).make().toList();

            assertThat(second.stream().map(p -> p.title).toList())
                    .isEqualTo(first.stream().map(p -> p.title).toList());
        }

        @Test
        @DisplayName("不同种子造出不同数据(否则上一条是假的)")
        void differentSeedsDiffer() {
            var a = Post.factory().count(3).seed(1).make().toList();
            var b = Post.factory().count(3).seed(2).make().toList();

            assertThat(b.stream().map(p -> p.title).toList())
                    .isNotEqualTo(a.stream().map(p -> p.title).toList());
        }
    }

    @Nested
    @DisplayName("关联构建器")
    class Relations {

        @Test
        @DisplayName("forAuthor(author) 把外键指过去")
        void belongsToExistingParent() {
            Author author = author();

            Post post = Post.factory().forAuthor(author).createOne();

            assertThat(post.authorId).isEqualTo(author.id);
        }

        @Test
        @DisplayName("★ forAuthor(Author.factory()) 只建一个父对象,count 条共用")
        void belongsToFactoryCreatesOneParent() {
            long authorsBefore = Author.query().count();

            var posts = Post.factory().count(3).forAuthor(Author.factory()).create().toList();

            assertThat(Author.query().count())
                    .as("三篇文章共用一个作者,而不是各建一个")
                    .isEqualTo(authorsBefore + 1);
            assertThat(posts.stream().map(p -> p.authorId).distinct().toList()).hasSize(1);
        }

        @Test
        @DisplayName("hasComments(3) 在父对象入库之后建子对象,外键已填")
        void hasManyCreatesChildren() {
            Post post = Post.factory().forAuthor(author()).hasComments(3).createOne();

            assertThat(Comment.query().wherePostId(post.id).count()).isEqualTo(3);

            // 走预加载读回来 —— 直接 post.comments().get() 会撞上
            // preventLazyLoading(这个示例开着它),而那条防线是对的
            Post reloaded = Post.query().with(app.models.Post_.comments)
                    .findOrFail(post.id);
            assertThat(reloaded.comments().get().size()).isEqualTo(3);
        }

        @Test
        @DisplayName("hasComments(n, tweak) 能调子工厂的状态")
        void hasManyAcceptsChildTweak() {
            Post post = Post.factory()
                    .forAuthor(author())
                    .hasComments(2, f -> f.pending())
                    .createOne();

            assertThat(Comment.query().wherePostId(post.id).whereApproved(false).count())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("★ hasTags:中间表也由生成的工厂挂")
        void belongsToManyCreatesAndAttaches() {
            Post post = Post.factory().forAuthor(author()).hasTags(2).createOne();

            Post reloaded = Post.query().with(app.models.Post_.tags).findOrFail(post.id);
            assertThat(reloaded.tags().get().size())
                    .as("多对多没有「子对象的外键」,挂接走 owner.tags().attach(...)")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("hasTags(n, tweak) 能调子工厂")
        void belongsToManyAcceptsChildTweak() {
            Post post = Post.factory()
                    .forAuthor(author())
                    .hasTags(1, f -> f.name("指定标签"))
                    .createOne();

            Post reloaded = Post.query().with(app.models.Post_.tags).findOrFail(post.id);
            assertThat(reloaded.tags().get().toList())
                    .extracting(tag -> tag.name)
                    .containsExactly("指定标签");
        }

        @Test
        @DisplayName("★ withTags 挂**已有**的 —— 两篇文章共用同一批标签")
        void belongsToManyAttachesExisting() {
            var shared = Tag.factory().count(2).create().toList();
            long tagsBefore = Tag.query().count();

            var posts = Post.factory().count(2)
                    .forAuthor(author())
                    .withTags(shared.get(0), shared.get(1))
                    .create().toList();

            assertThat(Tag.query().count())
                    .as("共用而不是各造一批 —— 否则「两篇文章同一个标签」这件事造不出来")
                    .isEqualTo(tagsBefore);
            for (Post post : posts) {
                Post reloaded = Post.query().with(app.models.Post_.tags).findOrFail(post.id);
                assertThat(reloaded.tags().get().size()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("hasPosts 在父侧也有 —— 同一套生成规则的另一边")
        void hasManyFromTheParentSide() {
            Author author = Author.factory().hasPosts(2).createOne();

            assertThat(Post.query().whereAuthorId(author.id).count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("afterCreating 钩子拿得到主键")
    void afterCreatingSeesTheKey() {
        var seen = new java.util.ArrayList<Long>();

        Post.factory()
                .count(2)
                .forAuthor(author())
                .afterCreating((post, index) -> seen.add(post.id))
                .create();

        assertThat(seen).hasSize(2).allSatisfy(id -> assertThat(id).isNotNull());
    }

    @Test
    @DisplayName("种子数据本身就是工厂的使用者 —— 启动时 seed 过的库里有文章")
    void theSeederUsesTheSameFactories() {
        assertThat(Post.query().count()).as("SEED_ON_BOOT=true,BlogSeeder 跑过").isPositive();
        assertThat(Comment.query().count()).as("hasComments(3) 生效").isPositive();
    }
}
