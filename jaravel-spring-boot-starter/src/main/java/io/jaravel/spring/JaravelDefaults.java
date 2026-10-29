package io.jaravel.spring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Jaravel 要求的那组 Spring 属性。
 *
 * <h2>为什么是 EnvironmentPostProcessor 而不是写在 Jaravel.run 里</h2>
 * <p>原先这些属性由 {@code Jaravel.run} 通过 {@code SpringApplicationBuilder}
 * 注入。问题是 {@code @SpringBootTest} **不走** {@code Jaravel.run} ——
 * 它自己构造 SpringApplication。于是用户写集成测试时会遇到:
 *
 * <ul>
 *   <li>404 不抛异常,走不到 Jaravel 的异常处理,错误格式与生产不一致;</li>
 *   <li>form 参数按 ISO-8859-1 解码 —— 中文在测试里乱码,在生产里正常;</li>
 *   <li>没有虚拟线程,请求作用域的行为与生产不同。</li>
 * </ul>
 *
 * <p>「生产正常但测试里表现不同」是最糟的一类不一致:它让测试失去意义,
 * 而排查方向会完全错。放进 EnvironmentPostProcessor 之后,
 * 任何方式启动的 Spring 都会拿到同一组属性。
 *
 * <h2>优先级最低</h2>
 * <p>用 {@code addLast} 加在属性源的末尾 —— 用户的 application.yml、
 * 命令行 {@code -D}、测试的 {@code @SpringBootTest(properties = ...)}
 * 全部优先于它。这些是**默认值**,不是强制值。
 */
public class JaravelDefaults implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "jaravelDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) return;

        environment.getPropertySources()
                .addLast(new MapPropertySource(SOURCE_NAME, defaults()));
    }

    static Map<String, Object> defaults() {
        var p = new LinkedHashMap<String, Object>();

        // ADR-007:每请求一个虚拟线程,是请求作用域与门面的前提
        p.put("spring.threads.virtual.enabled", "true");

        // Servlet 容器默认用 ISO-8859-1 解 form 参数,中文直接乱码。
        // force 让 CharacterEncodingFilter 无条件按 UTF-8 解码,
        // 不依赖客户端是否在 Content-Type 里带 charset
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
