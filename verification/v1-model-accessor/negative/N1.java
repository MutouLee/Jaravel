import approach_c.UserC; import approach_c.UserC_;
import approach_e.UserE; import approach_e.UserE_;
import runtime.Col;
public class N1 {
    // 负例 1:把 UserC 的列用在 UserE 实例上
    static void crossModel(UserE u) { Col<UserE,?> c = UserC_.name; }
}
