package database.seeders;

import app.models.Author;
import app.models.Post;
import app.models.Tag;
import io.jaravel.migration.Seeder;

/**
 * 种子数据。
 *
 * <h2>★ 这个文件是工厂那条 parity 的前后对照</h2>
 * <p>此前它是三段 {@code Factory.of(Post_.META, (f, p) -> ...)} ——
 * 每段都要写出元模型、把字段一个个赋值、再手工把外键接上。
 * 现在默认值住在 {@code database.factories} 的工厂类里,这里只说
 * 「要几条、什么状态、挂在谁下面」。
 *
 * <p>同一套工厂在测试里也用({@code FactoryFeatureTest})—— 这正是
 * 工厂类要放在 main 而不是 test 的理由:**seeder 也是它的使用者**。
 */
public class BlogSeeder implements Seeder {

    @Override
    public void run() {
        var authors = Author.factory().count(3).seed(42).create();
        var tags = Tag.factory().count(5).seed(42).create();

        // seed(42) 让数据随机但可复现 —— 种子不固定的种子数据会让
        // 「本地看到的页面」与「CI 上看到的页面」不是同一份。
        // sequence 把 9 篇轮流分给 3 个作者:一次 create 比三次循环好,
        // 因为 Faker 的唯一序号是**每个工厂实例一份**(见 PostFactory)
        var three = authors.toList();
        Post.factory()
                .count(9)
                .seed(42)
                .sequence(post -> post.authorId = three.get(0).id,
                          post -> post.authorId = three.get(1).id,
                          post -> post.authorId = three.get(2).id)
                .hasComments(3)
                // 中间表也由生成的工厂挂:withTags 用**已有**的标签,
                // 于是九篇文章共用这五个,而不是各造五个新的
                .withTags(tags.toList())
                .create();

        // 一篇草稿,让「只列已发布」那条作用域在页面上看得出效果
        Post.factory().draft()
                .forAuthor(authors.get(0))
                .withTags(tags.get(0))
                .createOne();
    }
}
