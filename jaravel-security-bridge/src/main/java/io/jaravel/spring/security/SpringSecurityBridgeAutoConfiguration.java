package io.jaravel.spring.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.spring.GuardDriver;

/**
 * 引入本模块即注册 {@code driver: spring-security}。
 *
 * <pre>{@code
 * # config/auth.yml
 * auth:
 *   guards:
 *     web:
 *       driver: spring-security
 *       provider: users
 *       username-column: email     # 可选,默认 email
 *       resolve: lookup            # lookup(默认) | wrap
 * }</pre>
 *
 * <p>不引入本模块时,同样的配置会得到一条带修复步骤的启动错误
 * (见 {@code JaravelAuthAutoConfiguration.unknownDriver})——
 * 而不是一个 ClassNotFoundException。
 */
@AutoConfiguration
public class SpringSecurityBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "springSecurityGuardDriver")
    public GuardDriver springSecurityGuardDriver(
            ObjectProvider<AuthenticationManager> authenticationManager) {

        return GuardDriver.named(SecurityBridge.DRIVER, (guardName, provider, config) -> {
            String path = "auth.guards." + guardName;
            String column = config.string(path + ".username-column", "email");
            String mode = config.string(path + ".resolve", "lookup");

            var resolver = switch (mode) {
                case "lookup" -> SpringSecurityGuard.PrincipalResolver.byUsername(column);
                case "wrap" -> SpringSecurityGuard.PrincipalResolver.wrapping();
                default -> throw new IllegalStateException("""
                        %s.resolve = "%s" 无效。
                          · lookup(默认)—— 拿 Spring 的用户名去 Jaravel provider 查库,
                            Auth.user(User.class) 返回真正的模型,Policy 可以照常用;
                          · wrap —— 不查库,直接包装 Spring 的 principal。
                            适合纯无状态服务,但 Policy 拿不到模型。"""
                        .formatted(path, mode));
            };

            // AuthenticationManager 是可选的:只读 Spring 认证结果的场景
            // 不需要它(认证由 Filter 链完成)。有它时 Guard.validate()
            // 会交给 Spring —— 企业环境里「密码对不对」的答案常在 LDAP
            return new SpringSecurityGuard(guardName, provider, resolver,
                                           authenticationManager.getIfAvailable());
        });
    }

    /**
     * 便于在配置类里拿到桥接的 driver 名做断言 ——
     * 也让 {@link ConfigRepository} 的 import 不只出现在 lambda 的类型推断里。
     */
    public static String driverName() {
        return SecurityBridge.DRIVER;
    }
}
