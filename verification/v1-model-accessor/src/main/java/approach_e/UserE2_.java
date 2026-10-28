package approach_e;

import java.time.Instant;
import java.util.List;
import runtime.Col;
import runtime.RelPath;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public final class UserE2_ {
    private UserE2_() {}

    public static final String table = "users";

    public static final Col<UserE2, Long>    id        = Col.of("id",         Long.class,    0, u -> u.id,        (u, v) -> u.id = v);
    public static final Col<UserE2, String>  name      = Col.of("name",       String.class,  1, u -> u.name,      (u, v) -> u.name = v);
    public static final Col<UserE2, String>  email     = Col.of("email",      String.class,  2, u -> u.email,     (u, v) -> u.email = v);
    public static final Col<UserE2, String>  status    = Col.of("status",     String.class,  3, u -> u.status,    (u, v) -> u.status = v);
    public static final Col<UserE2, Instant> createdAt = Col.of("created_at", Instant.class, 4, u -> u.createdAt, (u, v) -> u.createdAt = v);

    public static final List<Col<UserE2, ?>> all = List.of(id, name, email, status, createdAt);

    /** 关联路径:用于 .with(UserE2_.posts) 以及嵌套 .with(UserE2_.posts.then(Post_.comments)) */
    public static final RelPath<UserE2, Post> posts = RelPath.of("posts", Post.class);
}
