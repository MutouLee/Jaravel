# 安装

## 要求

| 项 | 版本 |
|---|---|
| JDK | 21+(推荐 Temurin / GraalVM 21) |
| Maven | 3.9+ |
| 数据库 | 开发默认 SQLite;生产支持 PostgreSQL / MySQL |
| Docker | 仅测试数据库容器 / native 构建需要 |

## 使用 CLI

发布版会提供单文件二进制:

```bash
jaravel --version
jaravel new myapp
```

各平台安装方式在 v1.0 发布页提供。开发期从源码构建:

```bash
git clone https://github.com/MutouLee/Jaravel.git
cd Jaravel
mvn -DskipTests install
mvn -pl jaravel-cli -am package
# 产物 jaravel-cli/target/jaravel-cli-*.jar;依赖在 local repo
```

Linux native(可选,~24MB,启动 <100ms):

```bash
mvn -pl jaravel-cli dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/native-libs
docker run --rm --entrypoint sh \
  -v "$PWD":/work -w /work/jaravel-cli \
  ghcr.io/graalvm/native-image-community:21 \
  -c 'CP="target/jaravel-cli-0.1.0-SNAPSHOT.jar:$(find target/native-libs -name "*.jar" | tr "\n" ":")"; \
      native-image -cp "$CP" -o target/jaravel --no-fallback \
        --initialize-at-build-time=net.dobeen.jaravel.cli -J-Xmx4g net.dobeen.jaravel.cli.Jaravel'
```

## 在已有 Maven 项目中使用

只需要一个 starter:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>net.dobeen.jaravel</groupId>
      <artifactId>jaravel-bom</artifactId>
      <version>${jaravel.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>net.dobeen.jaravel</groupId>
    <artifactId>jaravel-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

如果同时使用 Spring Boot BOM,把 `jaravel-bom` 放在它**之前** import ——
它带同一 patch 线的安全覆盖。

## 从源码构建框架

```bash
mvn test
mvn -Psecurity -DskipTests validate    # 依赖收敛
```

示例:

```bash
mvn -pl examples/todo spring-boot:run
mvn -pl examples/blog spring-boot:run
mvn -pl examples/auth spring-boot:run
mvn -pl examples/api-only spring-boot:run
mvn -pl examples/multi-tenant spring-boot:run
```

## 下一步

- [快速开始](quickstart.md)
- [认证](authentication.md)
- [从 Laravel 迁移](migrate-from-laravel.md)
- [从 Spring Boot 迁移](migrate-from-spring-boot.md)
