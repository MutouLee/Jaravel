"""
v0.1 垂直切片的端到端验收。

绕开 shell 的编码问题,直接用 Python 的 http 客户端。
结果写入 verify-result.txt(UTF-8),避免控制台代码页干扰。
"""
import json
import urllib.error
import urllib.parse
import urllib.request

B = "http://localhost:8000"
out = []
passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        out.append("  OK   %s" % name)
    else:
        failed += 1
        out.append("  FAIL %s" % name)
    if detail:
        out.append("         %s" % detail)


def request(method, path, fields=None, follow=True):
    data = urllib.parse.urlencode(fields).encode("ascii") if fields is not None else None
    headers = {"Content-Type": "application/x-www-form-urlencoded"} if data else {}

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(B + path, data=data, method=method, headers=headers)
    try:
        r = opener.open(req, timeout=10)
        return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def section(title):
    out.append("")
    out.append("=== %s ===" % title)


# ============ 1. 首页与模板 ============
section("1. 路由 + jblade 渲染")
status, body, headers = request("GET", "/")
html = body.decode("utf-8")
check("GET / 返回 200", status == 200, "实际 %d" % status)
check("响应以 <!DOCTYPE html> 开头(@extends 顶层无泄漏)", body.startswith(b"<!DOCTYPE html>"),
      "开头: %r" % body[:40])
check("布局继承:@section('title') → @yield('title')", "<title>Todo 列表</title>" in html)
check("@forelse 的空分支生效", "还没有任何待办" in html)
check("Content-Type 是 HTML", "text/html" in headers.get("Content-Type", ""))

# ============ 2. 表单提交与查询构造器 ============
section("2. 表单提交 + 查询构造器 CRUD")
TITLES = ["写 v0.1 垂直切片", "跑通端到端 CRUD", "统计 parity 比值", "中文与 café éü"]
for t in TITLES:
    st, _, hd = request("POST", "/todos", {"title": t}, follow=False)
    check("POST /todos 返回 302  (%s)" % t[:12], st == 302, "实际 %d" % st)
    check("  Location 指向 /", hd.get("Location", "").endswith("/"))

status, body, _ = request("GET", "/")
html = body.decode("utf-8")
check("列表渲染出全部 4 条", all(t in html for t in TITLES))
check("计数正确:共 4 项,4 项待办", "共 4 项,4 项待办" in html)

# ============ 3. 编码 ============
section("3. 编码(表单 → 数据库 → 模板 往返)")
todos = json.loads(request("GET", "/api/todos")[1].decode("utf-8"))
titles_back = [t["title"] for t in todos]
check("中文与变音符号往返一致", "中文与 café éü" in titles_back,
      "读回: %r" % titles_back)

# ============ 4. 返回值归一化 ============
section("4. 返回值归一化")
status, body, headers = request("GET", "/api/todos")
check("控制器返回 List<Todo> → JSON", status == 200 and body.startswith(b"["))
check("Content-Type 是 JSON", "application/json" in headers.get("Content-Type", ""))
check("JSON 字段来自 public 字段(ADR-010)",
      all(k in todos[0] for k in ("id", "title", "done", "created_at")),
      "字段: %s" % list(todos[0].keys()))

# ============ 5. 路径参数绑定 ============
section("5. 路径参数绑定与约束")
first_id = min(t["id"] for t in todos)
status, body, _ = request("GET", "/api/todos/%d" % first_id)
check("GET /api/todos/{id} → Long 类型转换", status == 200, "实际 %d" % status)
check("  返回的是正确那一条", json.loads(body.decode("utf-8"))["id"] == first_id)

check("不存在的 id → 404", request("GET", "/api/todos/999999")[0] == 404)
check("whereNumber 约束:非数字 id 不匹配 → 404",
      request("GET", "/api/todos/abc")[0] == 404)

# ============ 6. 状态变更 ============
section("6. 状态变更(toggle / delete)")
st, _, _ = request("POST", "/todos/%d/toggle" % first_id, {}, follow=False)
check("POST toggle → 302", st == 302, "实际 %d" % st)
html = request("GET", "/")[1].decode("utf-8")
check("计数变为 共 4 项,3 项待办", "共 4 项,3 项待办" in html)
check("已完成项带 done 样式类", 'class="done"' in html)

st, _, _ = request("POST", "/todos/%d/delete" % first_id, {}, follow=False)
check("POST delete → 302", st == 302)
html = request("GET", "/")[1].decode("utf-8")
check("删除后计数为 共 3 项", "共 3 项" in html)

# ============ 7. 安全 ============
section("7. 安全")
request("POST", "/todos", {"title": "<script>alert(1)</script>"})
html = request("GET", "/")[1].decode("utf-8")
check("模板自动转义,XSS 不执行",
      "&lt;script&gt;alert(1)&lt;/script&gt;" in html and "<script>alert(1)</script>" not in html)

# ============ 8. 直出视图与 404 ============
section("8. 直出视图与兜底")
status, body, _ = request("GET", "/about")
check("r.view(\"/about\", ...) 无控制器直出 200", status == 200, "实际 %d" % status)
check("  该页也继承了布局", b"<!DOCTYPE html>" in body and "关于这个示例".encode() in body)
check("未定义路由 → 404", request("GET", "/no-such-page")[0] == 404)
check("方法不符 → 非 2xx", request("GET", "/todos")[0] >= 400)

# ============ 9. 校验 ============
section("9. 控制器内的校验")
st, _, hd = request("POST", "/todos", {"title": "   "}, follow=False)
check("空白标题 → 302 回跳(不入库)", st == 302)
html = request("GET", "/")[1].decode("utf-8")
check("  空白标题未被写入", ">   <" not in html)

# ============ 汇总 ============
out.append("")
out.append("=" * 56)
out.append(" 端到端验收:%d 通过,%d 失败" % (passed, failed))
out.append("=" * 56)

open("verify-result.txt", "w", encoding="utf-8").write("\n".join(out) + "\n")
print("passed=%d failed=%d" % (passed, failed))
