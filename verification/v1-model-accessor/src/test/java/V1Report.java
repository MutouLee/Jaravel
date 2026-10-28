import java.time.Instant;

import approach_a.UserA;
import approach_a.UserA_;
import approach_c.UserC;
import approach_c.UserC_;
import approach_e.UserE;
import approach_e.UserE_;

/**
 * V1 验证:三种模型访问器方案的功能等价性与使用体感对比。
 *
 * 验证四件事:
 *   1. 三种方案的脏检查都正确(INSERT 只含已赋值列,UPDATE 只含变化列)
 *   2. 方案 E 的「快照比对」能否替代「setter 拦截」
 *   3. 类型安全是否成立(跨模型误用列应编译失败)
 *   4. 使用点代码的长度与可读性
 */
public class V1Report {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println(" V1 验证 · 模型访问器方案(ADR-010)");
        System.out.println("=".repeat(78));

        testC();
        testA();
        testE();
        testSnapshotOnlyDirty();
        showUsageSites();

        System.out.println();
        System.out.println("-".repeat(78));
        System.out.printf(" 结果:%d 通过,%d 失败%n", pass, fail);
        System.out.println("-".repeat(78));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 方案 C:手写 getter/setter ----------------

    static void testC() {
        section("方案 C · 手写 getter/setter");

        UserC u = new UserC();
        u.setName("Tom");
        u.setEmail("tom@example.com");
        String insert = u.save();
        check("C-新建仅含已赋值列",
              insert.contains("`name`") && insert.contains("`email`")
              && !insert.contains("`status`"), insert);

        // 模拟从数据库加载
        UserC loaded = new UserC();
        loaded.setId(1L);
        loaded.setName("Tom");
        loaded.setEmail("tom@example.com");
        loaded.setStatus("active");
        loaded.hydrated();

        loaded.setName("Jerry");
        String update = loaded.save();
        check("C-更新仅含变化列",
              update.startsWith("update") && update.contains("`name` = ?")
              && !update.contains("`email`"), update);

        String noop = loaded.save();
        check("C-无变化时跳过 UPDATE", noop.contains("无变化"), noop);
    }

    // ---------------- 方案 A:AutoValue 风格 ----------------

    static void testA() {
        section("方案 A · 抽象声明 + APT 生成具体子类");

        UserA u = UserA.create();           // ← 不能 new UserA()
        u.name("Tom");
        u.email("tom@example.com");
        String insert = u.save();
        check("A-新建仅含已赋值列",
              insert.contains("`name`") && insert.contains("`email`")
              && !insert.contains("`status`"), insert);

        UserA loaded = UserA.create();
        loaded.id(1L);
        loaded.name("Tom");
        loaded.status("active");
        loaded.hydrated();

        loaded.name("Jerry");
        String update = loaded.save();
        check("A-更新仅含变化列",
              update.contains("`name` = ?") && !update.contains("`status`"), update);

        check("A-实际运行类暴露 _Impl(认知成本)",
              loaded.getClass().getSimpleName().equals("UserA_Impl"),
              "getClass() = " + loaded.getClass().getSimpleName());
    }

    // ---------------- 方案 E:public 字段 + 快照 ----------------

    static void testE() {
        section("方案 E · public 字段直接赋值 + 快照脏检查");

        UserE u = new UserE();
        u.name = "Tom";                      // ← 直接字段赋值
        u.email = "tom@example.com";
        String insert = u.save();
        check("E-新建仅含已赋值列",
              insert.contains("`name`") && insert.contains("`email`")
              && !insert.contains("`status`"), insert);

        UserE loaded = new UserE();
        loaded.id = 1L;
        loaded.name = "Tom";
        loaded.email = "tom@example.com";
        loaded.status = "active";
        loaded.createdAt = Instant.parse("2026-10-28T00:00:00Z");
        loaded.hydrated();

        loaded.name = "Jerry";
        String update = loaded.save();
        check("E-更新仅含变化列(无 setter 拦截,纯快照比对)",
              update.startsWith("update") && update.contains("`name` = ?")
              && !update.contains("`email`") && !update.contains("`status`"), update);

        String noop = loaded.save();
        check("E-无变化时跳过 UPDATE", noop.contains("无变化"), noop);

        check("E-运行类就是用户写的类",
              loaded.getClass().getSimpleName().equals("UserE"),
              "getClass() = " + loaded.getClass().getSimpleName());
    }

    // ---------------- 关键验证:快照能否完全替代 setter 拦截 ----------------

    static void testSnapshotOnlyDirty() {
        section("关键验证 · 快照比对的边界情况");

        // 1. 赋回原值:应被识别为「未变化」
        UserE u = new UserE();
        u.id = 1L; u.name = "Tom"; u.hydrated();
        u.name = "Jerry";
        u.name = "Tom";                      // 改回去
        check("赋回原值 → 不产生 UPDATE", !u.isDirty(),
              "changes = " + u.changes());

        // 2. 置 null:应被识别为「变化」
        UserE u2 = new UserE();
        u2.id = 1L; u2.name = "Tom"; u2.email = "a@b.com"; u2.hydrated();
        u2.email = null;
        check("非 null → null 能被检测到", u2.changes().containsKey("email"),
              "changes = " + u2.changes());

        // 3. save 后重新拍快照
        UserE u3 = new UserE();
        u3.id = 1L; u3.name = "Tom"; u3.hydrated();
        u3.name = "Jerry";
        u3.save();
        check("save 后快照刷新,再次 save 无变化", !u3.isDirty(),
              "changes = " + u3.changes());

        // 4. 可变对象的别名陷阱 —— 快照方案的已知缺陷
        //    若字段是 List/Map 等可变对象,快照持有的是同一个引用,
        //    原地修改(list.add)无法被检测到。
        System.out.println();
        System.out.println("  ⚠ 已知缺陷:可变对象字段(List/Map/byte[])原地修改无法被快照检测。");
        System.out.println("     例:user.tags.add(\"x\") 不会产生 UPDATE。");
        System.out.println("     对策:@Cast 类型的字段在拍快照时做深拷贝或存序列化后的形态。");
        System.out.println("     注意:Hibernate 的脏检查有完全相同的问题,这不是方案 E 特有的。");
    }

    // ---------------- 使用点体感对比 ----------------

    static void showUsageSites() {
        section("使用点代码对比(同一业务逻辑)");

        System.out.println("""
          Laravel (PHP) ─────────────────────────────────────── 基准
            $user = User::find(1);
            $user->name = 'Jerry';
            $user->email = 'j@example.com';
            $user->save();

          方案 C ──────────────────────────── 手写 getter/setter
            UserC user = UserC.find(1L);
            user.setName("Jerry");
            user.setEmail("j@example.com");
            user.save();

          方案 A ─────────────────────── 抽象声明 + 生成子类
            UserA user = UserA.find(1L);
            user.name("Jerry");
            user.email("j@example.com");
            user.save();

          方案 E ───────────────── public 字段(与 PHP 等长)
            UserE user = UserE.find(1L);
            user.name = "Jerry";
            user.email = "j@example.com";
            user.save();
        """);
    }

    // ---------------- 工具 ----------------

    static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 70 - title.length())));
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else    { fail++; System.out.println("  ✗ " + name); }
        System.out.println("      " + detail);
    }
}
