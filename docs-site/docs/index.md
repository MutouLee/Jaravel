# Jaravel

> Laravel 级开发体验,构建在 Spring Boot 之上的 Java 应用框架。

Jaravel 不是另一个 Web 框架,也不是 Spring 的替代品。它骑在 Spring Boot 之上:

- **向下**:复用 Spring IoC、AOP、事务、嵌入式容器与整个生态;
- **向上**:提供 Laravel 风格、有唯一正确答案、编译期类型安全的 API;
- **对外**:一个 starter,照常能用任何 Spring 库。

```java
// routes/WebRoutes.java —— 方法引用,重命名会编译失败
@Component
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/posts/{id}", PostController::show).name("posts.show");
        r.get("/posts", PostController::index).name("posts.index");
    }
}
```

```java
// app/models/Post.java
@Model
@Fillable({"title", "body"})
public class Post extends PostBase {
    @Id public Long id;
    public String title;
    public String body;
    public Instant createdAt;
}
```

## 从这里开始

<div class="grid cards" markdown>

- **[30 分钟快速开始](quickstart.md)** — 跑起来,再建一个自己的项目
- **[安装](installation.md)** — JDK、Maven、CLI、从源码构建
- **[认证](authentication.md)** — `jaravel install:auth`
- **[从 Laravel 迁移](migrate-from-laravel.md)** — 概念对照与差异
- **[从 Spring Boot 迁移](migrate-from-spring-boot.md)** — 渐进采用

</div>

## 当前状态

**v1.0 功能面完成,正式发布待执行。** 默认全量 **2702 条测试**全绿;
parity 最差 **1.29 / 1.30**;示例:`todo` / `blog` / `auth` / `queue` /
`api-only` / `multi-tenant`。

详细结果与发布待办见 [V1.0-RESULT.md](https://github.com/MutouLee/Jaravel/blob/main/V1.0-RESULT.md)。

## 设计原则

1. **编译期优先** — 路由、视图参数、模型字段都尽量在编译期检查;
2. **零运行时反射优先** — 能由 APT 生成的,不在运行时 `Class.forName`;
3. **每个抽象都有逃生口** — 换掉视图引擎、直接用 Spring Bean、写裸 SQL
   都要有文档化的途径;
4. **不懂时给下一步** — 错误消息里写清楚「为什么」和「怎么修」。
