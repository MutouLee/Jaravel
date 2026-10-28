package approach_c;

import java.time.Instant;
import java.util.List;
import runtime.Col;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public final class UserC_ {
    private UserC_() {}

    public static final String table = "users";

    public static final Col<UserC, Long>    id        = Col.of("id",         Long.class,    0, UserC::getId,        UserC::setId);
    public static final Col<UserC, String>  name      = Col.of("name",       String.class,  1, UserC::getName,      UserC::setName);
    public static final Col<UserC, String>  email     = Col.of("email",      String.class,  2, UserC::getEmail,     UserC::setEmail);
    public static final Col<UserC, String>  status    = Col.of("status",     String.class,  3, UserC::getStatus,    UserC::setStatus);
    public static final Col<UserC, Instant> createdAt = Col.of("created_at", Instant.class, 4, UserC::getCreatedAt, UserC::setCreatedAt);

    public static final List<Col<UserC, ?>> all = List.of(id, name, email, status, createdAt);
}
