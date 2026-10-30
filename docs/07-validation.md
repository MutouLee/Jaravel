# 07 · 验证

---

## 设计目标

1. 规则表达的简洁度对齐 Laravel(`"required|email|unique:users,email"`);
2. 同时提供类型安全的 Builder 形式,两者等价;
3. 错误消息本地化,且可按字段/规则精细覆盖;
4. **不使用 Jakarta Bean Validation**(Hibernate Validator)——见 §7。

---

## 1. 两种写法

### 1.1 字符串规则(简洁优先)

```java
Validated v = Validator.make(request.all(), Map.of(
    "title",      "required|string|max:200",
    "body",       "required|string|min:10",
    "email",      "required|email|unique:users,email",
    "password",   "required|min:8|confirmed",
    "age",        "nullable|integer|between:18,120",
    "tags",       "array|max:5",
    "tags.*",     "exists:tags,id",
    "avatar",     "image|max:2048|dimensions:min_width=100",
    "publish_at", "date|after:today",
    "role",       "in:admin,editor,viewer",
    "website",    "url|active_url",
    "meta.city",  "required_with:meta.country|string"
)).validate();                   // 失败抛 ValidationException

String title = v.string("title");
int age = v.integer("age", 0);
List<Long> tags = v.longs("tags");
```

### 1.2 Builder 形式(类型安全优先)

```java
Validated v = Validator.make(request.all())
    .field("title", Rule.required(), Rule.string(), Rule.max(200))
    .field("email", Rule.required(), Rule.email(), Rule.unique(User.class, User_.EMAIL))
    .field("age",   Rule.nullable(), Rule.integer(), Rule.between(18, 120))
    .field("role",  Rule.in(Role.values()))
    .validate();
```

`Rule.unique(User.class, User_.EMAIL)` 相比字符串 `"unique:users,email"` 的优势:**改表名/列名时编译期发现**。

两种写法共享同一套规则实现,可混用:

```java
.field("title", "required|max:200")
.field("email", Rule.unique(User.class, User_.EMAIL).ignore(user.getId()))
```

---

## 2. 内置规则

### 2.1 存在性与类型

| 规则 | 说明 |
|---|---|
| `required` | 存在且非空(空串、空数组、null 均视为空) |
| `nullable` | 允许为 null(放在首位,否则后续规则会对 null 报错) |
| `filled` | 若存在则不能为空 |
| `present` | 必须存在(可以为空) |
| `prohibited` | 必须不存在或为空 |
| `sometimes` | 仅当字段存在时才校验后续规则 |
| `string` `integer` `numeric` `boolean` `array` `json` `file` `image` | 类型 |
| `decimal:2` `decimal:2,4` | 小数位数 |

### 2.2 条件必填

| 规则 |
|---|
| `required_if:other,value` |
| `required_unless:other,value` |
| `required_with:a,b` / `required_with_all:a,b` |
| `required_without:a,b` / `required_without_all:a,b` |
| `required_array_keys:a,b` |
| `prohibited_if` / `prohibited_unless` / `prohibits` |
| `missing_if` / `missing_with` |
| `exclude_if` / `exclude_unless` / `exclude_with`(校验通过后从 validated 中剔除) |

### 2.3 大小与范围

`min:n` `max:n` `size:n` `between:a,b` `gt:field` `gte:field` `lt:field` `lte:field` `digits:n` `digits_between:a,b` `multiple_of:n`

> `min`/`max` 的语义随类型变化(字符串按字符数,数值按值,数组按元素数,文件按 KB),与 Laravel 一致。

### 2.4 格式

`email` `email:rfc,dns` `url` `active_url` `ip` `ipv4` `ipv6` `mac_address` `uuid` `ulid` `alpha` `alpha_dash` `alpha_num` `ascii` `hex_color` `regex:/pattern/` `not_regex:/pattern/` `starts_with:a,b` `ends_with:a,b` `doesnt_start_with` `contains` `lowercase` `uppercase` `timezone`

### 2.5 日期

`date` `date_format:yyyy-MM-dd` `after:date|field` `after_or_equal` `before` `before_or_equal` `date_equals`

日期规则支持相对表达式:`after:today`、`before:+30 days`、`after:publish_at`(引用其他字段)。

### 2.6 数据库

| 规则 | 说明 |
|---|---|
| `unique:users,email` | 唯一 |
| `unique:users,email,except_id` | 排除某行(更新场景) |
| `exists:tags,id` | 存在 |
| `exists:tags,id,status,active` | 带附加条件 |

Builder 形式:

```java
Rule.unique(User.class, User_.EMAIL).ignore(user.getId()).where(q -> q.where(User_.TENANT_ID, tid));
Rule.exists(Tag.class, Tag_.ID).where(q -> q.where(Tag_.ACTIVE, true));
```

### 2.7 其他

`confirmed`(需 `{field}_confirmation`)· `same:other` · `different:other` · `in:a,b` · `not_in:a,b` · `in_array:other.*` · `distinct` · `accepted` · `accepted_if` · `declined` · `current_password` · `password`(强度)· `enum:UserStatus` · `mimes:jpg,png` · `mimetypes:image/*` · `extensions:jpg`(`dimensions` 见 §2.8,未实现)

### 2.8 文件

```java
Map.of("avatar", "required|image|mimes:jpg,png|max:2048")
```

> **v0.4 实现说明**
>
> 五条规则实现了:`file` · `image` · `mimes:jpg,png` ·
> `mimetypes:image/png,image/*` · `extensions:csv,txt`。
> `min` / `max` / `size` / `between` / `gt` 等对文件按 **KB**
> (与 Laravel 一致 —— 所以 `max:2048` 是 2 MB)。
>
> ★ **`image` / `mimes` / `mimetypes` 按内容嗅探判断**,不看文件名也不看
> 客户端声称的 `Content-Type`。一个检查 `.endsWith(".png")` 的实现
> 等于没检查:攻击者把 PHP 脚本命名为 `avatar.png` 就过了。
>
> ⚠ **`mimes` 对嗅探不出来的类型一律拒绝**,所以 `mimes:txt` /
> `mimes:csv` **永远不通过** —— 纯文本没有魔数。一个「认不出就放过」
> 的白名单等于没有白名单。文本类文件用 `extensions:csv,txt`
> (它看**文件名**,所以不是安全检查 —— 文档里这么写是刻意的)。
>
> ⚠ **`image` 不把 SVG 算作图片。** SVG 能带 `<script>`,
> 放过它等于一个存储型 XSS。Laravel 有 `image:allow_svg`,
> 这里**不提供** —— Jaravel 没有 SVG 消毒器,给一个开关却不给消毒
> 等于给了一把没有保险的枪。
>
> ⚠ **`dimensions` 未实现。** 它要解码图片才能知道宽高,而那引入一个
> 解压炸弹的面:一个 100 KB 的 PNG 可以声明 50000×50000 的尺寸,
> 解码它会吃掉几 GB 内存。做它需要先限制解码的像素总数。
>
> ⚠ **没有 `Rule.file()` 的 Builder 写法**(上面原本的示例)——
> 字符串写法已经覆盖,而一个只在文件这一类上有的 Builder
> 会与其余 60 条规则的形式不一致。
>
> 消息会自动用 `.file` 的措辞(「不能超过 2048 KB」),
> 而不是落到 string 分支说成「不能超过 2048 个字符」。

---

## 3. 嵌套与数组

```java
Map.of(
    "user.name",            "required|string",
    "user.profile.city",    "required_with:user.profile.country",
    "items",                "required|array|min:1",
    "items.*.sku",          "required|string|exists:products,sku",
    "items.*.qty",          "required|integer|min:1",
    "items.*.options.*.id", "integer"
)
```

通配符 `*` 在校验时展开为实际索引,错误键也带索引:`items.2.qty`。

---

## 4. FormRequest

```java
public class StorePostRequest extends FormRequest {

    @Override public boolean authorize() {
        return Gate.allows("create", Post.class);
    }

    @Override public Map<String, String> rules() {
        return Map.of(
            "title", "required|string|max:200|unique:posts,title",
            "body",  "required|string",
            "tags",  "array|max:5",
            "tags.*", "exists:tags,id"
        );
    }

    @Override public Map<String, String> messages() {
        return Map.of(
            "title.required", "标题不能为空",
            "title.unique",   "标题「:input」已被使用"
        );
    }

    @Override public Map<String, String> attributes() {
        return Map.of("title", "文章标题");     // 用于默认消息中的字段名
    }

    /** 校验前预处理 */
    @Override protected void prepareForValidation() {
        merge("slug", Str.slug(input("title", "")));
        merge("tags", normalizeTags(input("tags")));
    }

    /** 跨字段的复杂校验 */
    @Override protected void withValidator(Validator v) {
        v.after(val -> {
            if (publishAt() != null && publishAt().isBefore(Instant.now())) {
                val.errors().add("publish_at", "发布时间不能早于当前时间");
            }
        });
    }

    /** 自定义失败处理(默认 422 JSON / 回跳带 errors) */
    @Override protected Response failedValidation(ErrorBag errors) { ... }

    // 类型安全取值器
    public String title()      { return validated().string("title"); }
    public String body()       { return validated().string("body"); }
    public List<Long> tagIds() { return validated().longs("tags"); }
    public Instant publishAt() { return validated().instant("publish_at"); }
}
```

控制器:

```java
public Response store(StorePostRequest req) {
    Post post = Post.create(Map.of("title", req.title(), "body", req.body()));
    post.tags().sync(req.tagIds());
    return Redirect.route("posts.show", post.getId()).with("status", "created");
}
```

绑定流程(由参数绑定器执行):`实例化 → prepareForValidation() → authorize()(失败 403)→ 规则校验 + withValidator → 失败则 failedValidation → 成功则注入`。

### 4.1 取值器的自动生成(v0.5)

APT 从 `rules()` 的字面量 Map 推断类型,生成取值器:

```java
// 生成 StorePostRequest_
public String title();        // 因为 "string"
public List<Long> tagIds();   // 因为 "array" + "tags.* exists:tags,id"
```

若 `rules()` 不是字面量(动态构造),则跳过生成,用户手写。

---

## 5. 错误与本地化

### 5.1 ErrorBag

```java
ErrorBag errors = e.errors();
boolean has = errors.has("title");
String first = errors.first("title");
List<String> all = errors.get("title");
Map<String, List<String>> allMap = errors.messages();
List<String> flat = errors.all();
int count = errors.count();
```

### 5.2 JSON 响应(API)

```json
{
  "message": "给定数据无效。",
  "errors": {
    "title": ["标题不能为空"],
    "tags.2": ["所选的 tags.2 无效。"]
  }
}
```

状态码 422。格式与 Laravel 完全一致,前端代码可直接复用。

### 5.3 模板中使用

```html
<input name="title" value="@old('title')" class="{{ hasError('title') ? 'is-invalid' : '' }}">
@error('title') <span class="error">{{ message }}</span> @enderror
```

### 5.4 语言包

```yaml
# resources/lang/zh_CN/validation.yml
validation:
  required: ":attribute 不能为空。"
  email: ":attribute 必须是有效的邮箱地址。"
  max:
    string: ":attribute 不能超过 :max 个字符。"
    numeric: ":attribute 不能大于 :max。"
    array: ":attribute 最多只能有 :max 项。"
    file: ":attribute 不能超过 :max KB。"
  unique: ":attribute 已被占用。"

  # 字段级覆盖
  custom:
    email:
      required: "请填写邮箱"
  attributes:
    email: "邮箱"
    title: "文章标题"
```

占位符:`:attribute` `:value` `:input` `:min` `:max` `:other` `:size` `:values`。

---

## 6. 自定义规则

### 6.1 函数式(一次性)

```java
.field("slug", Rule.closure((attr, value, fail) -> {
    if (RESERVED.contains(value)) fail.accept("「" + value + "」是保留字");
}))
```

### 6.2 规则类(可复用)

```java
public class Uppercase implements ValidationRule {
    @Override public void validate(String attribute, Object value, Consumer<String> fail) {
        if (!(value instanceof String s) || !s.equals(s.toUpperCase())) {
            fail.accept("validation.uppercase");       // 语言包键
        }
    }
}

// 带数据访问 + 需要其他字段
public class MatchesTenant implements DataAwareRule, ValidationRule {
    private Map<String, Object> data;
    @Override public void setData(Map<String, Object> d) { this.data = d; }
    @Override public void validate(String attr, Object value, Consumer<String> fail) { ... }
}
```

### 6.3 注册为字符串规则

```java
// AppServiceProvider#boot
Validator.extend("uppercase", Uppercase.class);
Validator.extend("tenant_owned", TenantOwned.class);
// 之后可写 "required|uppercase"
```

### 6.4 隐式规则

```java
Validator.extendImplicit("required_if_tenant", RequiredIfTenant.class);
```

「隐式」指即使字段缺失也会执行该规则(`required*` 系列都是隐式的)。

---

## 7. 为什么不用 Jakarta Bean Validation(架构决策)

**决策:自研验证器,不以 Hibernate Validator 为底座。**

| Bean Validation 的问题 | 对 Jaravel 的影响 |
|---|---|
| 规则绑在类的字段上(`@NotNull`),不是绑在「这次请求」上 | 同一实体在「创建」和「更新」场景规则不同,需要分组(`groups`),极其繁琐 |
| 无法表达 `unique:users,email,except_id` 这类需查库的场景 | 要自己写 `ConstraintValidator` + 注入 Repository,样板巨大 |
| 嵌套数组通配符(`items.*.qty`)表达困难 | 需要 `@Valid` 逐层下传,错误键格式不可控 |
| 错误消息格式与 Laravel 的 `{message, errors}` 不一致 | 前端无法复用 |
| 条件规则(`required_with`)需要类级约束 + 自定义校验器 | 每条规则一个类,DX 崩塌 |
| 跨字段比较(`gte:start_date`)同上 | |

**核心矛盾**:Bean Validation 是**声明式的领域约束**(这个实体永远满足什么);Laravel 验证是**命令式的输入校验**(这次请求的这组数据是否合法)。两者解决的是不同问题,强行套用会让简单的事变复杂。

**自研成本**:约 60 条内置规则 + 规则解析 + 消息本地化,约 6000~8000 行,无技术风险。

**兼容措施**:`jaravel-bean-validation-bridge` 提供适配——若模型上有 `@NotNull` 等注解,可用 `Rule.beanValidation(User.class)` 一并校验,便于已有项目迁移。

见 ADR-005。

---

## 8. 逃生口

| 需求 | 途径 |
|---|---|
| 规则不够用 | `Rule.closure(...)` 或自定义 `ValidationRule` |
| 需要 Bean Validation | `jaravel-bean-validation-bridge` |
| 需要在 Service 层校验 | `Validator.make(data, rules).validate()` 可在任意位置调用 |
| 需要自定义错误响应格式 | 覆写 `FormRequest#failedValidation` 或全局 `ExceptionHandler` |
| 需要校验非 HTTP 输入(CLI/队列) | `Validator` 不依赖 HTTP,直接用 |

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 规则形式 | 仅字符串 / `Rule` 对象 | 字符串 + 类型安全 Builder | 提供编译期校验选项 |
| `unique` 表名 | 字符串 | `Rule.unique(User.class, User_.EMAIL)` | 重构安全 |
| 取值 | `$validated['title']` | `v.string("title")` 或生成的 `req.title()` | Java 无弱类型下标 |
| 语言包 | PHP 数组 | YAML | — |
| 规则注册 | `Validator::extend` 闭包 | 规则类 + `extend` | Java 无匿名函数持久化需求 |

---

## 10. 待决问题

- [ ] 字符串规则的解析是在**运行时**还是**编译期**?运行时实现简单;编译期可校验规则名拼写与参数个数(`"requird|email"` 直接报错),DX 更好。倾向:运行时解析 + APT 对字面量 Map 做**额外的编译期检查**(两者并存,APT 只负责报错不负责执行)。
- [ ] `rules()` 返回 `Map<String,String>` 的顺序不确定(`Map.of` 无序),而规则的执行顺序影响错误报告顺序。需改为 `LinkedHashMap` 或提供 `Rules.of(...)` 有序构造器。**这是一个实际 bug 风险,需在 v0.2 前定下。**
- [ ] `sometimes` / `exclude_if` 这类流程控制规则与 Builder 形式的结合方式。
- [ ] 取值器自动生成(§4.1)的类型推断可靠性:`"integer"` → `int` 还是 `Integer`(取决于是否 `nullable`)。规则组合复杂时可能推断错误,需要兜底为 `Object` + 显式转换。
