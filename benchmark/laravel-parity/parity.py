"""
parity 门禁:同一功能切片的 Jaravel 实现 vs Laravel 实现,统计有效代码行比值。

口径(V1 验证后修正,见 DESIGN.md §9):
  · 必须按**完整功能切片**统计,不能只看某一维度。
    Laravel 的模型文件是 O(1)(完全不声明字段),任何 Java 版本都是 O(n),
    单看模型声明的比值永远达不到 1.3,那会变成一个所有人都忽略的假指标。
  · 统计口径:有效代码行 = 去空行、去纯注释行。
  · 两侧共用同一份 CSS(逐字节相同),因此分别报告「含 CSS」与「不含 CSS」
    两个比值 —— 共享的样板会人为拉低比值,不含 CSS 的那个更诚实。

用法:python benchmark/laravel-parity/parity.py [--max 1.3]
退出码 1 表示超过门禁。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", ".."))

# 行注释 / 块注释 / 模板注释 的起始标记
LINE_COMMENT = ("//", "#", "*", "/*", "*/", "<!--", "{{--", "@*")


def effective_lines(path, skip_css=False):
    """有效代码行数。skip_css 时跳过 <style>...</style> 区块。"""
    n = 0
    in_block = False
    in_css = False
    for raw in open(path, encoding="utf-8").read().split("\n"):
        line = raw.strip()

        if skip_css:
            if "<style>" in line:
                in_css = True
            if in_css:
                if "</style>" in line:
                    in_css = False
                continue

        if not line:
            continue
        if in_block:
            if "*/" in line:
                in_block = False
            continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block = True
            continue
        if line.startswith(LINE_COMMENT):
            continue
        n += 1
    return n


def collect(label, files, skip_css):
    rows = []
    total = 0
    for role, path in files:
        full = os.path.join(REPO, path)
        if not os.path.isfile(full):
            rows.append((role, path, "缺失"))
            continue
        c = effective_lines(full, skip_css)
        total += c
        rows.append((role, os.path.basename(path), c))
    return rows, total


# ---- 功能切片 01:Todo CRUD(列表 / 新增 / 完成 / 删除 / JSON API / 直出页) ----

# 入口与引导文件两边都排除:Jaravel 的 Application.java 与 Laravel 的
# public/index.php + bootstrap/app.php 同属脚手架产物,从不手改,
# 只算一边会让比值失真。
JARAVEL = [
    ("路由",     "examples/todo/src/main/java/routes/WebRoutes.java"),
    ("模型",     "examples/todo/src/main/java/app/models/Todo.java"),
    ("控制器",   "examples/todo/src/main/java/app/http/controllers/TodoController.java"),
    ("迁移",     "examples/todo/src/main/java/database/migrations/m2026_10_29_000000/CreateTodosTable.java"),
    ("布局",     "examples/todo/src/main/resources/views/layouts/app.jblade.html"),
    ("列表页",   "examples/todo/src/main/resources/views/todos/index.jblade.html"),
    ("关于页",   "examples/todo/src/main/resources/views/todos/about.jblade.html"),
]

LARAVEL = [
    ("路由",     "benchmark/laravel-parity/01-crud-todo/laravel/routes/web.php"),
    ("模型",     "benchmark/laravel-parity/01-crud-todo/laravel/app/Models/Todo.php"),
    ("控制器",   "benchmark/laravel-parity/01-crud-todo/laravel/app/Http/Controllers/TodoController.php"),
    ("迁移",     "benchmark/laravel-parity/01-crud-todo/laravel/database/migrations/2026_10_29_000000_create_todos_table.php"),
    ("布局",     "benchmark/laravel-parity/01-crud-todo/laravel/resources/views/layouts/app.blade.php"),
    ("列表页",   "benchmark/laravel-parity/01-crud-todo/laravel/resources/views/todos/index.blade.php"),
    ("关于页",   "benchmark/laravel-parity/01-crud-todo/laravel/resources/views/todos/about.blade.php"),
]


# ---- 功能切片 02:Blog(关联 + 预加载 + 迁移 + FormRequest 校验 + 软删除) ----
#
# 与切片 01 的区别:01 只验证「最小闭环」,02 压的是 v0.2 真正新增的能力。
# 关联与校验是 Laravel 最省代码的两处,所以这个切片对 Jaravel 更不利 ——
# 正因如此它才是 v0.2 的有效标尺。

BLOG_JARAVEL = [
    ("路由",      "examples/blog/src/main/java/routes/WebRoutes.java"),
    ("模型",      "examples/blog/src/main/java/app/models/Post.java"),
    ("模型",      "examples/blog/src/main/java/app/models/Author.java"),
    ("模型",      "examples/blog/src/main/java/app/models/Comment.java"),
    ("模型",      "examples/blog/src/main/java/app/models/Tag.java"),
    ("表单请求",  "examples/blog/src/main/java/app/http/requests/StorePostRequest.java"),
    ("表单请求",  "examples/blog/src/main/java/app/http/requests/StoreCommentRequest.java"),
    ("控制器",    "examples/blog/src/main/java/app/http/controllers/PostController.java"),
    ("控制器",    "examples/blog/src/main/java/app/http/controllers/CommentController.java"),
    ("迁移",      "examples/blog/src/main/java/database/migrations/m2026_10_29_000100/CreateBlogTables.java"),
    ("迁移",      "examples/blog/src/main/java/database/migrations/m2026_10_29_000200/CreateTagTables.java"),
    ("种子",      "examples/blog/src/main/java/database/seeders/BlogSeeder.java"),
    ("布局",      "examples/blog/src/main/resources/views/layouts/app.jblade.html"),
    ("列表页",    "examples/blog/src/main/resources/views/posts/index.jblade.html"),
    ("详情页",    "examples/blog/src/main/resources/views/posts/show.jblade.html"),
    ("表单页",    "examples/blog/src/main/resources/views/posts/form.jblade.html"),
]

L2 = "benchmark/laravel-parity/02-crud-blog/laravel/"
BLOG_LARAVEL = [
    ("路由",      L2 + "routes/web.php"),
    ("模型",      L2 + "app/Models/Post.php"),
    ("模型",      L2 + "app/Models/Author.php"),
    ("模型",      L2 + "app/Models/Comment.php"),
    ("模型",      L2 + "app/Models/Tag.php"),
    ("表单请求",  L2 + "app/Http/Requests/StorePostRequest.php"),
    ("表单请求",  L2 + "app/Http/Requests/StoreCommentRequest.php"),
    ("控制器",    L2 + "app/Http/Controllers/PostController.php"),
    ("控制器",    L2 + "app/Http/Controllers/CommentController.php"),
    ("迁移",      L2 + "database/migrations/2026_10_29_000100_create_blog_tables.php"),
    ("迁移",      L2 + "database/migrations/2026_10_29_000200_create_tag_tables.php"),
    ("种子",      L2 + "database/seeders/BlogSeeder.php"),
    ("布局",      L2 + "resources/views/layouts/app.blade.php"),
    ("列表页",    L2 + "resources/views/posts/index.blade.php"),
    ("详情页",    L2 + "resources/views/posts/show.blade.php"),
    ("表单页",    L2 + "resources/views/posts/form.blade.php"),
]

# ---- 功能切片 03:认证(注册/登录/记住我/登出/密码重置/邮箱验证/Policy/API token) ----
#
# 这个切片**对 Jaravel 最不利**,因为认证是 Laravel 最省代码的地方:
# 「加个登录 = 3 行」这句话在 Laravel 侧是字面意义上的真话。
# 正因如此它才是 v0.3 的有效标尺 —— 挑一个 Jaravel 占优的切片去量
# 只会得到一个自我安慰的数字。
#
# 两条口径上的决定,都刻意选了**不利于 Jaravel** 的那一侧:
#
# 1. Jaravel 的 config/auth.yml 计入,Laravel 的 config/auth.php 不计入。
#    理由:Laravel 的 auth.php 随框架发布、且已经填好了默认的 User 模型,
#    这个应用一行都不用改;而 Jaravel 的 auth.yml 里 guards / providers
#    是真要写的。这是 Laravel 的一个真实优势,排除它等于把它藏起来。
#    (session / cache / hashing 的配置两侧都不计 —— Jaravel 侧那三个
#     文件在本切片里也没有认证相关的必填项。)
#
# 2. 两侧都不计入脚手架:Jaravel 的 Application.java、
#    Laravel 的 public/index.php + bootstrap/app.php + Controller.php 基类。
#
# 本切片没有视图:examples/auth 返回 JSON,Laravel 侧照样返回 JSON。
# 于是比的是纯控制流 —— Java 的类型声明与 import 在这里完全没有遮掩,
# 又是一处对 Jaravel 不利但更诚实的安排。

AUTH_JARAVEL = [
    ("路由",      "examples/auth/src/main/java/routes/WebRoutes.java"),
    ("认证配置",  "examples/auth/src/main/resources/config/auth.yml"),
    ("模型",      "examples/auth/src/main/java/app/models/User.java"),
    ("模型",      "examples/auth/src/main/java/app/models/Note.java"),
    ("表单请求",  "examples/auth/src/main/java/app/http/requests/RegisterRequest.java"),
    ("表单请求",  "examples/auth/src/main/java/app/http/requests/LoginRequest.java"),
    ("表单请求",  "examples/auth/src/main/java/app/http/requests/ResetPasswordRequest.java"),
    ("Policy",    "examples/auth/src/main/java/app/policies/NotePolicy.java"),
    ("控制器",    "examples/auth/src/main/java/app/http/controllers/AuthController.java"),
    ("控制器",    "examples/auth/src/main/java/app/http/controllers/DashboardController.java"),
    ("控制器",    "examples/auth/src/main/java/app/http/controllers/NoteController.java"),
    ("迁移",      "examples/auth/src/main/java/database/migrations/m2026_10_29_100100/CreateAuthTables.java"),
]

L3 = "benchmark/laravel-parity/03-auth/laravel/"
AUTH_LARAVEL = [
    ("路由",      L3 + "routes/web.php"),
    ("模型",      L3 + "app/Models/User.php"),
    ("模型",      L3 + "app/Models/Note.php"),
    ("表单请求",  L3 + "app/Http/Requests/RegisterRequest.php"),
    ("表单请求",  L3 + "app/Http/Requests/LoginRequest.php"),
    ("表单请求",  L3 + "app/Http/Requests/ResetPasswordRequest.php"),
    ("Policy",    L3 + "app/Policies/NotePolicy.php"),
    ("控制器",    L3 + "app/Http/Controllers/AuthController.php"),
    ("控制器",    L3 + "app/Http/Controllers/DashboardController.php"),
    ("控制器",    L3 + "app/Http/Controllers/NoteController.php"),
    ("迁移",      L3 + "database/migrations/2026_10_29_100100_create_auth_tables.php"),
]


SLICES = [
    ("01:Todo CRUD", JARAVEL, LARAVEL),
    ("02:Blog(关联/校验/迁移)", BLOG_JARAVEL, BLOG_LARAVEL),
    ("03:认证(guard/Policy/重置/验证)", AUTH_JARAVEL, AUTH_LARAVEL),
]


def report(title, jaravel_files, laravel_files, skip_css):
    out = []
    tag = "不含共享 CSS" if skip_css else "含共享 CSS"
    out.append("")
    out.append("─" * 62)
    out.append(" 功能切片 %s    [%s]" % (title, tag))
    out.append("─" * 62)

    jr, jt = collect("Jaravel", jaravel_files, skip_css)
    lr, lt = collect("Laravel", laravel_files, skip_css)

    out.append("")
    out.append("  Jaravel")
    for role, name, c in jr:
        out.append("    %-10s %-34s %5s" % (role, name, c))
    out.append("    %-10s %-34s %5d" % ("", "合计", jt))

    out.append("")
    out.append("  Laravel")
    for role, name, c in lr:
        out.append("    %-10s %-34s %5s" % (role, name, c))
    out.append("    %-10s %-34s %5d" % ("", "合计", lt))

    ratio = jt / lt if lt else float("inf")
    out.append("")
    out.append("  比值  Jaravel / Laravel = %d / %d = %.2f" % (jt, lt, ratio))
    return out, ratio


def main():
    max_ratio = 1.3
    if "--max" in sys.argv:
        max_ratio = float(sys.argv[sys.argv.index("--max") + 1])

    lines = ["=" * 62, " parity 门禁:Jaravel vs 等价 Laravel 实现", "=" * 62]
    summary = []
    worst = 0.0

    for title, jf, lf in SLICES:
        out, ratio_with = report(title, jf, lf, False)
        lines += out
        out, ratio_without = report(title, jf, lf, True)
        lines += out
        summary.append((title, ratio_with, ratio_without))
        worst = max(worst, ratio_without)

    lines.append("")
    lines.append("=" * 62)
    lines.append(" 门禁上限: %.2f    以「不含 CSS」为准" % max_ratio)
    lines.append("-" * 62)
    # 三位小数而不是两位。
    #
    # 两位的时候 518/398 = 1.3015 会打印成「1.30 超标」—— 一个
    # 看起来自相矛盾的结论(1.30 不大于门禁的 1.30)。
    # 判定用的是原始值,而显示把它藏了起来,于是读者会以为脚本坏了。
    #
    # 这是实打实踩到的:v0.4 往切片 03 的被测文件里多加了三条路由,
    # 比值从 1.294 变成 1.3015,而输出两次都显示「1.30」。
    for title, rw, rwo in summary:
        lines.append(" %-30s 含 CSS %.3f   不含 CSS %.3f   %s"
                     % (title, rw, rwo, "通过" if rwo <= max_ratio else "超标"))
    lines.append("-" * 62)
    lines.append(" 最差切片: %.3f   %s" % (worst, "通过" if worst <= max_ratio else "超标"))
    lines.append("=" * 62)

    text = "\n".join(lines) + "\n"
    open(os.path.join(ROOT, "parity-result.txt"), "w", encoding="utf-8").write(text)
    for title, rw, rwo in summary:
        print("slice=%s with_css=%.2f without_css=%.2f" % (title, rw, rwo))
    print("worst=%.2f gate=%.2f" % (worst, max_ratio))

    return 0 if worst <= max_ratio else 1


if __name__ == "__main__":
    sys.exit(main())
