package approach_e;

import java.time.Instant;

/**
 * 方案 E:public 字段 + 快照式脏检查。
 *
 * ★ 以下全部由用户手写 —— parity 计数对象。
 *
 * 对照 Laravel:
 *     class User extends Model { protected $table = 'users'; }   // 字段完全不声明
 * Jaravel 必须声明字段(静态类型语言的必然成本),但**仅此而已**:
 * 无 getter、无 setter、无元模型、无构造函数。
 *
 * 读写:user.name  /  user.name = "Tom"   ← 与 PHP 的 $user->name 等长
 *
 * 优点:① 信息密度最高,一字段一行;
 *       ② 零访问器代码生成;
 *       ③ IDE 完美支持(字段是真实的,跳转/重命名/补全全部原生可用);
 *       ④ 无字节码织入、无 javac 内部 API、无 IDE 插件依赖;
 *       ⑤ `new UserE()` 正常可用,类就是用户写的那个类。
 * 缺点:① public 可变字段违反 Java 封装正统,部分团队会抵触;
 *       ② 写入无法拦截 → 必须靠快照比对,每个已加载实例多一个 Object[];
 *       ③ 无法在 setter 里做修改器(@Mutator)—— 需在 save 前钩子里处理。
 */
public class UserE extends UserEBase {

    public Long id;
    public String name;
    public String email;
    public String status;
    public Instant createdAt;
}
