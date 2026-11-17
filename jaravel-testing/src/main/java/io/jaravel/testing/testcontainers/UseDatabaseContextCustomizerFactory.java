package io.jaravel.testing.testcontainers;

import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

import io.jaravel.testing.UseDatabase;

/**
 * 把 {@link UseDatabase} 变成 Spring 测试上下文的属性(docs/14 §2.2)。
 *
 * <p>用 {@code ContextCustomizerFactory} 而不是 JUnit 扩展:
 * 它能在上下文刷新**之前**拿到测试类与环境,并且参与 Spring 的上下文缓存键
 * (换数据库方言会得到新上下文,而不是复用旧的数据源)。
 *
 * <p>注册在 {@code META-INF/spring.factories} 的
 * {@code org.springframework.test.context.ContextCustomizerFactory} 下。
 */
public final class UseDatabaseContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        UseDatabase annotation = find(testClass);
        if (annotation == null) return null;
        return new TestDatabaseCustomizer(annotation.value(), annotation.image());
    }

    /** 支持超类继承与 {@code @Nested} 外层类上的注解 */
    static UseDatabase find(Class<?> testClass) {
        for (Class<?> type = testClass; type != null; type = type.getEnclosingClass()) {
            UseDatabase found = AnnotatedElementUtils.findMergedAnnotation(type, UseDatabase.class);
            if (found != null) return found;
        }
        return null;
    }
}
