package io.jaravel.auth.access;

import io.jaravel.http.HttpException;

/**
 * 授权失败。默认 403,但 {@link Decision#deny(String, int)} 可以指定别的。
 *
 * <p>继承 {@link HttpException},所以框架的异常处理器会把它转成
 * 对应的响应 —— 控制器里不必 catch。
 */
public class AuthorizationException extends HttpException {

    private final String ability;

    public AuthorizationException(String ability, String reason, int status) {
        super(status, buildMessage(ability, reason));
        this.ability = ability;
    }

    public AuthorizationException(String ability) {
        this(ability, null, 403);
    }

    private static String buildMessage(String ability, String reason) {
        if (reason != null && !reason.isBlank()) return reason;

        // 没给原因时,至少说出是哪个权限 —— 「没有权限」这四个字
        // 对排查毫无帮助,而 ability 名字能直接定位到规则定义处
        return "没有执行 \"" + ability + "\" 的权限。";
    }

    public String ability() {
        return ability;
    }
}
