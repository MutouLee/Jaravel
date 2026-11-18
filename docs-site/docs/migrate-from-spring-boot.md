# 从 Spring Boot 迁移

Jaravel 骑在 Spring Boot 之上,所以迁移是**增量**的:不需要一次性重写,
旧 `@RestController`、`@Service`、JPA Repository 都能继续工作。

## 方案

### A. 新功能用 Jaravel,旧代码不动(推荐)

1. 加 `jaravel-spring-boot-starter` 与 `jaravel-bom`;
2. 把 `jaravel-bom` 放在 Spring Boot BOM **之前** import;
3. 用 `RouteDefinition` 注册新路由,控制器可以注入现有 Spring Bean;
4. 新模型用 Eloquent,旧模型继续 JPA/MyBatis;
5. 逐步迁移页面和接口。

### B. 全量迁移

按路由 → 控制器 → 模型 → 数据访问 → 模板/前端 → 测试 的顺序整体替换。
适合内部工具/中后台,不建议大型核心系统一次性做。

## 概念对照

| Spring Boot | Jaravel |
|---|---|
| `@RestController` + `@GetMapping` | `RouteDefinition` + 控制器方法引用 |
| `@RequestParam` / `@PathVariable` | 参数名绑定(可加 `whereNumber`) |
| `@Valid` + Bean Validation | `FormRequest` / `Validator` |
| `@ControllerAdvice` | 内置异常处理 + Problem JSON |
| Spring Data JPA | Eloquent(`@Model`) |
| Flyway/Liquibase | `Schema` DSL + `Migrator` |
| Thymeleaf | jblade |
| Spring Security | `jaravel-auth` 或 `jaravel-security-bridge` |
| `@Scheduled` | `Schedule` DSL |
| `@Async` / RabbitMQ | `ShouldQueue` + queue 驱动 |
| `TestRestTemplate` / MockMvc | `FeatureTest` |

## 共存要点

- **不要重复扫描**:Jaravel 只扫描 `app` / `routes` / `database` 三个约定包;
  旧代码放在自己的包,Spring 仍然会扫;
- **事务**:Eloquent 用 `DB.transaction`/`Connection`,与 Spring `@Transactional`
  可以共存,但不要在同一业务里混用两个事务管理器;
- **安全**:同一应用里 Spring Security 与会话 guard 同时启用时,用
  `jaravel-security-bridge` 让两者共享身份;
- **配置**:`application.yml` 继续有效;Jaravel 的 `config/*.yml` 是额外一层;
- **反向代理**:用 `server.forward-headers-strategy`,Jaravel 不解析 XFF;
- **模板**:Jaravel 打包时需要 `jaravel-maven-plugin:compile-views`。

## 常见坑

| 症状 | 原因 |
|---|---|
| 路由 404 | 控制器所在包不在 `app` 下;`RouteDefinition` 没加 `@Component` |
| `middleware("auth")` 不生效 | 路由没挂 `web` 组(没有 StartSession) |
| 表单中文乱码 | 用 Jaravel 的 `web` 组(UTF-8 已强制) |
| 多实例随机掉登录 | `session.driver` 还是 file;换 redis/database |
| 打包后模板报「必须预编译」 | 没接 `compile-views` 插件 |
| 启动报 APP_KEY | 配 `APP_KEY`;`jaravel key:generate --show` |
