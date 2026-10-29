package io.jaravel;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.jaravel.core.config.DotEnv;

/**
 * 应用入口。用户的 main 只需一行:
 *
 * <pre>{@code
 * public class Application {
 *     public static void main(String[] args) {
 *         Jaravel.run(Application.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>内部就是 {@code SpringApplication.run},但用户看不到 Spring ——
 * 这是 ADR-001「向上提供 Laravel 风格 API」的入口体现。
 */
public final class Jaravel {

    private Jaravel() {}

    public static ConfigurableApplicationContext run(Class<?> mainClass, String... args) {
        requireNotDefaultPackage(mainClass);
        loadDotEnv();
        return new SpringApplicationBuilder(mainClass,
                        io.jaravel.spring.JaravelComponentScan.class)
                .properties(defaults())
                .run(args);
    }

    /**
     * 默认包里的入口类是个地雷:{@code @SpringBootApplication} 会从默认包开始
     * 组件扫描 —— 即扫描**整个 classpath**,连 Spring 自己的 jar 一起扫,
     * 很快就会炸在某个条件类上,而报错信息与根因相距很远。
     *
     * <p>因此直接拦住,并给出明确的修复方式。
     */
    private static void requireNotDefaultPackage(Class<?> mainClass) {
        if (mainClass.getPackage() == null || mainClass.getPackageName().isEmpty()) {
            throw new IllegalStateException("""
                    入口类 %s 位于**默认包**,这会让 Spring 的组件扫描遍历整个 classpath 并启动失败。
                      修复:把它移到一个包里,例如
                        src/main/java/app/Application.java   →   package app;
                      Jaravel 会自动扫描 app / routes / database 这三个约定包,无需写 scanBasePackages。\
                    """.formatted(mainClass.getSimpleName()));
        }
    }

    /**
     * .env 的加载必须在 SpringApplication 启动**之前** ——
     * 因为 spring.datasource.url 这类配置可能就写在 .env 里。
     *
     * <p>写进系统属性而非 Spring 的 PropertySource,是为了让它对
     * Spring 启动过程的每个阶段都可见(包括 EnvironmentPostProcessor 之前)。
     * 已存在的系统属性不覆盖 —— 命令行 -D 优先级最高。
     */
    private static void loadDotEnv() {
        String profile = System.getProperty("spring.profiles.active",
                                            System.getenv("SPRING_PROFILES_ACTIVE"));
        Map<String, String> env = DotEnv.load(Path.of("."), profile);
        env.forEach((k, v) -> {
            if (System.getProperty(k) == null) System.setProperty(k, v);
        });
    }

    private static Map<String, Object> defaults() {
        var p = new java.util.LinkedHashMap<String, Object>();

        // ADR-007:每请求一个虚拟线程,是请求作用域与门面的前提
        p.put("spring.threads.virtual.enabled", "true");

        // Servlet 容器默认用 ISO-8859-1 解 form 参数,中文直接乱码。
        // force 让 CharacterEncodingFilter 无条件按 UTF-8 解码,
        // 不依赖客户端是否在 Content-Type 里带 charset。
        // 这是框架该替用户做对的事,不该让每个人自己踩一遍。
        p.put("server.servlet.encoding.charset", "UTF-8");
        p.put("server.servlet.encoding.force", "true");

        p.put("spring.main.banner-mode", "off");
        p.put("spring.jackson.default-property-inclusion", "non_null");

        // 未匹配的请求要抛异常,才能走到 Jaravel 的 404 处理
        p.put("spring.mvc.throw-exception-if-no-handler-found", "true");
        p.put("spring.web.resources.add-mappings", "false");
        p.put("server.error.whitelabel.enabled", "false");

        return p;
    }
}
