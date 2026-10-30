package io.jaravel.spring;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

import io.jaravel.core.config.ConfigRepository;

/**
 * 文件上传的限制(docs/11 §3.3)。
 *
 * <pre>{@code
 * # config/uploads.yml
 * uploads:
 *   max-file-size: 10MB      # 单个文件
 *   max-request-size: 25MB   # 整个请求(多文件表单的总和)
 * }</pre>
 *
 * <h2>为什么限制在这一层而不在 {@code UploadedFile} 里</h2>
 * <p>一个「读完再检查大小」的实现保护不了任何东西 —— 等它能
 * 检查的时候,那 2 GB 已经在内存或磁盘上了。
 *
 * <p>{@code MultipartConfigElement} 是容器在**读流的过程中**
 * 执行的限制:超过就中断,不会先吃满再报错。
 * 所以这是唯一正确的位置。
 *
 * <h2>为什么覆盖 Spring Boot 自己的那个 bean</h2>
 * <p>Spring Boot 的 {@code MultipartAutoConfiguration} 提供一个
 * {@code @ConditionalOnMissingBean} 的 {@code MultipartConfigElement},
 * 默认 1 MB —— 一个头像上传在默认配置下就会失败。
 *
 * <p>更重要的是**配置的来源**:Jaravel 的配置在
 * {@code config/*.yml},它不进 Spring 的 Environment
 * (见 {@code JaravelAutoConfiguration} 的说明),所以
 * {@code spring.servlet.multipart.max-file-size} 读不到
 * Jaravel 的那份配置。一个把限制写在 {@code config/uploads.yml} 里
 * 却不生效的应用会非常难排查。
 *
 * <p>所以这里自己造一个,从 Jaravel 的配置读。
 *
 * <h2>⚠ 仍然尊重 {@code application.yml}</h2>
 * <p>{@code @ConditionalOnMissingBean} 保证一个显式定义了
 * {@code MultipartConfigElement} 的应用不受影响。
 *
 * <p>但一个只在 {@code application.yml} 里写了
 * {@code spring.servlet.multipart.max-file-size} 的应用**会被这里覆盖** ——
 * 因为 Spring Boot 的那个 bean 根本不会被创建。
 *
 * <p>这个取舍是刻意的(Jaravel 的配置是单一来源),代价是
 * 一个从 Spring Boot 迁过来的应用要把那两行搬到
 * {@code config/uploads.yml}。为此这里在两边都配了的情况下
 * 会**显式报错**,而不是静默地让一边失效。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
public class JaravelUploadsAutoConfiguration {

    /** 单个文件的默认上限 —— 比 Spring Boot 的 1 MB 宽松,够一张照片 */
    static final String DEFAULT_MAX_FILE_SIZE = "10MB";

    /**
     * 整个请求的默认上限。
     *
     * <p>比单文件上限大但不是它的整数倍:一个多文件表单的总量
     * 应该被单独限制(10 个 10 MB 的文件 = 100 MB),
     * 而 25 MB 对「一次传几张照片」够用。
     */
    static final String DEFAULT_MAX_REQUEST_SIZE = "25MB";

    @Bean
    @ConditionalOnMissingBean
    public MultipartConfigElement multipartConfigElement(
            ConfigRepository config,
            org.springframework.core.env.Environment environment) {

        rejectConflictingSpringProperty(environment, "max-file-size", config);
        rejectConflictingSpringProperty(environment, "max-request-size", config);

        var factory = new org.springframework.boot.web.servlet.MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.parse(
                config.string("uploads.max-file-size", DEFAULT_MAX_FILE_SIZE)));
        factory.setMaxRequestSize(DataSize.parse(
                config.string("uploads.max-request-size", DEFAULT_MAX_REQUEST_SIZE)));

        // 阈值 0 = 任何大小都先落临时文件,不在内存里攒。
        //
        // 这与 UploadedFile「内容在内存里」看似矛盾,其实不是:
        // 容器落盘 → 适配器读成 byte[] → 立刻删临时文件。
        // 中间多一次磁盘往返,换来的是容器在读流阶段
        // 不会为一个超限的请求先分配内存
        factory.setFileSizeThreshold(DataSize.ofBytes(0));

        return factory.createMultipartConfig();
    }

    /**
     * 两边都配了就报错。
     *
     * <p>这是「响亮地失败优于静默降级」的一次具体应用:
     * 一个在 {@code application.yml} 里把上限设成 100 MB 的应用
     * 会得到 Jaravel 的 10 MB 默认值,而症状是
     * 「大文件上传 413,但配置里明明写了 100MB」。
     */
    private void rejectConflictingSpringProperty(
            org.springframework.core.env.Environment environment,
            String key, ConfigRepository config) {

        String springKey = "spring.servlet.multipart." + key;
        if (environment.getProperty(springKey) == null) return;

        // Jaravel 这边没配的话不算冲突 —— 那只是一个
        // 「用 Spring 的方式配置」的应用,直接采纳它的值更友好。
        // 但这里采纳不了(值已经在上面被读过了),所以仍然要说清
        if (config.string("uploads." + key, null) == null) {
            throw new IllegalStateException("""
                    %s 配在了 application.yml 里,但 Jaravel 不读它。

                      Jaravel 的上传限制来自 config/uploads.yml:
                        uploads:
                          %s: %s

                      把那一行搬过去。两处都留会让其中一处静默失效,
                      所以这里直接报错而不是挑一个用。"""
                    .formatted(springKey, key, environment.getProperty(springKey)));
        }

        throw new IllegalStateException("""
                %s 同时配在了两处:
                  application.yml:        %s
                  config/uploads.yml:     %s

                  Jaravel 只读后者 —— 前者会静默失效。删掉它。"""
                .formatted(springKey, environment.getProperty(springKey),
                        config.string("uploads." + key, null)));
    }
}
