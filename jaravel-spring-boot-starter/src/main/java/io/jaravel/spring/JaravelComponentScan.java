package io.jaravel.spring;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 扫描 Jaravel 约定的应用包(ADR-009 方案 C)。
 *
 * <pre>
 * src/main/java/app/...        控制器 / 模型 / 服务
 * src/main/java/routes/...     路由定义
 * src/main/java/database/...   迁移 / 填充器 / 工厂
 * </pre>
 *
 * <p><b>为什么需要它:</b>Laravel 式的平级布局让入口类没有自然的归属包。
 * 若把入口类放在**默认包**,{@code @SpringBootApplication} 会从默认包开始扫描
 * ——也就是扫描整个 classpath,连 Spring 自己的 jar 一起扫,立刻炸在
 * 某个条件类上(实测:{@code io/r2dbc/spi/ValidationDepth not found})。
 *
 * <p>把约定包的扫描放在框架侧,用户就不必写 {@code scanBasePackages},
 * 入口类也可以安心放在 {@code app} 包下。不存在的包被扫描是无害的。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"app", "routes", "database"})
public class JaravelComponentScan {
}
