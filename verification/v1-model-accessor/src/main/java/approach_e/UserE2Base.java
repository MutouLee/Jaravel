package approach_e;

import java.util.List;
import runtime.Col;
import runtime.Model;
import runtime.Rel;

/**
 * ⚙ 由 APT 生成 —— 不计入用户代码行数。
 *
 * 关联访问器生成在这里:返回类型精确,IDE 可跳转可补全,
 * 且不依赖子类字段(避开了已验证的初始化顺序陷阱)。
 */
public abstract class UserE2Base extends Model<UserE2> {

    @Override protected List<Col<UserE2, ?>> columns() { return UserE2_.all; }
    @Override protected String table() { return "users"; }

    /** 来自 @HasMany(name="posts", target=Post.class) */
    public Rel<Post> posts() { return rel("posts", Post.class); }
}
