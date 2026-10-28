package approach_c;

import java.util.List;
import runtime.Col;
import runtime.Model;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public abstract class UserCBase extends Model<UserC> {
    @Override protected List<Col<UserC, ?>> columns() { return UserC_.all; }
    @Override protected String table() { return "users"; }
}
