package database.factories;

import app.models.Post;
import net.dobeen.jaravel.support.Faker;

/**
 * Post 的工厂(docs/14 §3)。
 *
 * <pre>{@code
 * Post.factory().createOne();                          // 一篇已发布
 * Post.factory().draft().count(3).create();            // 三篇草稿
 * Post.factory().forAuthor(author).createOne();        // 指定作者
 * Post.factory().forAuthor(Author.factory()).create(); // 顺手建一个作者
 * }</pre>
 *
 * <h2>★ 默认值里为什么没有 author_id</h2>
 * <p>{@code posts.author_id} 是 NOT NULL 外键,但「造一篇文章」并不隐含
 * 「顺便造一个作者」—— 那会让一条断言「作者表里有几个人」的测试莫名多出一行。
 *
 * <p>所以作者必须显式给:{@code forAuthor(author)} 用现成的,
 * {@code forAuthor(Author.factory())} 现建一个。漏了的后果是数据库的
 * NOT NULL 报错 —— 指名道姓说哪一列,比框架替你猜一个作者好。
 */
public class PostFactory extends PostFactoryBase {

    /**
     * {@code posts.slug} 上有 UNIQUE 索引,所以默认值必须**跨工厂实例**唯一。
     *
     * <h2>★ 为什么不能只用 f.slug()</h2>
     * <p>Faker 的序号是**每个工厂实例一份**(它要让 {@code seed(42)} 可复现,
     * 那就不能有跨实例的共享状态)。于是同一个测试里两次
     * {@code Post.factory()} 都会从 {@code slug-1} 开始 ——
     * 第二次撞 UNIQUE,而报错是 SQLite 的约束冲突,与「工厂」三个字毫无关系。
     *
     * <p>这是任何带唯一列的模型都要处理的一件事,所以示例里把它写出来:
     * 唯一列的默认值自己带一个进程级计数器。
     */
    private static final java.util.concurrent.atomic.AtomicLong SLUGS =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    protected void definition(Faker f, Post post) {
        post.title = f.sentence();
        post.slug = f.slug() + "-" + SLUGS.incrementAndGet();
        post.body = f.paragraph();
        post.published = true;
        post.views = f.number(0, 500);
    }

    public PostFactory draft() {
        return state(p -> p.published = false);
    }

    public PostFactory popular() {
        return state(p -> p.views = 10_000);
    }
}
