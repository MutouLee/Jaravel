"""
v0.2 blog 切片的端到端验收。

重点不只是「能跑」,而是 v0.2 特有的那几件事:
  · 关联预加载确实生效(而不是悄悄 N+1);
  · @Hidden 在 **API 响应**里也生效(v0.2 发现过这个安全缺口);
  · 校验失败对表单是 302 回跳、对 API 是 422 JSON;
  · 软删除 / 恢复;
  · ModelNotFoundException → 404。
"""
import json
import urllib.error
import urllib.parse
import urllib.request

B = "http://localhost:8001"
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


def request(method, path, fields=None, follow=True, json_accept=False):
    data = urllib.parse.urlencode(fields, doseq=True).encode("utf-8") if fields is not None else None
    headers = {}
    if data:
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
    if json_accept:
        headers["Accept"] = "application/json"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(B + path, data=data, method=method, headers=headers)
    try:
        r = opener.open(req, timeout=15)
        return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def section(title):
    out.append("")
    out.append("=== %s ===" % title)


# ============ 1. 列表页与关联 ============
section("1. 列表页(预加载 author + tags)")

status, body, headers = request("GET", "/")
text = body.decode("utf-8", "replace")
check("GET / → 200", status == 200, "实际 %s" % status)
check("渲染出文章标题", "<h2>" in text)
check("渲染出作者名(BelongsTo 预加载)", "作者" in text)
check("没有泄漏懒加载异常", "LazyLoadingViolation" not in text)

# ============ 2. API 与序列化 ============
section("2. API 序列化")

status, body, headers = request("GET", "/api/posts")
posts = json.loads(body)
check("GET /api/posts → 200", status == 200)
check("有数据", len(posts) > 0, "条数 %s" % len(posts))

if posts:
    p = posts[0]
    check("键是 snake_case(与 Laravel 的 API 同构)",
          "created_at" in p and "createdAt" not in p,
          "键: %s" % sorted(p.keys()))
    check("不泄漏 ORM 内部状态",
          not any(k in p for k in ("dirty", "clean", "trashed")),
          "键: %s" % sorted(p.keys()))
    check("预加载的关联出现在 JSON 里", "author" in p)
    if isinstance(p.get("author"), dict):
        check("@Hidden 在 API 响应里也生效(password 不出现)",
              "password" not in p["author"],
              "author 键: %s" % sorted(p["author"].keys()))

# ============ 3. 详情页与嵌套关联 ============
section("3. 详情页(author + tags + comments)")

slug = posts[0]["slug"] if posts else "none"
status, body, headers = request("GET", "/posts/%s" % slug)
text = body.decode("utf-8", "replace")
check("GET /posts/{slug} → 200", status == 200, "实际 %s" % status)
check("渲染出评论区", "评论" in text)

# 浏览量自增(原子 increment)
status, body, _ = request("GET", "/api/posts/%s" % posts[0]["id"]) if posts else (0, b"{}", {})
before = json.loads(body).get("views", 0) if status == 200 else 0
request("GET", "/posts/%s" % slug)
status, body, _ = request("GET", "/api/posts/%s" % posts[0]["id"]) if posts else (0, b"{}", {})
after = json.loads(body).get("views", 0) if status == 200 else 0
check("浏览量原子自增", after == before + 1, "%s → %s" % (before, after))

# ============ 4. 404 ============
section("4. ModelNotFoundException → 404")

status, body, _ = request("GET", "/api/posts/999999")
check("findOrFail 不存在的 id → 404", status == 404, "实际 %s" % status)

status, body, _ = request("GET", "/posts/no-such-slug")
check("不存在的 slug → 404", status == 404, "实际 %s" % status)

# ============ 5. 校验 ============
section("5. FormRequest 校验")

# 表单请求:302 回跳
status, body, headers = request("POST", "/posts",
                                {"title": "", "body": "短"}, follow=False)
check("表单校验失败 → 302 回跳(而不是 422 错误页)",
      status == 302, "实际 %s" % status)

# API 请求:422 + Laravel 同构 JSON
status, body, headers = request("POST", "/posts",
                                {"title": "", "body": "短"},
                                follow=False, json_accept=True)
check("API 校验失败 → 422", status == 422, "实际 %s" % status)
if status == 422:
    payload = json.loads(body)
    check("422 响应体结构与 Laravel 一致",
          "message" in payload and "errors" in payload,
          "键: %s" % sorted(payload.keys()))
    check("错误按字段分组", isinstance(payload["errors"], dict)
          and "title" in payload["errors"])
    check("自定义 attributes 生效(:attribute → 标题)",
          any("标题" in m for m in payload["errors"].get("title", [])),
          "title: %s" % payload["errors"].get("title"))

# ============ 6. 创建成功 ============
section("6. 创建(含多对多 sync)")

author_id = posts[0]["author_id"] if posts else 1
status, body, headers = request("POST", "/posts", {
    "title": "端到端新建的文章",
    "body": "这是一篇由验收脚本创建的文章,正文足够长。",
    "author_id": author_id,
    "published": "1",
}, follow=False)
check("创建成功 → 302", status == 302, "实际 %s" % status)
location = headers.get("Location", "")
check("重定向到新文章", "/posts/" in location, location)

status, body, _ = request("GET", location if location.startswith("/") else "/")
check("新文章可访问", status == 200, "实际 %s" % status)
check("标题正确", "端到端新建的文章" in body.decode("utf-8", "replace"))

# slug 唯一:同名再建一次应失败
status, body, headers = request("POST", "/posts", {
    "title": "端到端新建的文章",
    "body": "这是一篇由验收脚本创建的文章,正文足够长。",
    "author_id": author_id,
}, follow=False, json_accept=True)
check("slug 重复被 unique 规则拦住 → 422", status == 422, "实际 %s" % status)
if status == 422:
    msgs = json.loads(body)["errors"].get("slug", [])
    check("用了自定义消息", any("已被占用" in m for m in msgs), str(msgs))

# ============ 7. 评论 ============
section("7. 评论(HasMany create)")

post_id = posts[0]["id"] if posts else 1
status, body, headers = request("POST", "/posts/%s/comments" % post_id,
                                {"author_name": "验收脚本", "body": "一条测试评论"},
                                follow=False)
check("提交评论 → 302", status == 302, "实际 %s" % status)

status, body, headers = request("POST", "/posts/%s/comments" % post_id,
                                {"author_name": "", "body": ""},
                                follow=False, json_accept=True)
check("空评论被拦住 → 422", status == 422, "实际 %s" % status)

# ============ 8. 软删除与恢复 ============
section("8. 软删除 / 恢复")

status, body, _ = request("GET", "/api/posts")
all_posts = json.loads(body)
target = all_posts[-1]["id"]

status, _, _ = request("POST", "/posts/%s/delete" % target, {}, follow=False)
check("软删除 → 302", status == 302, "实际 %s" % status)

status, body, _ = request("GET", "/api/posts")
remaining = [p["id"] for p in json.loads(body)]
check("软删后列表里看不到", target not in remaining)

status, _, _ = request("POST", "/posts/%s/restore" % target, {}, follow=False)
check("恢复 → 302", status == 302, "实际 %s" % status)

status, body, _ = request("GET", "/api/posts")
restored = [p["id"] for p in json.loads(body)]
check("恢复后又能看到", target in restored)

# ============ 9. 安全 ============
section("9. 安全")

status, body, headers = request("POST", "/posts", {
    "title": "<script>alert(1)</script>",
    "body": "XSS 测试文章,正文足够长不会被 min 规则拦住。",
    "author_id": author_id,
}, follow=False)
if status == 302 and headers.get("Location"):
    status, body, _ = request("GET", headers["Location"])
    text = body.decode("utf-8", "replace")
    check("模板自动转义,XSS 不执行",
          "<script>alert(1)</script>" not in text and "&lt;script&gt;" in text)

# 批量赋值:试图直接设置 views 与 id
status, body, headers = request("POST", "/posts", {
    "title": "批量赋值测试",
    "body": "试图通过表单直接改 views 与 id,应被 @Fillable 挡住。",
    "author_id": author_id,
    "views": 99999,
    "id": 1,
}, follow=False)
if status == 302 and headers.get("Location"):
    slug2 = headers["Location"].rsplit("/", 1)[-1]
    status, body, _ = request("GET", "/api/posts")
    created = [p for p in json.loads(body) if p["slug"] == slug2]
    if created:
        check("@Fillable 挡住了 views 的批量赋值",
              created[0]["views"] != 99999, "views = %s" % created[0]["views"])
        check("@Fillable 挡住了 id 的批量赋值", created[0]["id"] != 1,
              "id = %s" % created[0]["id"])

# ============ 汇总 ============
out.append("")
out.append("=" * 56)
out.append(" passed=%d  failed=%d" % (passed, failed))
out.append("=" * 56)

open("verify-blog-result.txt", "w", encoding="utf-8").write("\n".join(out) + "\n")
print("passed=%d failed=%d" % (passed, failed))
