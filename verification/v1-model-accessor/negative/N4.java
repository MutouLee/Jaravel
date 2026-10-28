import approach_e.UserE2_;
public class N4 {
    // 负例 4:嵌套路径接错模型 —— posts 的叶子是 Post,不能再接 UserE2 的关联
    static void wrongNesting() { Object x = UserE2_.posts.then(UserE2_.posts); }
}
