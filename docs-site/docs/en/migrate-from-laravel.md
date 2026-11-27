# Migrating from Laravel

Jaravel's goal is not "Laravel for Java", but to bring over the parts of the
Laravel experience that have **one obvious answer**, while keeping Java's type
safety and the Spring ecosystem.

## Concept mapping

| Laravel | Jaravel |
|---|---|
| `routes/web.php` | `routes/WebRoutes.java` (`RouteDefinition`) |
| `Route::get('/posts/{id}', [C::class, 'show'])` | `r.get("/posts/{id}", C::show)` |
| Controller methods | Controller methods (bound by parameter name) |
| `$request->validate([...])` | `FormRequest` / `Validator.make(...)` |
| Eloquent model `$fillable` | `@Model` + `@Fillable` |
| `$table->id()` in a migration | `Schema.create("posts", t -> t.id())` |
| `artisan make:model` | `jaravel make:model` |
| `artisan migrate` | `jaravel migrate` |
| Blade | jblade (`.jblade.html`, close to Blade syntax) |
| `@extends` / `@section` | the same |
| `{{ $post->title }}` | `{{ post.title }}` |
| `Auth::attempt()` | `Auth.attempt()` |
| `Password::sendResetLink()` | `Password.sendResetLink()` |
| `Queue::dispatch()` | `Queue.dispatch()` / `ShouldQueue` |
| `Mail::to()->send()` | `Mail.to().send()` |
| `php artisan tinker` | `jaravel tinker` (partial) |
| PHPUnit / Pest | JUnit 5 + `FeatureTest` |

## Mental differences

1. **Strong typing**: route parameters, template `@params` and model fields are
   Java types; a wrong name is a compile error, not a runtime 500;
2. **No `@php ... @endphp`**: templates do not contain Java code blocks; put
   complex logic in controllers/components;
3. **No dynamic properties**: `$post->title` becomes `post.title` (a field) or
   `post.title()` (an accessor); the IDE can navigate;
4. **No runtime package scanning/autoloading**: routes are registered
   explicitly by `RouteDefinition`;
5. **`empty()` semantics differ**: `Jb.isEmpty` only treats null/empty
   strings/empty collections as empty; `0` and `false` are **not** empty;
6. **Configuration**: `config/*.yml` + `.env`, with `${VAR:default}`
   placeholders;
7. **Multiple instances**: sessions/cache are in-process by default; switch to
   Redis/database explicitly in production.

## Suggested migration order

1. Add the starter first and keep existing Spring code (they can coexist);
2. Start with new CRUD pages: routes + controllers + models;
3. Rewrite Blade templates page by page as jblade;
4. For authentication use `jaravel install:auth`, or bridge existing Spring
   Security (`jaravel-security-bridge`);
5. Before migrating jobs/mail/notifications, look at `examples/queue`.

## What does not migrate

- The frontend framework ecosystem is out of scope: use jblade for first paint
  and SEO pages, and a separate SPA hitting the JSON API for rich interactivity;
- The Composer package ecosystem is replaced by Maven/Spring.

## A minimal side-by-side

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
