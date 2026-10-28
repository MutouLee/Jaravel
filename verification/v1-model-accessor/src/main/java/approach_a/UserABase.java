package approach_a;

import java.util.List;
import runtime.Col;
import runtime.Model;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public abstract class UserABase extends Model<UserA> {
    @Override protected List<Col<UserA, ?>> columns() { return UserA_.all; }
    @Override protected String table() { return "users"; }
}
