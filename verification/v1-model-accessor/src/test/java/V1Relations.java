import java.util.List;

import approach_e.Post;
import approach_e.UserE2;
import approach_e.UserE2_;

/**
 * V1 补充验证:方案 E' 的关联形态。
 *
 * 验证:
 *   1. 关联访问器生成在父类 → 不受「子类字段初始化顺序」陷阱影响
 *   2. 未预加载时报错清晰(N+1 第二道防线)
 *   3. 预加载回填后可读
 *   4. RelPath 的嵌套路径类型安全
 */
public class V1Relations {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println(" V1 补充 · 方案 E' 关联形态验证");
        System.out.println("=".repeat(78));
        System.out.println();

        // 1. 显式初始化器不再是问题 —— 关联不是字段
        UserE2 u = new UserE2();
        u.id = 1L;
        u.name = "Tom";
        check("关联句柄在未预加载时也能取到(不为 null)",
              u.posts() != null, "posts() = " + u.posts());

        // 2. 未预加载访问 → 清晰报错
        try {
            u.posts().get();
            check("未预加载应抛异常", false, "没抛异常");
        } catch (IllegalStateException e) {
            check("未预加载访问时报错清晰", e.getMessage().contains(".with("), e.getMessage());
        }

        // 3. 预加载回填
        u.fillRelation("posts", Post.class,
                List.of(new Post(10L, "第一篇"), new Post(11L, "第二篇")));
        check("预加载回填后可读", u.posts().get().size() == 2, "posts = " + u.posts().get());
        check("同一关联句柄被缓存(多次调用返回同一实例)",
              u.posts() == u.posts(), "identity ok");

        // 4. RelPath 路径
        check("RelPath 单段路径", UserE2_.posts.path().equals("posts"), UserE2_.posts.path());

        System.out.println();
        System.out.println("  关联的使用点对比:");
        System.out.println("""
            Laravel : $user->posts            (属性,带魔术方法)
            Jaravel : user.posts()            (生成的方法,多两个括号)
                      user.posts().get()      (取集合)
                      user.posts().query()... (继续加条件)
        """);

        System.out.println("-".repeat(78));
        System.out.printf(" 结果:%d 通过,%d 失败%n", pass, fail);
        System.out.println("-".repeat(78));
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else    { fail++; System.out.println("  ✗ " + name); }
        System.out.println("      " + detail);
    }
}
