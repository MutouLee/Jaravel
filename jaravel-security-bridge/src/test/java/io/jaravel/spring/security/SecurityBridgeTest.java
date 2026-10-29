package io.jaravel.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import io.jaravel.auth.AuthManager;
import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.Guard;
import io.jaravel.auth.UserProvider;
import io.jaravel.auth.access.Gate;
import io.jaravel.auth.access.GateRegistry;
import io.jaravel.auth.hash.HashManager;

/** Spring Security ↔ Jaravel Auth 双向桥接 */
class SecurityBridgeTest {

    private FakeProvider provider;
    private AuthManager auth;

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();
        auth = new AuthManager().registerProvider(provider);
        SecurityBridge.registerGuard(auth, "web", "users");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        // SecurityContextHolder 是 ThreadLocal,JUnit 复用线程 ——
        // 不清会把上一个测试的登录态带进下一个
        SecurityContextHolder.clearContext();
    }

    private Guard guard() {
        return auth.guard("web");
    }

    // ================= 读取认证结果 =================

    @Nested
    @DisplayName("读取 Spring 的认证结果")
    class Reading {

        @Test
        @DisplayName("已认证 → Auth.user() 拿到 Jaravel 的模型")
        void authenticatedResolvesToModel() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"));

            var user = guard().user();

            assertThat(user).isPresent();
            assertThat(user.get().getAuthIdentifier()).isEqualTo(7L);
            assertThat(guard().check()).isTrue();
        }

        @Test
        @DisplayName("按用户名回查 provider —— Policy 需要真正的模型")
        void resolvesByUsernameFromProvider() {
            provider.add("ann@example.com", 7L);

            // 模拟 Spring 的 Filter 只给了一个用户名字符串
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "ann@example.com", "N/A", List.of()));

            var user = guard().user();

            assertThat(user).isPresent();
            assertThat(user.get().unwrap()).isInstanceOf(FakeUser.class);
            assertThat(provider.credentialLookups.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 匿名认证算游客 —— 它不是 null 且 isAuthenticated() 为 true")
        void anonymousIsGuest() {
            var anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(anonymous);

            // 这两条断言是这个桥接最重要的一条正确性保证:
            // Spring 给游客装的 token 自称已认证,
            // 按 != null 判断会让 middleware("auth") 对所有人放行
            assertThat(anonymous.isAuthenticated())
                    .as("前提:Spring 的匿名 token 确实自称已认证").isTrue();

            assertThat(guard().check()).isFalse();
            assertThat(guard().user()).isEmpty();
            assertThat(guard().guest()).isTrue();
        }

        @Test
        @DisplayName("★ 认证未完成的 token 算游客")
        void unauthenticatedTokenIsGuest() {
            // 构造器版本的 UsernamePasswordAuthenticationToken 是「未认证」的,
            // 专供 AuthenticationManager 的入参使用
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("ann@example.com", "secret"));

            assertThat(guard().check()).isFalse();
        }

        @Test
        @DisplayName("没有 SecurityContext 时算游客,而不是报错")
        void noContextIsGuest() {
            assertThat(guard().check()).isFalse();
            assertThat(guard().user()).isEmpty();
        }

        @Test
        @DisplayName("同一个 Authentication 只回查一次库")
        void resolutionIsCachedPerAuthentication() {
            provider.add("ann@example.com", 7L);
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "ann@example.com", "N/A", List.of()));

            Guard g = guard();
            g.user();
            g.check();
            g.id();

            assertThat(provider.credentialLookups.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("principal 已是 Jaravel 主体时不回查库")
        void alreadyAuthenticatableSkipsLookup() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"));

            assertThat(guard().user()).isPresent();
            assertThat(provider.credentialLookups.get())
                    .as("actingAs 塞进去的就是 Authenticatable,不该再查").isZero();
        }

        @Test
        @DisplayName("★ SSO 用户在本地库里不存在 → 判未登录,不自动建号")
        void unknownSsoUserIsNotAutoProvisioned() {
            // 本地库是空的
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "stranger@partner.com", "N/A", List.of()));

            assertThat(guard().user())
                    .as("JIT provisioning 是业务决定,桥接层不该偷偷建号")
                    .isEmpty();
        }
    }

    // ================= wrapping 策略 =================

    @Nested
    @DisplayName("wrapping 策略(不查库)")
    class Wrapping {

        private Guard wrappingGuard() {
            var manager = new AuthManager().registerProvider(provider);
            SecurityBridge.registerGuard(manager, "api", "users",
                    SpringSecurityGuard.PrincipalResolver.wrapping(), null);
            return manager.guard("api");
        }

        @Test
        @DisplayName("包装成 SpringPrincipal,不查库")
        void wrapsWithoutLookup() {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "sub-123", "N/A",
                            List.of(new SimpleGrantedAuthority("SCOPE_write"))));

            var user = wrappingGuard().user();

            assertThat(user).isPresent();
            assertThat(user.get()).isInstanceOf(SpringPrincipal.class);
            assertThat(user.get().getAuthIdentifier()).isEqualTo("sub-123");
            assertThat(provider.credentialLookups.get()).isZero();
        }

        @Test
        @DisplayName("主键列名是 username 而不是 id")
        void identifierNameIsUsername() {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "sub-123", "N/A", List.of()));

            // 谎称 id 会让任何 where id = ? 的查询拼出错的 SQL
            assertThat(wrappingGuard().user().orElseThrow().getAuthIdentifierName())
                    .isEqualTo("username");
        }

        @Test
        @DisplayName("★ 拿不到模型 —— 没有就说没有,不假装")
        void hasNoModel() {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "sub-123", "N/A", List.of()));

            assertThat(wrappingGuard().user().orElseThrow().as(FakeUser.class))
                    .as("半空的模型会让 Policy 里的字段比较静默出错")
                    .isEmpty();
        }

        @Test
        @DisplayName("密码永远为空 —— SSO 场景本服务从不接触凭据")
        void neverHasPassword() {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "sub-123", "N/A", List.of()));

            assertThat(wrappingGuard().user().orElseThrow().getAuthPassword()).isEmpty();
        }
    }

    // ================= 单一事实来源 =================

    @Nested
    @DisplayName("单一事实来源")
    class SingleSourceOfTruth {

        @Test
        @DisplayName("★ setUser 直接拒绝,而不是写一个会被忽略的值")
        void setUserIsRejected() {
            provider.add("ann@example.com", 7L);

            assertThatThrownBy(() -> guard().setUser(provider.get("ann@example.com")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("SecurityContextHolder")
                    .hasMessageContaining("actingAs");
        }

        @Test
        @DisplayName("Spring 侧清掉认证后,Jaravel 侧立刻变游客")
        void clearingSpringContextLogsOut() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"));

            Guard g = guard();
            assertThat(g.check()).isTrue();

            SecurityBridge.clear();

            // 同一个 guard 实例:缓存不能让已失效的登录态存活
            assertThat(g.check()).isFalse();
            assertThat(g.user()).isEmpty();
        }

        @Test
        @DisplayName("Spring 侧换人后,Jaravel 侧跟着换")
        void switchingSpringUserSwitchesJaravelUser() {
            provider.add("ann@example.com", 7L);
            provider.add("bob@example.com", 9L);

            Guard g = guard();
            SecurityBridge.actingAs(provider.get("ann@example.com"));
            assertThat(g.id()).contains(7L);

            SecurityBridge.actingAs(provider.get("bob@example.com"));
            assertThat(g.id()).as("缓存必须按 Authentication 实例失效").contains(9L);
        }
    }

    // ================= validate =================

    @Nested
    @DisplayName("凭据校验")
    class Validation {

        @Test
        @DisplayName("没有 AuthenticationManager 时退回 Jaravel 的 provider")
        void fallsBackToProvider() {
            provider.add("ann@example.com", 7L);

            assertThat(guard().validate(Map.of(
                    "email", "ann@example.com", "password", "correct"))).isTrue();
            assertThat(guard().validate(Map.of(
                    "email", "ann@example.com", "password", "wrong"))).isFalse();
        }

        @Test
        @DisplayName("★ Spring 用异常表达失败,这里转成 false")
        void springFailureBecomesFalse() {
            AuthenticationManager rejecting = authentication -> {
                throw new BadCredentialsException("nope");
            };
            var manager = new AuthManager().registerProvider(provider);
            SecurityBridge.registerGuard(manager, "web", "users",
                    SpringSecurityGuard.PrincipalResolver.byUsername("email"), rejecting);

            // validate 的契约是返回布尔 —— 调用方是「确认当前密码」之类的
            // 流程,失败是预期分支
            assertThat(manager.guard("web").validate(
                    Map.of("email", "ann@example.com", "password", "x"))).isFalse();
        }

        @Test
        @DisplayName("有 AuthenticationManager 时交给 Spring")
        void delegatesToSpring() {
            AuthenticationManager accepting = authentication ->
                    UsernamePasswordAuthenticationToken.authenticated(
                            authentication.getPrincipal(), "N/A", List.of());
            var manager = new AuthManager().registerProvider(provider);
            SecurityBridge.registerGuard(manager, "web", "users",
                    SpringSecurityGuard.PrincipalResolver.byUsername("email"), accepting);

            // 本地库里根本没有这个人 —— 答案来自 Spring(LDAP / IdP)
            assertThat(manager.guard("web").validate(
                    Map.of("email", "ldap-only@corp.com", "password", "x"))).isTrue();
            assertThat(provider.credentialLookups.get()).isZero();
        }
    }

    // ================= 权限 → Gate =================

    @Nested
    @DisplayName("Spring 权限接进 Gate")
    class Authorities {

        private GateRegistry gate;

        @BeforeEach
        void setUpGate() {
            gate = SecurityBridge.registerAuthorityAbilities(new GateRegistry());
            Gate.use(gate);
        }

        @Test
        @DisplayName("authority 原样比对")
        void authorityExactMatch() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"), "SCOPE_write");

            var user = guard().user().orElseThrow();
            assertThat(Gate.forUser(user).allows("authority", "SCOPE_write")).isTrue();
            assertThat(Gate.forUser(user).allows("authority", "SCOPE_admin")).isFalse();
        }

        @Test
        @DisplayName("★ role 自动补 ROLE_ 前缀 —— 照搬 Spring 的约定")
        void roleGetsPrefix() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"), "ROLE_ADMIN");

            var user = guard().user().orElseThrow();
            assertThat(Gate.forUser(user).allows("role", "ADMIN")).isTrue();
            assertThat(Gate.forUser(user).allows("role", "ROLE_ADMIN"))
                    .as("写全名也该通过").isTrue();
            assertThat(Gate.forUser(user).allows("authority", "ADMIN"))
                    .as("authority 不补前缀,这正是 Spring 的行为").isFalse();
        }

        @Test
        @DisplayName("★ 问别人的权限时不拿当前登录者的答")
        void doesNotLeakCurrentUserAuthorities() {
            provider.add("ann@example.com", 7L);
            provider.add("bob@example.com", 9L);

            SecurityBridge.actingAs(provider.get("ann@example.com"), "ROLE_ADMIN");

            // ann 是管理员,bob 不是。拿 ann 的角色回答关于 bob 的问题
            // 就是一个静默越权
            assertThat(Gate.forUser(provider.get("bob@example.com"))
                    .allows("role", "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("未登录一律拒绝")
        void guestIsDenied() {
            assertThat(Gate.inspect("role", "ADMIN").allowed()).isFalse();
        }

        @Test
        @DisplayName("漏传参数时报错信息带写法")
        void missingArgumentExplains() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"), "ROLE_ADMIN");

            var decision = Gate.forUser(guard().user().orElseThrow()).inspect("role");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("Gate.allows(\"role\", \"ADMIN\")");
        }

        @Test
        @DisplayName("guard 能直接列出当前权限")
        void guardExposesAuthorities() {
            provider.add("ann@example.com", 7L);
            SecurityBridge.actingAs(provider.get("ann@example.com"),
                                    "ROLE_ADMIN", "SCOPE_write");

            assertThat(((SpringSecurityGuard) guard()).authorities())
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "SCOPE_write");
        }

        @Test
        @DisplayName("匿名的 ROLE_ANONYMOUS 不算权限")
        void anonymousAuthoritiesAreNotCounted() {
            SecurityContextHolder.getContext().setAuthentication(
                    new AnonymousAuthenticationToken("key", "anonymousUser",
                            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

            assertThat(((SpringSecurityGuard) guard()).authorities()).isEmpty();
        }
    }

    // ================= 反向:Spring 用 Jaravel 的用户表 =================

    @Nested
    @DisplayName("反向桥接:Spring 用 Jaravel 的用户表与哈希")
    class ReverseDirection {

        @Test
        @DisplayName("UserDetailsService 从 Jaravel provider 取用户")
        void loadsFromJaravelProvider() {
            provider.add("ann@example.com", 7L);
            var service = new JaravelUserDetailsService(provider);

            var details = service.loadUserByUsername("ann@example.com");

            assertThat(details.getUsername()).isEqualTo("ann@example.com");
            assertThat(details.getPassword()).isEqualTo(FakeProvider.HASH);
            assertThat(details.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("★ 默认不给任何权限 —— 不凭空发明 ROLE_USER")
        void noDefaultAuthorities() {
            provider.add("ann@example.com", 7L);

            assertThat(new JaravelUserDetailsService(provider)
                    .loadUserByUsername("ann@example.com").getAuthorities())
                    .as("凭空补 ROLE_USER 会让 hasRole(\"USER\") 静默通过")
                    .isEmpty();
        }

        @Test
        @DisplayName("显式 authorityMapper 可以给权限")
        void explicitAuthorityMapper() {
            provider.add("ann@example.com", 7L);
            var service = new JaravelUserDetailsService(provider, "email",
                    user -> List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                    user -> true);

            assertThat(service.loadUserByUsername("ann@example.com").getAuthorities())
                    .extracting(a -> a.getAuthority())
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("找不到用户抛 UsernameNotFoundException,且消息不回显邮箱")
        void unknownUser() {
            var service = new JaravelUserDetailsService(provider);

            assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessageContaining("users")
                    .as("日志里堆满待枚举的邮箱不是好事")
                    .hasMessageNotContaining("nobody@example.com");
        }

        @Test
        @DisplayName("空用户名也抛 UsernameNotFoundException")
        void blankUsername() {
            var service = new JaravelUserDetailsService(provider);
            assertThatThrownBy(() -> service.loadUserByUsername("  "))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("★ JaravelUserDetails 同时是 Authenticatable —— 省掉回查")
        void userDetailsIsAlsoAuthenticatable() {
            provider.add("ann@example.com", 7L);
            var details = new JaravelUserDetailsService(provider)
                    .loadUserByUsername("ann@example.com");
            provider.credentialLookups.set(0);

            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            details, "N/A", List.of()));

            var user = guard().user();
            assertThat(user).isPresent();
            assertThat(user.get().unwrap()).isInstanceOf(FakeUser.class);
            assertThat(provider.credentialLookups.get())
                    .as("Spring 认证 + Jaravel 用户表 + Jaravel 授权 是最常见组合,"
                      + "这条快路径就是为它准备的")
                    .isZero();
        }

        @Test
        @DisplayName("PasswordEncoder 复用 Jaravel 的哈希配置")
        void passwordEncoderUsesJaravelHashes() {
            var hashes = HashManager.forTests();
            var encoder = new JaravelPasswordEncoder(hashes);

            String encoded = encoder.encode("secret");

            assertThat(encoded).isNotEqualTo("secret");
            assertThat(encoder.matches("secret", encoded)).isTrue();
            assertThat(encoder.matches("wrong", encoded)).isFalse();
            assertThat(hashes.check("secret", encoded))
                    .as("两边必须认同一个哈希").isTrue();
        }

        @Test
        @DisplayName("★ 密码为 null 时返回 false 而不是抛异常 —— SSO 用户没有密码")
        void nullPasswordIsFalse() {
            var encoder = new JaravelPasswordEncoder(HashManager.forTests());

            assertThat(encoder.matches("secret", null)).isFalse();
            assertThat(encoder.matches(null, "whatever")).isFalse();
            assertThat(encoder.upgradeEncoding(null)).isFalse();
        }

        @Test
        @DisplayName("upgradeEncoding 接到 needsRehash —— 不实现等于永不升级")
        void upgradeEncodingDelegates() {
            var hashes = HashManager.forTests();
            var encoder = new JaravelPasswordEncoder(hashes);
            String encoded = encoder.encode("secret");

            assertThat(encoder.upgradeEncoding(encoded))
                    .isEqualTo(hashes.needsRehash(encoded));
        }
    }

    // ================= 装配 =================

    @Nested
    @DisplayName("装配")
    class Wiring {

        @Test
        @DisplayName("driver 常量与 config/auth.yml 里写的一致")
        void driverName() {
            assertThat(SecurityBridge.DRIVER).isEqualTo("spring-security");
        }

        @Test
        @DisplayName("注册的是工厂,每次 build 出新实例")
        void guardIsPerRequest() {
            // 请求作用域外:每次 guard() 都新建 —— 跨请求复用会把
            // 上一个请求的用户带给下一个
            assertThat(auth.guard("web")).isNotSameAs(auth.guard("web"));
        }

        @Test
        @DisplayName("guard 名与 provider 都登记上了")
        void registered() {
            assertThat(auth.registeredGuards()).contains("web");
            assertThat(auth.guard("web").name()).isEqualTo("web");
            assertThat(auth.guard("web").provider()).isSameAs(provider);
        }
    }

    // ================= 测试替身 =================

    record FakeUser(long id, String email, String password) {
    }

    static final class FakeUserPrincipal implements Authenticatable {
        private final FakeUser model;

        FakeUserPrincipal(FakeUser model) {
            this.model = model;
        }

        @Override public Object getAuthIdentifier() { return model.id(); }
        @Override public Optional<String> getAuthPassword() {
            return Optional.ofNullable(model.password());
        }
        @Override public Object unwrap() { return model; }
    }

    /** 最小 UserProvider —— 这里测的是桥接,不是 Eloquent */
    static final class FakeProvider implements UserProvider {

        static final String HASH = "hashed:correct";

        private final List<FakeUserPrincipal> users = new ArrayList<>();
        final AtomicInteger credentialLookups = new AtomicInteger();

        void add(String email, long id) {
            users.add(new FakeUserPrincipal(new FakeUser(id, email, HASH)));
        }

        Authenticatable get(String email) {
            return users.stream()
                    .filter(u -> ((FakeUser) u.unwrap()).email().equals(email))
                    .findFirst().orElseThrow();
        }

        @Override public String name() { return "users"; }

        @Override public Optional<Authenticatable> retrieveById(Object id) {
            return users.stream()
                    .filter(u -> u.getAuthIdentifier().equals(id))
                    .map(Authenticatable.class::cast).findFirst();
        }

        @Override public Optional<Authenticatable> retrieveByToken(Object id, String token) {
            return Optional.empty();
        }

        @Override public void updateRememberToken(Authenticatable user, String token) {
        }

        @Override
        public Optional<Authenticatable> retrieveByCredentials(Map<String, Object> credentials) {
            credentialLookups.incrementAndGet();
            Object email = credentials.get("email");
            if (email == null) return Optional.empty();
            return users.stream()
                    .filter(u -> ((FakeUser) u.unwrap()).email().equals(email))
                    .map(Authenticatable.class::cast).findFirst();
        }

        @Override
        public boolean validateCredentials(Authenticatable user,
                                           Map<String, Object> credentials) {
            return "correct".equals(credentials.get("password"));
        }
    }
}
