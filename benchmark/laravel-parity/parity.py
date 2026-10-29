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
    ("数据访问", "examples/todo/src/main/java/app/models/TodoRepository.java"),
    ("控制器",   "examples/todo/src/main/java/app/http/controllers/TodoController.java"),
    ("建表",     "examples/todo/src/main/resources/schema.sql"),
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


def report(skip_css):
    out = []
    tag = "不含共享 CSS" if skip_css else "含共享 CSS"
    out.append("")
    out.append("─" * 62)
    out.append(" 功能切片 01:Todo CRUD    [%s]" % tag)
    out.append("─" * 62)

    jr, jt = collect("Jaravel", JARAVEL, skip_css)
    lr, lt = collect("Laravel", LARAVEL, skip_css)

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

    all_out, ratio_with = report(False)
    lines += all_out
    all_out, ratio_without = report(True)
    lines += all_out

    lines.append("")
    lines.append("=" * 62)
    lines.append(" 门禁上限: %.2f" % max_ratio)
    lines.append(" 含 CSS   : %.2f   %s" % (ratio_with, "通过" if ratio_with <= max_ratio else "超标"))
    lines.append(" 不含 CSS : %.2f   %s  ← 以此为准" %
                 (ratio_without, "通过" if ratio_without <= max_ratio else "超标"))
    lines.append("=" * 62)

    text = "\n".join(lines) + "\n"
    open(os.path.join(ROOT, "parity-result.txt"), "w", encoding="utf-8").write(text)
    print("ratio_with_css=%.2f ratio_without_css=%.2f gate=%.2f"
          % (ratio_with, ratio_without, max_ratio))

    return 0 if ratio_without <= max_ratio else 1


if __name__ == "__main__":
    sys.exit(main())
