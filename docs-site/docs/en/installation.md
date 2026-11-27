# Installation

## Requirements

| Item | Version |
|---|---|
| JDK | 21+ (Temurin / GraalVM 21 recommended) |
| Maven | 3.9+ |
| Database | SQLite by default in development; PostgreSQL / MySQL in production |
| Docker | Only for test database containers / native builds |

## Using the CLI

The release ships a single-file binary:

```bash
jaravel --version
jaravel new myapp
```

Per-platform installation options are published on the v1.0 release page.
During development, build it from source:

```bash
git clone https://github.com/MutouLee/Jaravel.git
cd Jaravel
mvn -DskipTests install
mvn -pl jaravel-cli -am package
# artifact: jaravel-cli/target/jaravel-cli-*.jar; dependencies live in the local repo
```

Linux native (optional, ~24MB, starts in <100ms):

```bash
mvn -pl jaravel-cli dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/native-libs
docker run --rm --entrypoint sh \
  -v "$PWD":/work -w /work/jaravel-cli \
  ghcr.io/graalvm/native-image-community:21 \
  -c 'CP="target/jaravel-cli-0.1.0-SNAPSHOT.jar:$(find target/native-libs -name "*.jar" | tr "\n" ":")"; \
      native-image -cp "$CP" -o target/jaravel --no-fallback \
        --initialize-at-build-time=io.jaravel.cli -J-Xmx4g io.jaravel.cli.Jaravel'
```

## Using it in an existing Maven project

One starter is all you need:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.jaravel</groupId>
      <artifactId>jaravel-bom</artifactId>
      <version>${jaravel.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.jaravel</groupId>
    <artifactId>jaravel-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

If you also use the Spring Boot BOM, import `jaravel-bom` **before** it —
it carries security overrides for the same patch line.

## Building the framework from source

```bash
mvn test
mvn -Psecurity -DskipTests validate    # dependency convergence
```

Examples:

```bash
mvn -pl examples/todo spring-boot:run
mvn -pl examples/blog spring-boot:run
mvn -pl examples/auth spring-boot:run
mvn -pl examples/api-only spring-boot:run
mvn -pl examples/multi-tenant spring-boot:run
```

## Next

- [Quickstart](quickstart.md)
- [Authentication](authentication.md)
- [Migrating from Laravel](migrate-from-laravel.md)
- [Migrating from Spring Boot](migrate-from-spring-boot.md)
