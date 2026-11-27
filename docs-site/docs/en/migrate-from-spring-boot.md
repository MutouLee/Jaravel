# Migrating from Spring Boot

Jaravel sits on top of Spring Boot, so migration is **incremental**: there is
no need for a big-bang rewrite. Existing `@RestController`s, `@Service`s and
JPA repositories keep working.

## Approaches

### A. New features in Jaravel, old code untouched (recommended)

1. Add `jaravel-spring-boot-starter` and `jaravel-bom`;
2. Import `jaravel-bom` **before** the Spring Boot BOM;
3. Register new routes with `RouteDefinition`; controllers can inject existing
   Spring beans;
4. Use Eloquent for new models and keep JPA/MyBatis for old ones;
5. Migrate pages and endpoints gradually.

### B. Full migration

Replace everything in this order: routes → controllers → models → data access →
templates/frontend → tests. Suitable for internal tools and back-office apps;
not recommended as a single step for large core systems.

## Concept mapping

| Spring Boot | Jaravel |
|---|---|
| `@RestController` + `@GetMapping` | `RouteDefinition` + controller method references |
| `@RequestParam` / `@PathVariable` | binding by parameter name (optionally with `whereNumber`) |
| `@Valid` + Bean Validation | `FormRequest` / `Validator` |
| `@ControllerAdvice` | built-in exception handling + Problem JSON |
| Spring Data JPA | Eloquent (`@Model`) |
| Flyway/Liquibase | `Schema` DSL + `Migrator` |
| Thymeleaf | jblade |
| Spring Security | `jaravel-auth`, or `jaravel-security-bridge` |
| `@Scheduled` | `Schedule` DSL |
| `@Async` / RabbitMQ | `ShouldQueue` + queue drivers |
| `TestRestTemplate` / MockMvc | `FeatureTest` |

## Coexistence notes

- **Do not double-scan**: Jaravel only scans the three conventional packages
  `app` / `routes` / `database`; keep old code in its own packages, where
  Spring still scans it;
- **Transactions**: Eloquent uses `DB.transaction`/`Connection` and can coexist
  with Spring `@Transactional`, but do not mix the two transaction managers in
  one business operation;
- **Security**: when Spring Security and a session guard are both enabled in
  one application, use `jaravel-security-bridge` so they share identity;
- **Configuration**: `application.yml` keeps working; Jaravel's `config/*.yml`
  is an additional layer;
- **Reverse proxy**: use `server.forward-headers-strategy`; Jaravel does not
  parse XFF itself;
- **Templates**: packaging a Jaravel app requires
  `jaravel-maven-plugin:compile-views`.

## Common pitfalls

| Symptom | Cause |
|---|---|
| 404 on a route | The controller's package is not under `app`; the `RouteDefinition` lacks `@Component` |
| `middleware("auth")` has no effect | The route is not in the `web` group (no `StartSession`) |
| Garbled non-ASCII form data | Use Jaravel's `web` group (UTF-8 is enforced) |
| Random logouts with multiple instances | `session.driver` is still `file`; switch to redis/database |
| "Templates must be precompiled" after packaging | `compile-views` is not wired in |
| Startup fails with `APP_KEY` | Set `APP_KEY`; `jaravel key:generate --show` |
