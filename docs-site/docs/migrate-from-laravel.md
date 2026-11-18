# 从 Laravel 迁移

Jaravel 的目标不是「Java 版 Laravel」,而是把 Laravel 里**有唯一正确答案**的
那部分体验搬过来,同时保留 Java 的类型安全与 Spring 生态。

## 概念对照

| Laravel | Jaravel |
|---|---|
| `routes/web.php` | `routes/WebRoutes.java`(`RouteDefinition`) |
| `Route::get('/posts/{id}', [C::class, 'show'])` | `r.get("/posts/{id}", C::show)` |
| 控制器方法 | 控制器方法(参数名绑定) |
| `$request->validate([...])` | `FormRequest` / `Validator.make(...)` |
| Eloquent 模型 `$fillable` | `@Model` + `@Fillable` |
| `$table->id()` 迁移 | `Schema.create("posts", t -> t.id())` |
| `artisan make:model` | `jaravel make:model` |
| `artisan migrate` | `jaravel migrate` |
| Blade | jblade(`.jblade.html`,语法接近 Blade) |
| `@extends` / `@section` | 同左 |
| `{{ $post->title }}` | `{{ post.title }}` |
| `Auth::attempt()` | `Auth.attempt()` |
| `Password::sendResetLink()` | `Password.sendResetLink()` |
| `Queue::dispatch()` | `Queue.dispatch()` / `ShouldQueue` |
| `Mail::to()->send()` | `Mail.to().send()` |
| `php artisan tinker` | `jaravel tinker`(部分功能) |
| PHPUnit / Pest | JUnit 5 + `FeatureTest` |

## 心智差异

1. **强类型**:路由参数、模板 `@params`、模型字段都是 Java 类型;
   写错名字是编译错误,不是运行时 500;
2. **没有 `@php ... @endphp`**:模板里不写 Java 代码块,复杂逻辑放控制器/组件;
3. **没有动态属性**:`$post->title` 变成 `post.title`(字段)或 `post.title()`
   (访问器);IDE 可跳转;
4. **没有运行时包扫描/自动加载**:路由由 `RouteDefinition` 显式注册;
5. **`empty()` 语义不同**:`Jb.isEmpty` 只把 null/空串/空集合算空,
   `0` 与 `false` **不**算空;
6. **配置**:`config/*.yml` + `.env`,`${VAR:default}` 占位符;
7. **多实例**:会话/缓存默认是进程内的,生产要显式换 Redis/database。

## 迁移顺序建议

1. 先装 starter,保留现有 Spring 代码(可以共存);
2. 从新的 CRUD 页面开始写路由 + 控制器 + 模型;
3. 把 Blade 模板逐页改写成 jblade;
4. 认证用 `jaravel install:auth`,或桥接现有 Spring Security(`jaravel-security-bridge`);
5. 迁移任务/邮件/通知时,先看 `examples/queue`。

## 不迁的东西

- 前端框架生态不在当前范围;首屏和 SEO 页面用 jblade,
  富交互前端走独立 SPA 直连 JSON API;
- Composer 包生态由 Maven/Spring 生态替代。

## 一个最小对照

```php
// Laravel
Route::get('/posts/{id}', [PostController::class, 'show']);

public function show(Post $post) {
    return view('posts.show', compact('post'));
}
```

```java
// Jaravel
r.get("/posts/{id}", PostController::show).whereNumber("id");

public View show(Long id) {
    return View.of("posts.show").with("post", Post.findOrFail(id));
}
```
