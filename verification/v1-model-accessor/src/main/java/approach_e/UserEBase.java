package approach_e;

import java.util.List;
import runtime.Col;
import runtime.Model;

/** ⚙ 由 APT 生成 —— 不计入用户代码行数。 */
public abstract class UserEBase extends Model<UserE> {
    @Override protected List<Col<UserE, ?>> columns() { return UserE_.all; }
    @Override protected String table() { return "users"; }
}
