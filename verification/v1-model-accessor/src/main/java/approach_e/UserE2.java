package approach_e;

import java.time.Instant;

/**
 * 方案 E'(混合形态,V1 验证后的修正版):
 *   · 标量列 → public 字段(一行一列,直接读写)
 *   · 关联   → 类级注解声明,APT 生成访问器方法,状态存 Model 基类
 *
 * ★ 以下全部由用户手写 —— parity 计数对象。
 *
 * 注解在类级而非字段级,是因为关联不作为字段存在(V1 已验证两条原因:
 * public 字段无法拦截懒加载;父类构造器初始化子类字段会被显式初始化器静默覆盖)。
 */
// @Model(table = "users")
// @HasMany(name = "posts", target = Post.class)
// @BelongsTo(name = "team", target = Team.class)
public class UserE2 extends UserE2Base {

    public Long id;
    public String name;
    public String email;
    public String status;
    public Instant createdAt;
}
