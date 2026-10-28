package approach_e;

import java.time.Instant;
import java.util.List;
import runtime.Col;

/**
 * ⚙ 由 APT 生成 —— 不计入用户代码行数。
 *
 * 注意:Col 的读写直接走字段(u -> u.name),因为字段是 public。
 * 这意味着运行时读写模型属性**既无反射也无方法调用间接层**。
 * 常量命名用小写(ADR-007 决策),与 Laravel 的字符串列名视觉对齐。
 */
public final class UserE_ {
    private UserE_() {}

    public static final String table = "users";

    public static final Col<UserE, Long>    id        = Col.of("id",         Long.class,    0, u -> u.id,        (u, v) -> u.id = v);
    public static final Col<UserE, String>  name      = Col.of("name",       String.class,  1, u -> u.name,      (u, v) -> u.name = v);
    public static final Col<UserE, String>  email     = Col.of("email",      String.class,  2, u -> u.email,     (u, v) -> u.email = v);
    public static final Col<UserE, String>  status    = Col.of("status",     String.class,  3, u -> u.status,    (u, v) -> u.status = v);
    public static final Col<UserE, Instant> createdAt = Col.of("created_at", Instant.class, 4, u -> u.createdAt, (u, v) -> u.createdAt = v);

    public static final List<Col<UserE, ?>> all = List.of(id, name, email, status, createdAt);
}
