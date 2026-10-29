package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.GlobalScope;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.SoftDeletes;
import io.jaravel.eloquent.query.Query;

/**
 * 专门用来测全局作用域和软删除的模型。
 *
 * <p>不把 {@code @GlobalScope} 加在 {@link Post} 上:全局作用域会**默默过滤掉**
 * 不满足条件的行,放在共享夹具上会让每个用到 Post 的测试都必须额外设置字段,
 * 失败时也看不出是作用域干的。这正是全局作用域在真实项目里的风险,
 * 所以隔离到单独模型里测。
 */
@Model(timestamps = false)
@SoftDeletes
public class Draft extends DraftBase {

    @Id public Long id;
    public String title;
    public boolean visible;
    public java.time.Instant deletedAt;

    /** 默认只看 visible = true */
    @GlobalScope("visible")
    public static Query<Draft> visible(Query<Draft> q) {
        return q.where(Draft_.visible, true);
    }
}
