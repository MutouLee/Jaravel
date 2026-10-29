package io.jaravel.spring;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;
import io.jaravel.migration.Seeder;

/**
 * 发现并执行迁移。
 *
 * <h2>为什么用包扫描而不是让用户给每个迁移加注解</h2>
 * <p>Laravel 的迁移是纯 PHP 类,不需要任何注册动作。要在 Java 里达到同样的
 * 体验,只有两条路:让用户写 {@code @Component}(每个迁移多一行,而且把
 * Spring 带进了本该与框架无关的文件),或者扫描一个**约定包**。
 *
 * <p>选了后者:只扫 {@code database.migrations} 这一个包,范围极小,
 * 不存在「扫整个 classpath」的性能问题,用户的迁移文件里一个框架注解都没有。
 *
 * <h2>默认不自动执行</h2>
 * <p>{@code jaravel.migrate.on-boot} 默认 {@code false}。生产环境里
 * 「启动即改库结构」是危险的 —— 多实例滚动发布时会有两个进程同时改表。
 * 本地开发打开它很方便,所以 {@code app.env=local} 时默认为开。
 */
public class MigrationRunner {

    /** ADR-009 的约定布局 */
    private static final String MIGRATION_PACKAGE = "database.migrations";
    private static final String SEEDER_PACKAGE = "database.seeders";

    private final Connection connection;
    private final ConfigRepository config;

    public MigrationRunner(Connection connection, ConfigRepository config) {
        this.connection = connection;
        this.config = config;
    }

    /** 由 starter 在容器就绪后调用 */
    public void runIfEnabled() {
        if (!enabled()) return;

        List<Migration> migrations = discover();
        if (migrations.isEmpty()) return;

        var migrator = new Migrator(connection);
        var options = new Migrator.Options(false, allowDrift(), false);

        try {
            migrator.migrate(migrations, options);
        } finally {
            migrator.output().forEach(line -> System.out.println("[jaravel migrate] " + line));
        }
    }

    private boolean enabled() {
        // 本地开发默认开,其它环境必须显式打开
        boolean isLocal = "local".equalsIgnoreCase(config.string("app.env", "production"));
        return config.bool("jaravel.migrate.on-boot", isLocal);
    }

    /**
     * 跑种子数据。
     *
     * <p>默认关闭,**且只在目标表为空时执行** —— 「每次启动都塞一批假数据」
     * 会让本地库在几天后变成垃圾堆。Laravel 用 {@code artisan db:seed} 手动触发,
     * Jaravel 的 CLI 排期在 v0.5,在那之前用这个开关。
     */
    public void seedIfEnabled() {
        if (!config.bool("jaravel.seed.on-boot", false)) return;

        List<Seeder> seeders = discoverSeeders();
        if (seeders.isEmpty()) return;

        var runner = new Seeder.Runner();
        for (Seeder seeder : seeders) {
            runner.run(seeder);
            System.out.println("[jaravel seed] 已执行 " + seeder.getClass().getSimpleName());
        }
    }

    List<Seeder> discoverSeeders() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(Seeder.class));

        var found = new ArrayList<Seeder>();
        for (BeanDefinition definition : provider.findCandidateComponents(SEEDER_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) continue;
            found.add((Seeder) instantiateAny(className, "Seeder"));
        }
        return found;
    }

    private Object instantiateAny(String className, String kind) {
        try {
            Class<?> type = Class.forName(className);
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法实例化 " + kind + " " + className + ":" + e.getMessage()
                  + "\n  需要一个可访问的无参构造函数。", e);
        }
    }

    private boolean allowDrift() {
        return config.bool("jaravel.migrate.allow-drift", false);
    }

    /** 扫描约定包,实例化全部 {@link Migration} 实现 */
    List<Migration> discover() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(Migration.class));

        var found = new ArrayList<Migration>();
        for (BeanDefinition definition : provider.findCandidateComponents(MIGRATION_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) continue;
            found.add(instantiate(className));
        }
        return found;
    }

    private Migration instantiate(String className) {
        try {
            return (Migration) instantiateAny(className, "Migration");
        } catch (RuntimeException e) {
            throw new IllegalStateException("""
                    无法实例化迁移 %s:%s
                      迁移类需要一个可访问的无参构造函数。
                      约定:database/migrations/m2026_10_29_143500/CreateTodosTable.java
                      (时间戳在**包名**上 —— Java 不允许类名以数字开头,
                       见 Migration 的类注释)"""
                    .formatted(className, e.getMessage()), e);
        }
    }
}
