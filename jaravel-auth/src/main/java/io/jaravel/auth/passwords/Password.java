package io.jaravel.auth.passwords;

import java.util.Map;
import java.util.function.BiConsumer;

import io.jaravel.auth.Authenticatable;

/**
 * 密码重置的静态门面。
 *
 * <pre>{@code
 * var status = Password.sendResetLink(Map.of("email", email), (user, token) -> {
 *     Mail.to(email).send(new ResetPasswordMail(token));
 * });
 *
 * Password.reset(credentials, (user, plaintext) -> { ... });
 * }</pre>
 *
 * <h2>为什么补这个门面</h2>
 * <p>{@code Hash} / {@code Auth} / {@code Gate} 都是门面,而密码重置
 * 原先只能构造注入 —— 一个控制器为此要多写「字段 + 构造参数 + 赋值」
 * 三行,而它与 {@code Hash.make()} 在同一个方法里出现。
 *
 * <p>这个不一致不只是行数:它让用户必须记住「哪些能静态调、哪些要注入」,
 * 而那条界线没有任何道理可讲。
 *
 * <p>需要注入的场景仍然可以注入 {@link PasswordBroker} —— 门面只是
 * 一层薄壳,测试里用 {@link #use} 换掉它。
 */
public final class Password {

    private static volatile PasswordBroker broker;

    private Password() {
    }

    /** 由 starter 在启动时调用;测试里用来换一个假的 */
    public static void use(PasswordBroker configured) {
        broker = configured;
    }

    public static PasswordBroker broker() {
        PasswordBroker current = broker;
        if (current == null) {
            throw new IllegalStateException("""
                    密码重置尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调 Password.use(new PasswordBroker(provider, connection))。
                      它需要一张 password_reset_tokens 表 —— 见 auth.passwords.table。""");
        }
        return current;
    }

    // ================= 转发 =================

    public static PasswordBroker.Status sendResetLink(
            Map<String, Object> credentials,
            BiConsumer<Authenticatable, String> sender) {
        return broker().sendResetLink(credentials, sender);
    }

    public static PasswordBroker.Status reset(
            Map<String, Object> credentials,
            BiConsumer<Authenticatable, String> applier) {
        return broker().reset(credentials, applier);
    }

    public static int purgeExpired() {
        return broker().purgeExpired();
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        broker = null;
    }
}
