package database.factories;

import app.models.Tag;
import io.jaravel.support.Faker;

/**
 * Tag 的工厂(docs/14 §3)。
 *
 * <p>标签与文章是中间表关联({@code post_tag}),而中间表的外键不在模型字段上,
 * 所以生成的基类里**没有** {@code hasPosts(...)} —— 挂关联要走
 * {@code post.tags().attach(...)}(见 {@code BlogSeeder})。
 * 这一条登记在 KNOWN-ISSUES.md。
 */
public class TagFactory extends TagFactoryBase {

    @Override
    protected void definition(Faker f, Tag tag) {
        tag.name = f.word();
        tag.slug = f.slug();
    }
}
