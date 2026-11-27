# Quickstart

## Requirements

- JDK 21+
- Maven 3.9+
- Docker only for Testcontainers / native CLI builds

## Run the Todo example

```bash
git clone https://github.com/MutouLee/Jaravel.git
cd Jaravel
mvn -q -DskipTests install
mvn -pl examples/todo spring-boot:run
```

Open <http://localhost:8000>.

## Create a project

```bash
jaravel new myapp
cd myapp
mvn spring-boot:run
```

`--preset=api` generates a JSON-only app; `--preset=minimal` is the smallest
skeleton.

## Add authentication

```bash
jaravel install:auth
mvn spring-boot:run
```

Open <http://localhost:8000/login>, register, log out, log back in, and try
"forgot password". The default mailer is `log`, so the reset link is written
to the application log.

Generated files include the `User` model, the auth migration, the auth
controller, routes, jblade views, and `config/auth.yml`.

## Tests

```bash
mvn test
```

A test is just:

```java
class PostTest extends FeatureTest {
    @Test void creates_a_post() {
        post("/posts", Map.of("title", "First", "body", "Long enough body"))
            .assertRedirect("/posts");
    }
}
```

## Deploy

```bash
mvn package
java -jar target/myapp-0.1.0-SNAPSHOT.jar
```

jblade templates are precompiled by `jaravel-maven-plugin` during
`process-classes`.

## More

- [Installation](installation.md)
- [Authentication](authentication.md)
- [Database and Eloquent](database.md)
- [Testing](testing.md)
- [Deployment](deployment.md)
- [API reference](api-reference.md)
