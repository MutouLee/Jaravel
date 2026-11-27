package net.dobeen.jaravel.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用 Testcontainers 起一个真实数据库,并自动注入 Spring 数据源(docs/14 §2.2)。
 *
 * <pre>{@code
 * @UseDatabase(UseDatabase.Kind.MYSQL)
 * class OrderRepositoryTest extends FeatureTest { … }
 * }</pre>
 *
 * <p>做的事:
 * <ul>
 *   <li>启动(或复用)本 JVM 内同 kind/image 的容器;</li>
 *   <li>把 {@code spring.datasource.url/username/password/driver-class-name}
 *       加在配置数据之前;</li>
 *   <li>把 {@code jaravel.database.default} 改成对应方言 —— 只换 URL 不换
 *       Grammar 会得到「MySQL 上跑 SQLite 语法」;</li>
 *   <li>打开 {@code jaravel.migrate.on-boot},让空库在上下文启动时迁移。</li>
 * </ul>
 *
 * <p><b>依赖</b>:Testcontainers 在 {@code jaravel-testing} 里是 optional,
 * 应用需要在自己的 {@code test} 作用域声明对应模块与 JDBC 驱动:
 *
 * <pre>{@code
 * <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><scope>test</scope></dependency>
 * <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>test</scope></dependency>
 * }</pre>
 *
 * <p><b>Docker 不可用时失败而不是跳过</b>:测试显式声明了要真实数据库,
 * 静默降级到 SQLite/内存会让「方言差异」重新漏过去。确实需要跳过的,
 * 用 JUnit 的 {@code @EnabledIf("dockerAvailable")} 在类级别先挡住。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface UseDatabase {

    Kind value();

    /** 自定义镜像;留空用默认(mysql:8.0 / postgres:16-alpine / mariadb:11.4) */
    String image() default "";

    enum Kind {
        MYSQL("mysql", "mysql:8.0"),
        POSTGRESQL("postgres", "postgres:16-alpine"),
        MARIADB("mariadb", "mariadb:11.4");

        private final String configName;
        private final String defaultImage;

        Kind(String configName, String defaultImage) {
            this.configName = configName;
            this.defaultImage = defaultImage;
        }

        /** {@code database.default} / Grammar 认的名字 */
        public String configName() {
            return configName;
        }

        public String defaultImage() {
            return defaultImage;
        }
    }
}
