-- v0.1 还没有迁移模块(v0.2),先用 Spring Boot 的 schema 初始化。
-- v0.2 会替换为 database/migrations/CreateTodosTable.java
create table if not exists todos (
  id         integer primary key autoincrement,
  title      text    not null,
  done       integer not null default 0,
  created_at text
);
