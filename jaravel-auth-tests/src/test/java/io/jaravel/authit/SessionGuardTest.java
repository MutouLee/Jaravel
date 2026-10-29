package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Auth;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.StatefulGuard;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.session.SessionData;
import io.jaravel.authit.models.Admin;
import io.jaravel.authit.models.User;

/** 会话型认证的全链路 */
class SessionGuardTest extends AuthTestBase {

    @Nested
    @DisplayName("登录与登出")
    class LoginLogout {

        @Test
        @DisplayName("正确凭据登录成功,错误的不行")
        void attempt() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                assertThat(Auth.attempt(credentials("a@example.com", "wrong"))).isFalse();
                assertThat(Auth.check()).isFalse();

                assertThat(Auth.attempt(credentials("a@example.com", "secret"))).isTrue();
                assertThat(Auth.check()).isTrue();
                assertThat(Auth.user(User.class)).get()
                        .extracting(u -> u.email).isEqualTo("a@example.com");
            });
        }

        @Test
        @DisplayName("登录状态跨请求保持 —— 会话里存的是 id")
        void persistsAcrossRequests() {
            User user = createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> Auth.attempt(credentials("a@example.com", "secret")));

            // 新的执行作用域 = 新的请求,guard 实例也是新的
            inRequest(session, () -> {
                assertThat(Auth.check()).isTrue();
                assertThat(Auth.id()).contains(user.id);
            });
        }

        @Test
        @DisplayName("★ 登录后换 session id —— 防会话固定攻击")
        void loginRegeneratesSessionId() {
            createUser("a@example.com", "secret");
            var session = newSession();
            String attackerChosenId = session.id();

            inRequest(session, () -> Auth.attempt(credentials("a@example.com", "secret")));

            // 攻击者预先塞给受害者的 session id 必须失效
            assertThat(session.id()).isNotEqualTo(attackerChosenId);
        }

        @Test
        @DisplayName("登出清掉登录状态并换 id")
        void logout() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> Auth.attempt(credentials("a@example.com", "secret")));
            String loggedInId = session.id();

            inRequest(session, Auth::logout);

            assertThat(session.id()).isNotEqualTo(loggedInId);
            inRequest(session, () -> assertThat(Auth.check()).isFalse());
        }

        @Test
        @DisplayName("login(user) 直接登录,不校验密码")
        void loginDirectly() {
            User user = createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                var authenticatable = auth.provider("users")
                        .retrieveById(user.id).orElseThrow();
                Auth.login(authenticatable);
                assertThat(Auth.check()).isTrue();
            });
        }

        @Test
        @DisplayName("loginUsingId")
        void loginUsingId() {
            User user = createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                assertThat(Auth.loginUsingId(user.id)).isPresent();
                assertThat(Auth.check()).isTrue();

                assertThat(Auth.loginUsingId(999_999L)).isEmpty();
            });
        }

        @Test
        @DisplayName("once 不写会话 —— 本请求有效,下个请求失效")
        void once() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                assertThat(Auth.once(credentials("a@example.com", "secret"))).isTrue();
                assertThat(Auth.check()).isTrue();
            });

            inRequest(session, () -> assertThat(Auth.check()).isFalse());
        }

        @Test
        @DisplayName("validate 只验证,不登录")
        void validateDoesNotLogin() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                assertThat(Auth.validate(credentials("a@example.com", "secret"))).isTrue();
                assertThat(Auth.check()).as("validate 不该写会话").isFalse();
            });
        }

        @Test
        @DisplayName("额外的凭据字段参与查询 —— 停用的账号登不进来")
        void extraCredentialsAreQueried() {
            User user = createUser("a@example.com", "secret");
            user.status = "suspended";
            user.save();

            var session = newSession();
            inRequest(session, () -> {
                assertThat(Auth.attempt(Map.of(
                        "email", "a@example.com",
                        "password", "secret",
                        "status", "active"))).isFalse();

                // 不带 status 条件时能登进来 —— 证明上面的 false 来自 status
                assertThat(Auth.attempt(credentials("a@example.com", "secret"))).isTrue();
            });
        }

        @Test
        @DisplayName("凭据字段名写错时报错 —— 静默忽略等于不校验那个条件")
        void unknownCredentialFieldFails() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> assertThatThrownBy(() -> Auth.attempt(Map.of(
                            "email", "a@example.com",
                            "password", "secret",
                            "statuss", "active")))
                    .hasMessageContaining("没有对应的列")
                    .hasMessageContaining("是否想写")
                    .hasMessageContaining("停用的账号也能登录"));
        }

        @Test
        @DisplayName("只给 password 的凭据被拒 —— 否则会随便匹配一个用户")
        void passwordOnlyCredentialsRejected() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> assertThatThrownBy(
                    () -> Auth.attempt(Map.of("password", "secret")))
                    .hasMessageContaining("没有任何可查询的字段"));
        }
    }

    @Nested
    @DisplayName("安全")
    class Security {

        /**
         * 时序攻击防护。
         *
         * <p>不断言绝对耗时(CI 机器抖动大),而是断言**两条路径都真的跑了
         * 哈希校验** —— 用一个会计数的 HashManager 包装。这比计时稳定,
         * 且测的正是「有没有空转那一次哈希」这个因果。
         */
        @Test
        @DisplayName("★ 用户不存在时仍然跑一次哈希校验 —— 防邮箱枚举")
        void timingAttackDefense() {
            createUser("exists@example.com", "secret");
            var session = newSession();

            var counting = new CountingAlgorithm(hasher.driver("pbkdf2"));
            var countingManager = new io.jaravel.auth.hash.HashManager().register(counting);
            var countingAuth = new AuthManager()
                    .registerProvider(new io.jaravel.auth.provider.EloquentUserProvider<>(
                            "users", User.class, countingManager))
                    .registerGuard("web", "users", p -> new SessionGuard("web", p))
                    .defaultGuard("web");

            inRequest(session, () -> {
                counting.reset();
                countingAuth.guard().validate(credentials("exists@example.com", "wrong"));
                int withExistingUser = counting.checks();

                counting.reset();
                countingAuth.guard().validate(credentials("nobody@example.com", "wrong"));
                int withMissingUser = counting.checks();

                // 两条路径的哈希校验次数必须一致 —— 否则耗时差异可用来枚举邮箱
                assertThat(withMissingUser)
                        .as("用户不存在时也必须跑哈希校验")
                        .isEqualTo(withExistingUser)
                        .isEqualTo(1);
            });
        }

        @Test
        @DisplayName("★ 登录成功后若哈希参数已升级,静默重算")
        void rehashOnLogin() {
            // 先用低参数建用户
            var weak = new io.jaravel.core.crypt.Hasher(10_000);
            User user = new User();
            user.email = "a@example.com";
            user.password = weak.make("secret");
            user.save();
            String oldHash = user.password;

            // 换成高参数的 hasher
            var strong = new io.jaravel.auth.hash.HashManager()
                    .register(new io.jaravel.auth.hash.Pbkdf2Algorithm(
                            new io.jaravel.core.crypt.Hasher(20_000)));
            var upgradedAuth = new AuthManager()
                    .registerProvider(new io.jaravel.auth.provider.EloquentUserProvider<>(
                            "users", User.class, strong))
                    .registerGuard("web", "users", p -> new SessionGuard("web", p))
                    .defaultGuard("web");

            var session = newSession();
            inRequest(session, () -> {
                assertThat(((StatefulGuard) upgradedAuth.guard())
                        .attempt(credentials("a@example.com", "secret"), false)).isTrue();
            });

            // 数据库里的哈希应该已被替换成高参数的
            User reloaded = User.findOrFail(user.id);
            assertThat(reloaded.password)
                    .as("参数升级后应在登录时静默重算")
                    .isNotEqualTo(oldHash);
            assertThat(reloaded.password).contains("i=20000");
            // 新哈希仍然能校验原密码
            assertThat(strong.check("secret", reloaded.password)).isTrue();
        }

        @Test
        @DisplayName("★ 两个 guard 的登录状态互相隔离")
        void guardsAreIsolated() {
            createUser("u@example.com", "secret");
            createAdmin("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                ((StatefulGuard) auth.guard("admin"))
                        .attempt(credentials("a@example.com", "secret"), false);

                assertThat(auth.guard("admin").check()).isTrue();
                // admin 登录了不等于 web 也登录了
                assertThat(auth.guard("web").check())
                        .as("guard 之间必须隔离").isFalse();
            });
        }

        @Test
        @DisplayName("guard 实例不跨请求复用 —— 否则会把上个请求的用户带过来")
        void guardInstancesAreRequestScoped() {
            createUser("a@example.com", "secret");
            var sessionA = newSession();
            var sessionB = newSession();

            inRequest(sessionA, () -> Auth.attempt(credentials("a@example.com", "secret")));

            // 另一个「浏览器」的请求
            inRequest(sessionB, () -> assertThat(Auth.check())
                    .as("别的会话不该看到已登录状态").isFalse());
        }

        @Test
        @DisplayName("没有会话时报错,而不是当作未登录")
        void noSessionIsAnError() {
            createUser("a@example.com", "secret");

            // 不包会话的作用域 —— 模拟队列任务
            assertThatThrownBy(() -> {
                io.jaravel.core.context.ExecutionScope.runIn("queue", () -> {
                    Auth.check();
                    return null;
                });
            })
                    .isInstanceOf(SessionGuard.NoSessionException.class)
                    .hasMessageContaining("以访客的规则通过")
                    .hasMessageContaining("队列");
        }

        @Test
        @DisplayName("密码为空的主体无法用密码登录")
        void emptyPasswordCannotLogin() {
            User user = new User();
            user.email = "nopass@example.com";
            user.password = null;
            user.save();

            var session = newSession();
            inRequest(session, () -> assertThat(
                    Auth.attempt(credentials("nopass@example.com", ""))).isFalse());
        }
    }

    @Nested
    @DisplayName("remember me")
    class RememberMe {

        @Test
        @DisplayName("勾选记住我会写回 remember_token 并签发 cookie")
        void issuesCookie() {
            User user = createUser("a@example.com", "secret");
            assertThat(user.rememberToken).isNull();

            var session = newSession();
            var cookie = inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie();
            });

            assertThat(cookie).isPresent();
            assertThat(cookie.get().name()).isEqualTo("remember_web");
            assertThat(cookie.get().value()).startsWith(user.id + "|");

            // 数据库里也写了令牌
            assertThat(User.findOrFail(user.id).rememberToken).isNotBlank();
        }

        @Test
        @DisplayName("★ cookie 是 httpOnly + secure —— XSS 偷不走长期凭据")
        void cookieIsHardened() {
            createUser("a@example.com", "secret");
            var session = newSession();

            var cookie = inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie();
            }).orElseThrow();

            assertThat(cookie.httpOnly()).as("JS 必须读不到").isTrue();
            assertThat(cookie.secure()).as("只能走 https").isTrue();
        }

        @Test
        @DisplayName("用 remember cookie 恢复登录")
        void recallFromCookie() {
            User user = createUser("a@example.com", "secret");

            var loginSession = newSession();
            String cookieValue = inRequest(loginSession, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie().orElseThrow().value();
            });

            // 全新会话(会话过期了),只带着 cookie
            var freshSession = newSession();
            inRequest(freshSession, () -> {
                var guard = (SessionGuard) auth.guard("web");
                assertThat(guard.check()).isFalse();

                assertThat(guard.recallFromCookie(cookieValue)).isPresent();
                assertThat(guard.check()).isTrue();
                assertThat(guard.viaRemember()).isTrue();
                assertThat(guard.id()).contains(user.id);
            });
        }

        @Test
        @DisplayName("★ 伪造的 remember cookie 无效 —— 只有 id 不够")
        void forgedCookieRejected() {
            User user = createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                // 攻击者知道 id,但猜不到令牌
                assertThat(guard.recallFromCookie(user.id + "|guessed-token")).isEmpty();
                assertThat(guard.recallFromCookie(user.id + "|")).isEmpty();
                assertThat(guard.recallFromCookie(String.valueOf(user.id))).isEmpty();
                assertThat(guard.recallFromCookie("garbage")).isEmpty();
                assertThat(guard.check()).isFalse();
            });
        }

        @Test
        @DisplayName("★ 恢复登录时也换 session id")
        void recallRegeneratesSession() {
            createUser("a@example.com", "secret");

            var loginSession = newSession();
            String cookieValue = inRequest(loginSession, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie().orElseThrow().value();
            });

            var freshSession = newSession();
            String before = freshSession.id();
            inRequest(freshSession, () ->
                    ((SessionGuard) auth.guard("web")).recallFromCookie(cookieValue));

            assertThat(freshSession.id()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("★ 登出会清掉数据库里的令牌 —— 被盗的 cookie 随之失效")
        void logoutInvalidatesCookie() {
            User user = createUser("a@example.com", "secret");

            var session = newSession();
            String cookieValue = inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie().orElseThrow().value();
            });

            inRequest(session, Auth::logout);

            // 旧 cookie 不该还能用
            var freshSession = newSession();
            inRequest(freshSession, () -> assertThat(
                    ((SessionGuard) auth.guard("web")).recallFromCookie(cookieValue)).isEmpty());
        }

        @Test
        @DisplayName("登出会写一个过期的 cookie 清掉客户端副本")
        void logoutClearsCookie() {
            createUser("a@example.com", "secret");
            var session = newSession();

            var cookie = inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                guard.takePendingCookie();
                guard.logout();
                return guard.takePendingCookie();
            });

            assertThat(cookie).isPresent();
            assertThat(cookie.get().value()).isEmpty();
        }

        @Test
        @DisplayName("模型没有 remember_token 列时,请求 remember 会明确报错")
        void unsupportedRememberFailsLoud() {
            createAdmin("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> assertThatThrownBy(() ->
                    ((StatefulGuard) auth.guard("admin"))
                            .attempt(credentials("a@example.com", "secret"), true))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("请求了 remember me")
                    .hasMessageContaining("remember_token"));
        }

        @Test
        @DisplayName("★ 再次登录会轮换令牌 —— 旧 cookie 立刻失效")
        void loginRotatesToken() {
            createUser("a@example.com", "secret");

            var first = newSession();
            String oldCookie = inRequest(first, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie().orElseThrow().value();
            });

            var second = newSession();
            String newCookie = inRequest(second, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.attempt(credentials("a@example.com", "secret"), true);
                return guard.takePendingCookie().orElseThrow().value();
            });

            assertThat(newCookie).isNotEqualTo(oldCookie);

            var third = newSession();
            inRequest(third, () -> {
                var guard = (SessionGuard) auth.guard("web");
                assertThat(guard.recallFromCookie(oldCookie))
                        .as("旧 cookie 应已失效").isEmpty();
            });
        }

        @Test
        @DisplayName("logoutOtherDevices 需要正确的当前密码")
        void logoutOtherDevicesRequiresPassword() {
            createUser("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                Auth.attempt(credentials("a@example.com", "secret"));

                assertThatThrownBy(() -> Auth.logoutOtherDevices("wrong"))
                        .hasMessageContaining("当前密码不正确")
                        .hasMessageContaining("后果不可逆");

                Auth.logoutOtherDevices("secret");   // 正确密码应通过
            });
        }
    }

    @Nested
    @DisplayName("门面行为")
    class Facade {

        @Test
        @DisplayName("userOrFail 未登录时抛 401 并说清怎么修")
        void userOrFailThrows401() {
            var session = newSession();

            inRequest(session, () -> assertThatThrownBy(() -> Auth.userOrFail(User.class))
                    .isInstanceOf(io.jaravel.http.HttpException.class)
                    .satisfies(e -> assertThat(
                            ((io.jaravel.http.HttpException) e).status()).isEqualTo(401))
                    .hasMessageContaining("middleware(\"auth\")"));
        }

        @Test
        @DisplayName("user(Class) 类型不符时返回空而不是抛异常")
        void wrongTypeReturnsEmpty() {
            createAdmin("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                ((StatefulGuard) auth.guard("admin"))
                        .attempt(credentials("a@example.com", "secret"), false);

                assertThat(auth.guard("admin").user().flatMap(u -> u.as(Admin.class)))
                        .isPresent();
                // 多 guard 应用里问错类型是正常情况,不该炸
                assertThat(auth.guard("admin").user().flatMap(u -> u.as(User.class)))
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("shouldUse 在本请求内改默认 guard")
        void shouldUse() {
            createAdmin("a@example.com", "secret");
            var session = newSession();

            inRequest(session, () -> {
                Auth.shouldUse("admin");
                assertThat(Auth.guard().name()).isEqualTo("admin");

                assertThat(Auth.attempt(credentials("a@example.com", "secret"))).isTrue();
                assertThat(Auth.user(Admin.class)).isPresent();
            });

            // 不影响下一个请求
            inRequest(newSession(), () -> assertThat(Auth.guard().name()).isEqualTo("web"));
        }

        @Test
        @DisplayName("未注册的 guard 名给出「是否想写」与已注册清单")
        void unknownGuardIsActionable() {
            assertThatThrownBy(() -> auth.guard("admln"))
                    .hasMessageContaining("没有名为 \"admln\" 的 guard")
                    .hasMessageContaining("是否想写 \"admin\"")
                    .hasMessageContaining("已注册");
        }

        @Test
        @DisplayName("Auth 未配置时的报错给出完整的装配代码")
        void unconfiguredIsActionable() {
            Auth.use(null);
            try {
                assertThatThrownBy(Auth::check)
                        .hasMessageContaining("认证还没有配置")
                        .hasMessageContaining("registerGuard");
            } finally {
                Auth.use(auth);
            }
        }
    }

    /**
     * 统计哈希校验次数。
     *
     * <p>装饰 {@code Algorithm} 而不是 {@code HashManager} —— 后者的
     * {@code check} 会先按前缀挑算法,从算法层计数才是真正「跑了几次哈希」。
     */
    static final class CountingAlgorithm implements io.jaravel.auth.hash.HashManager.Algorithm {
        private final io.jaravel.auth.hash.HashManager.Algorithm delegate;
        private int checks;

        CountingAlgorithm(io.jaravel.auth.hash.HashManager.Algorithm delegate) {
            this.delegate = delegate;
        }

        void reset() { checks = 0; }

        int checks() { return checks; }

        @Override public String name() { return delegate.name(); }

        @Override public boolean recognizes(String hash) { return delegate.recognizes(hash); }

        @Override public String make(String plaintext) { return delegate.make(plaintext); }

        @Override public boolean check(String plaintext, String hash) {
            checks++;
            return delegate.check(plaintext, hash);
        }

        @Override public boolean needsRehash(String hash) { return delegate.needsRehash(hash); }
    }
}
