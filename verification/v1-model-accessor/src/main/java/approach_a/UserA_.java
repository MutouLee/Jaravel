package approach_a;

import java.time.Instant;
import java.util.List;
import runtime.Col;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public final class UserA_ {
    private UserA_() {}

    public static final String table = "users";

    public static final Col<UserA, Long>    id        = Col.of("id",         Long.class,    0, UserA::id,        UserA::id);
    public static final Col<UserA, String>  name      = Col.of("name",       String.class,  1, UserA::name,      UserA::name);
    public static final Col<UserA, String>  email     = Col.of("email",      String.class,  2, UserA::email,     UserA::email);
    public static final Col<UserA, String>  status    = Col.of("status",     String.class,  3, UserA::status,    UserA::status);
    public static final Col<UserA, Instant> createdAt = Col.of("created_at", Instant.class, 4, UserA::createdAt, UserA::createdAt);

    public static final List<Col<UserA, ?>> all = List.of(id, name, email, status, createdAt);
}
