package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.AuthManager;
import io.jaravel.auth.middleware.Authenticate;
import io.jaravel.auth.middleware.Authorize;
import io.jaravel.auth.middleware.ConfirmPassword;
import io.jaravel.auth.middleware.EnsureEmailVerified;
import io.jaravel.auth.middleware.RedirectIfAuthenticated;
import io.jaravel.auth.middleware.ThrottleRequests;
import io.jaravel.cache.CacheManager;
import io.jaravel.cache.RateLimiter;
import io.jaravel.http.Middleware;
import io.jaravel.http.MiddlewareStack;
import io.jaravel.session.SessionConfig;
import io.jaravel.session.StartSession;
import io.jaravel.session.VerifyCsrfToken;
import io.jaravel.session.store.MemoryStore;

/**
 * 默认内核的分组、别名与**顺序约束**。
 *
 * <p>顺序那一组是这个测试存在的主要理由:
 * {@link DefaultKernel#priority()} 里每一条都对应一个真实故障,
 * 而「顺序错了」不会让任何测试变红 —— 除非有人专门守住它。
 */
class DefaultKernelTest {

    private final DefaultKernel kernel = new DefaultKernel();

    /**
     * 用真实构造函数创建中间件。
     *
     * <p>不用 stub:这样顺便证明了这些中间件能从
     * {@link JaravelAuthAutoConfiguration} 提供的那些 bean 构造出来 ——
     * 别名表指向一个无法实例化的类,症状是运行期 500 而不是启动失败。
     */
    private final MiddlewareStack stack = new MiddlewareStack(kernel, new MiddlewareStack.Factory() {
        private final Map<Class<?>, Supplier<Middleware>> factories = Map.of(
                StartSession.class, () -> new StartSession(new MemoryStore(),
                                                           SessionConfig.defaults()),
                VerifyCsrfToken.class, VerifyCsrfToken::new,
                Authenticate.class, () -> new Authenticate(new AuthManager()),
                RedirectIfAuthenticated.class,
                        () -> new RedirectIfAuthenticated(new AuthManager()),
                Authorize.class, Authorize::new,
                EnsureEmailVerified.class, EnsureEmailVerified::new,
                ConfirmPassword.class, ConfirmPassword::new,
                ThrottleRequests.class,
                        () -> new ThrottleRequests(
                                new RateLimiter(CacheManager.inMemory().store())),
                SubstituteBindings.class, SubstituteBindings::new);

        @SuppressWarnings("unchecked")
        @Override
        public <T extends Middleware> T create(Class<T> type) {
            var factory = factories.get(type);
            if (factory == null) {
                throw new AssertionError("测试没有为 " + type.getSimpleName() + " 提供工厂");
            }
            return (T) factory.get();
        }
    });

    private List<Class<?>> chain(String... declared) {
        return stack.resolve(List.of(declared), List.of()).stream()
                .<Class<?>>map(Object::getClass)
                .toList();
    }

    // ================= 别名 =================

    @Nested
    @DisplayName("标准别名开箱可用")
    class Aliases {

        @Test
        @DisplayName("★ 不写 Kernel 时 middleware(\"auth\") 也能用")
        void authAliasWorksWithoutUserKernel() {
            // 「加个登录 = 3 行」的前提:别名在框架里而不是脚手架生成的文件里
            assertThat(chain("auth")).containsExactly(Authenticate.class);
        }

        @Test
        @DisplayName("六个标准别名都在")
        void allStandardAliases() {
            assertThat(kernel.middlewareAliases())
                    .containsKeys("auth", "guest", "can", "verified",
                                  "throttle", "password.confirm");
        }

        @Test
        @DisplayName("带参数的别名能解析")
        void parameterizedAliases() {
            assertThat(chain("throttle:60,1")).containsExactly(ThrottleRequests.class);
            assertThat(chain("can:update,post")).containsExactly(Authorize.class);
            assertThat(chain("password.confirm:30")).containsExactly(ConfirmPassword.class);
        }

        @Test
        @DisplayName("参数真的传进去了")
        void parametersReachTheMiddleware() {
            var resolved = stack.resolve(List.of("password.confirm:30"), List.of());

            assertThat(((ConfirmPassword) resolved.get(0)).validity())
                    .isEqualTo(java.time.Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("写错别名时报错带候选")
        void typoSuggestsCandidate() {
            assertThatThrownBy(() -> chain("ath"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("是否想写 \"auth\"");
        }
    }

    // ================= 分组 =================

    @Nested
    @DisplayName("分组")
    class Groups {

        @Test
        @DisplayName("web 组 = 会话 + CSRF + 模型绑定")
        void webGroup() {
            assertThat(chain("web"))
                    .containsExactly(StartSession.class, VerifyCsrfToken.class,
                                     SubstituteBindings.class);
        }

        @Test
        @DisplayName("★ api 组不含 StartSession —— 无状态接口不下发会话 cookie")
        void apiGroupHasNoSession() {
            assertThat(chain("api")).doesNotContain(StartSession.class);
        }

        @Test
        @DisplayName("★ 隐式模型绑定是默认行为,web 与 api 两组都有")
        void bindingsAreInBothGroups() {
            // 忘了在路由上加 bindings 的症状是 can:update,post 永远拒绝,
            // 那看起来像权限配错了 —— 所以它必须是默认的
            assertThat(chain("web")).contains(SubstituteBindings.class);
            assertThat(chain("api")).containsExactly(SubstituteBindings.class);
        }

        @Test
        @DisplayName("组 + 别名合并后去重")
        void deduplicates() {
            // web 组里有 StartSession,再单独声明一次 session 别名
            assertThat(chain("web", "session"))
                    .containsExactly(StartSession.class, VerifyCsrfToken.class,
                                     SubstituteBindings.class);
        }
    }

    // ================= 顺序 =================

    @Nested
    @DisplayName("★ 顺序约束(每一条都对应一个真实故障)")
    class Priority {

        @Test
        @DisplayName("声明顺序写反了也不影响实际顺序")
        void declarationOrderDoesNotMatter() {
            // 用户在路由上写 middleware("auth", "web") —— 很自然的笔误
            assertThat(chain("auth", "web"))
                    .containsExactly(StartSession.class, VerifyCsrfToken.class,
                                     Authenticate.class, SubstituteBindings.class);
        }

        @Test
        @DisplayName("StartSession 必须最先 —— 否则「登录成功但下个请求又变游客」")
        void sessionStartsFirst() {
            var order = chain("can:update,post", "auth", "verified", "web");
            assertThat(order.indexOf(StartSession.class)).isZero();
        }

        @Test
        @DisplayName("CSRF 在认证之前 —— 伪造请求不该白送一次登录尝试")
        void csrfBeforeAuth() {
            var order = chain("auth", "csrf");
            assertThat(order.indexOf(VerifyCsrfToken.class))
                    .isLessThan(order.indexOf(Authenticate.class));
        }

        @Test
        @DisplayName("★ Authenticate 在 Authorize 之前 —— 否则授权永远在判断游客")
        void authBeforeAuthorize() {
            // docs/08 §10 的待决问题之一,在此固定
            var order = chain("can:update,post", "auth");
            assertThat(order.indexOf(Authenticate.class))
                    .isLessThan(order.indexOf(Authorize.class));
        }

        @Test
        @DisplayName("节流在认证之前 —— 放后面等于没放")
        void throttleBeforeAuth() {
            var order = chain("auth", "throttle:5,1");
            assertThat(order.indexOf(ThrottleRequests.class))
                    .isLessThan(order.indexOf(Authenticate.class));
        }

        @Test
        @DisplayName("邮箱验证与二次密码确认都在认证之后 —— 它们要读当前用户")
        void userDependentMiddlewareAfterAuth() {
            var order = chain("verified", "password.confirm", "auth");
            int auth = order.indexOf(Authenticate.class);

            assertThat(order.indexOf(EnsureEmailVerified.class)).isGreaterThan(auth);
            assertThat(order.indexOf(ConfirmPassword.class)).isGreaterThan(auth);
        }

        @Test
        @DisplayName("完整的一条典型链的顺序")
        void fullChain() {
            assertThat(chain("web", "auth", "verified", "can:update,post",
                             "throttle:60,1", "password.confirm"))
                    .containsExactly(
                            StartSession.class,
                            VerifyCsrfToken.class,
                            ThrottleRequests.class,
                            Authenticate.class,
                            EnsureEmailVerified.class,
                            ConfirmPassword.class,
                            SubstituteBindings.class,
                            Authorize.class);
        }

        @Test
        @DisplayName("★ SubstituteBindings 在 Authorize 之前(docs/08 §10 的待决项)")
        void bindingsBeforeAuthorize() {
            var order = chain("can:update,post", "bindings");

            // can:update,post 要的是 Post 实例。拿到字符串 "42" 去问
            // PostPolicy.update(User, Post) 的结果是永远拒绝,
            // 而那个 403 看起来像「权限配错了」
            assertThat(order.indexOf(SubstituteBindings.class))
                    .isLessThan(order.indexOf(Authorize.class));
        }
    }

    // ================= 可覆盖性 =================

    @Nested
    @DisplayName("可覆盖")
    class Overridable {

        @Test
        @DisplayName("继承后只改一项,其余保留")
        void subclassKeepsTheRest() {
            var custom = new DefaultKernel() {
                @Override
                protected List<Class<? extends Middleware>> global() {
                    return List.of(VerifyCsrfToken.class);
                }
            };

            // 这正是 Kernel.empty() 做不到的 ——
            // 用户想加一个全局中间件就得把整张别名表重新抄一遍
            assertThat(custom.globalMiddleware()).containsExactly(VerifyCsrfToken.class);
            assertThat(custom.middlewareAliases()).containsKeys("auth", "can", "throttle");
            assertThat(custom.middlewarePriority()).isNotEmpty();
        }
    }
}
