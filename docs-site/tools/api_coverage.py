#!/usr/bin/env python3
"""公开 API 的文档覆盖审计(V1.0-RESULT §2 第 2 条)。

v1.0 验收第 2 条写的是「文档覆盖所有公开 API」,而当时只能标 ⚠️ ——
因为"覆盖"没有可判定的定义,"逐个公开 API 一个站点页面"对 400+ 个
公开类型也不是真的能维护的东西。这个脚本把它定义成两件能机械判定的事:

1. **权威参考完整**:每个公开类型(非 internal/impl 包)都有 Javadoc。
   聚合 Javadoc 只有在这条成立时才真的是"参考";漏掉的类在
   apidocs 里是一个空壳页面。
2. **入口可达**:每个有公开 API 的模块都在文档站的模块索引里出现,
   并且索引点的关键类型确实存在(不是改名后留下的死名字)。

用法:
    python docs-site/tools/api_coverage.py            # 报告
    python docs-site/tools/api_coverage.py --strict   # 有缺口就退 1(CI 用)
    python docs-site/tools/api_coverage.py --list     # 逐条列出缺口
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INDEX = ROOT / "docs-site" / "docs" / "api-reference.md"

# 不算公开 API 的包段:实现细节、Spring 装配、注解处理器内部
PRIVATE_SEGMENTS = {"internal", "impl", "autoconfigure", "support_internal"}

# 不算"对外模块"的 reactor 成员:测试夹具模块、处理器、插件、BOM
SKIP_MODULES = {
    "jaravel-bom",
    "jaravel-coverage",
    "jaravel-archunit-rules",
    "jaravel-maven-plugin",
    "jaravel-api-processor",
    "jaravel-eloquent-processor",
    "jaravel-events-processor",
    "jaravel-auth-tests",
    "jaravel-eloquent-tests",
    "jaravel-events-tests",
    "jaravel-spring-boot-starter",  # 全是自动装配,入口是别的模块的类型
}

TYPE_RE = re.compile(
    r"^(?P<indent>\s*)"
    r"(?P<mods>(?:public\s+)(?:(?:final|abstract|static|sealed|non-sealed)\s+)*)"
    r"(?P<kind>class|interface|enum|record|@interface)\s+"
    r"(?P<name>\w+)"
)


def is_private_package(path: Path, module: Path) -> bool:
    rel = path.relative_to(module / "src" / "main" / "java").parts[:-1]
    return any(seg in PRIVATE_SEGMENTS for seg in rel)


def public_types(java: Path) -> list[tuple[str, bool]]:
    """返回 [(类型名, 有 Javadoc)] —— 只看顶层类型(缩进为 0)。"""
    lines = java.read_text(encoding="utf-8", errors="replace").splitlines()
    found = []
    for i, line in enumerate(lines):
        m = TYPE_RE.match(line)
        if not m or m.group("indent"):
            continue
        # 往上跳过空行、行注释和注解(注解可能跨多行:`@SpringBootTest(\n ... )`)。
        # 判据:注解块里的行都不会以 ; 或 } 结尾,而上一个声明/import 会 ——
        # 先撞上 */ 就是有块注释,先撞上 ; } 就是没有。
        j = i - 1
        documented = False
        while j >= 0:
            s = lines[j].strip()
            if s.endswith("*/"):
                documented = True
                break
            if not s or s.startswith("//") or s.startswith("@") or not (
                    s.endswith(";") or s.endswith("}") or s.endswith("{")):
                j -= 1
                continue
            break
        if documented:
            # 区分 /** Javadoc */ 与 /* 普通块注释 */
            k = j
            while k >= 0 and not lines[k].strip().startswith("/*"):
                k -= 1
            documented = k >= 0 and lines[k].strip().startswith("/**")
        found.append((m.group("name"), documented))
    return found


def modules() -> list[Path]:
    out = []
    for p in sorted(ROOT.iterdir()):
        if not p.is_dir() or not p.name.startswith("jaravel-"):
            continue
        if p.name in SKIP_MODULES:
            continue
        if (p / "src" / "main" / "java").is_dir():
            out.append(p)
    return out


def index_entries() -> dict[str, list[str]]:
    """解析 api-reference.md 的模块索引表:模块 -> 关键类型名。"""
    text = INDEX.read_text(encoding="utf-8")
    entries: dict[str, list[str]] = {}
    for row in re.finditer(r"^\|\s*`(jaravel-[\w-]+)`\s*\|(.+?)\|\s*$", text, re.M):
        module, cell = row.group(1), row.group(2)
        # 只把「反引号里首字母大写的标识符」当类型名 —— 单元格里还有
        # `jaravel` 这种命令名和中文说明,它们不是要核对的类型
        names = [n for n in re.findall(r"`@?(\w+)", cell) if n[:1].isupper()]
        entries[module] = names
    return entries


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="有缺口则退出码 1")
    ap.add_argument("--list", action="store_true", help="逐条列出缺口")
    args = ap.parse_args()

    index = index_entries()
    all_type_names: set[str] = set()
    rows = []
    undocumented: list[str] = []
    missing_modules: list[str] = []

    for module in modules():
        src = module / "src" / "main" / "java"
        total = documented = 0
        for java in sorted(src.rglob("*.java")):
            if is_private_package(java, module):
                continue
            for name, has_doc in public_types(java):
                total += 1
                all_type_names.add(name)
                if has_doc:
                    documented += 1
                else:
                    undocumented.append(
                        f"{java.relative_to(ROOT).as_posix()}:{name}")
        if total == 0:
            continue
        in_index = module.name in index
        if not in_index:
            missing_modules.append(module.name)
        rows.append((module.name, total, documented, in_index))

    stale = []
    for module, names in index.items():
        for name in names:
            if name not in all_type_names:
                stale.append(f"{module}: {name}")

    total = sum(r[1] for r in rows)
    documented = sum(r[2] for r in rows)
    print(f"公开类型 {total},有 Javadoc {documented} "
          f"({documented * 100 // max(total, 1)}%)")
    print(f"模块 {len(rows)},在索引里 {sum(1 for r in rows if r[3])}")
    print()
    print(f"{'模块':<28}{'公开类型':>8}{'有文档':>8}{'索引':>6}")
    for name, t, d, idx in rows:
        flag = "✓" if idx else "✗"
        print(f"{name:<28}{t:>8}{d:>8}{flag:>6}")

    if missing_modules:
        print("\n不在模块索引里的模块:")
        for m in missing_modules:
            print(f"  - {m}")
    if stale:
        print("\n索引里指向不存在类型的名字:")
        for s in stale:
            print(f"  - {s}")
    if undocumented:
        print(f"\n缺 Javadoc 的公开类型:{len(undocumented)}")
        if args.list:
            for u in undocumented:
                print(f"  - {u}")
        else:
            for u in undocumented[:20]:
                print(f"  - {u}")
            if len(undocumented) > 20:
                print(f"  ... 另有 {len(undocumented) - 20} 条(--list 全列)")

    gaps = bool(undocumented or missing_modules or stale)
    if args.strict and gaps:
        print("\n不通过:上面的缺口要么补文档,要么把类型移进 internal 包。")
        return 1
    print("\n通过:公开 API 全部有 Javadoc,模块索引完整且无死名字。"
          if not gaps else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
