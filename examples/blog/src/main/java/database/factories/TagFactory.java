package database.factories;

import app.models.Tag;
import io.jaravel.support.Faker;

/**
 * Tag 的工厂(docs/14 §3)。
 *
 * <p>标签与文章是中间表关联({@code post_tag})。中间表的外键不在模型字段上,
 * 所以生成的构建器走的是关联访问器:{@code TagFactoryBase} 这边是
 * {@code hasPosts(n)} / {@code withPosts(…)},{@code PostFactoryBase} 那边是
 * {@code hasTags(n)} / {@code withTags(…)} —— 两边都生成,因为中间表是对称的。
 */
public class TagFactory extends TagFactoryBase {

    /** {@code tags.slug} 上有 UNIQUE 索引 —— 理由见 {@link PostFactory} 里那段注释 */
    private static final java.util.concurrent.atomic.AtomicLong SLUGS =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    protected void definition(Faker f, Tag tag) {
        tag.name = f.word();
        tag.slug = "tag-" + SLUGS.incrementAndGet();
    }
}
