package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Auth;
import io.jaravel.auth.twofactor.Totp;
import io.jaravel.auth.twofactor.TwoFactorAuthenticatable;
import io.jaravel.auth.twofactor.TwoFactorChallenge;
import io.jaravel.authit.models.Admin;
import io.jaravel.authit.models.User;
import io.jaravel.session.Session;
import io.jaravel.support.Clock;

/**
 * 登录流程的第二步(docs/08 §6.4)。
 *
 * <p>测的是**跨请求的状态**:挑战发起在一个请求里,完成在下一个请求里,
 * 中间只有会话在传递。所以这里用 {@link AuthTestBase#inRequest} 一个请求一段 ——
 * 同一个 {@code SessionData} 代表「同一个浏览器」,换一个就是另一台设备。
 *
 * <p>整条 HTTP 流程(表单、重定向、CSRF)由 {@code examples/auth} 的
 * {@code TwoFactorFlowTest} 在真服务器上验收;这里是那一层下面的状态机。
 */
class TwoFactorChallengeTest extends AuthTestBase {

    /**
     * 开好二步验证的用户。
     *
     * <p>模型上**没有**那七个访问器 —— 列名写在 {@code @AuthModel} 里,
     * {@code TwoFactorAuthenticatable.of(model)} 取到 provider 的包装体
     * (它改的是同一个模型实例,所以接着 save 就落库)。
     */
    private User enabled(String email) {
        User user = createUser(email, "secret");
        var twoFactor = TwoFactorAuthenticatable.of(user);

        twoFactor.enableTwoFactor("Jaravel");
        twoFactor.twoFactorEnabled(true);
        user.save();
        return user;
    }

    private String code(User user) {
        return Totp.code(TwoFactorAuthenticatable.of(user).twoFactorSecret(), Clock.now());
    }

    @Test
    @DisplayName("★ 挑战发起之后仍然没有登录")
    void startingAChallengeDoesNotLogIn() {
        User user = enabled("pending@example.com");
        var session = newSession();

        inRequest(session, () -> {
            TwoFactorChallenge.start(user, false);

            assertThat(TwoFactorChallenge.pending()).isTrue();
            assertThat(Auth.check())
                    .as("""
                            「先登录再补验证码」会让每一个忘了挂中间件的路由
                            成为绕过口 —— 而忘了挂一定会发生。""")
                    .isFalse();
        });

        // 下一个请求:还是没登录,但挑战还在
        inRequest(session, () -> {
            assertThat(Auth.check()).isFalse();
            assertThat(TwoFactorChallenge.pending()).isTrue();
            assertThat(TwoFactorChallenge.pendingUser()
                    .flatMap(p -> p.as(User.class)).map(u -> u.email))
                    .contains("pending@example.com");
        });
    }

    @Test
    @DisplayName("发起时换会话 id —— 挑战态本身就是一个有价值的凭据")
    void startRegeneratesTheSessionId() {
        User user = enabled("rotate@example.com");
        var session = newSession();

        inRequest(session, () -> {
            String before = Session.id();
            TwoFactorChallenge.start(user, false);

            assertThat(Session.id()).isNotEqualTo(before);
        });
    }

    @Test
    @DisplayName("★ 正确的验证码才通过,通过之后才登录")
    void completeRequiresTheRightCode() {
        User user = enabled("code@example.com");
        var session = newSession();

        inRequest(session, () -> TwoFactorChallenge.start(user, false));

        inRequest(session, () -> {
            assertThat(TwoFactorChallenge.complete("000000")).isEmpty();
            assertThat(Auth.check()).isFalse();
            assertThat(TwoFactorChallenge.pending())
                    .as("失败不该清掉挑战 —— 用户要能再试一次")
                    .isTrue();
        });

        inRequest(session, () -> {
            assertThat(TwoFactorChallenge.complete(code(user))).isPresent();
            assertThat(Auth.check()).isTrue();
            assertThat(TwoFactorChallenge.pending())
                    .as("通过之后挑战要清掉,否则它能被重放")
                    .isFalse();
        });

        // 再开一个请求:登录态活在会话里
        inRequest(session, () -> assertThat(Auth.check()).isTrue());
    }

    @Test
    @DisplayName("★ 恢复码能通过,而且用掉就不能再用")
    void recoveryCodeIsConsumed() {
        User user = createUser("recovery@example.com", "secret");
        var twoFactor = TwoFactorAuthenticatable.of(user);
        var setup = twoFactor.enableTwoFactor("Jaravel");
        twoFactor.twoFactorEnabled(true);
        user.save();
        String recoveryCode = setup.recoveryCodes().get(0);

        var first = newSession();
        inRequest(first, () -> TwoFactorChallenge.start(user, false));
        inRequest(first, () -> {
            var passed = TwoFactorChallenge.completeWithRecoveryCode(recoveryCode);

            assertThat(passed).isPresent();
            // 用掉的码必须落库 —— 不保存的话同一个码能用第二次
            passed.flatMap(p -> p.as(User.class)).orElseThrow().save();
        });

        assertThat(User.query().whereEmail("recovery@example.com").first()
                        .orElseThrow().twoFactorRecoveryCodes)
                .as("十个里用掉一个")
                .hasSize(9);

        var second = newSession();
        User reloaded = User.query().whereEmail("recovery@example.com").first().orElseThrow();
        inRequest(second, () -> TwoFactorChallenge.start(reloaded, false));
        inRequest(second, () -> {
            assertThat(TwoFactorChallenge.completeWithRecoveryCode(recoveryCode))
                    .as("恢复码是一次性的")
                    .isEmpty();
            assertThat(Auth.check()).isFalse();
        });
    }

    @Test
    @DisplayName("★ 挑战属于这个浏览器 —— 另一个会话完不成它")
    void theChallengeBelongsToOneBrowser() {
        User user = enabled("device@example.com");
        var phone = newSession();
        var laptop = newSession();

        inRequest(phone, () -> TwoFactorChallenge.start(user, false));

        inRequest(laptop, () -> {
            assertThat(TwoFactorChallenge.pending())
                    .as("按用户 id 存缓存的实现里,这里会是 true —— 于是别的设备能替你完成")
                    .isFalse();
            assertThat(TwoFactorChallenge.complete(code(user))).isEmpty();
            assertThat(Auth.check()).isFalse();
        });
    }

    @Test
    @DisplayName("remember 跟着挑战走:通过之后才下发那张长效凭据")
    void rememberIsCarriedThroughTheChallenge() {
        User user = enabled("remember@example.com");
        var session = newSession();

        inRequest(session, () -> TwoFactorChallenge.start(user, true));
        inRequest(session, () -> {
            assertThat(TwoFactorChallenge.complete(code(user))).isPresent();

            assertThat(User.query().whereEmail("remember@example.com").first()
                            .orElseThrow().rememberToken)
                    .as("remember=true 的登录会写 remember_token —— 它是挑战通过之后才发生的")
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("abandon 清掉挑战 —— 「用别的账号登录」那条路")
    void abandonClearsIt() {
        User user = enabled("abandon@example.com");
        var session = newSession();

        inRequest(session, () -> {
            TwoFactorChallenge.start(user, false);
            TwoFactorChallenge.abandon();

            assertThat(TwoFactorChallenge.pending()).isFalse();
            assertThat(TwoFactorChallenge.complete(code(user))).isEmpty();
        });
    }

    @Test
    @DisplayName("★ 模型没配二步验证的列:报错说清要改什么")
    void modelWithoutTwoFactorColumnsFailsLoudly() {
        Admin admin = createAdmin("admin@example.com", "secret");
        var session = newSession();

        inRequest(session, () -> {
            // admin guard 的 provider 查的是 admins 表;Admin 的 @AuthModel
            // 里没写二步验证那几列 —— 于是「没有密钥」
            Auth.shouldUse("admin");
            TwoFactorChallenge.start(admin, false);

            // 报错而不是返回「验证码不对」:静默失败的话用户看到的是
            // 「验证码不正确」,没人会去看代码
            assertThatThrownBy(() -> TwoFactorChallenge.complete("123456"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有二步验证密钥")
                    .hasMessageContaining("@AuthModel");
        });
    }
}
