package io.jaravel.validation;

import io.jaravel.http.HttpException;

/**
 * 校验失败。
 *
 * <p>继承 {@link HttpException} 而不是裸 RuntimeException:框架的异常处理器
 * 据此自动给出 422 + Laravel 同构的 JSON,或回跳并带上 errors ——
 * 否则每个控制器都要自己 catch 一遍。
 */
public class ValidationException extends HttpException {

    private final ErrorBag errors;

    public ValidationException(ErrorBag errors, String message) {
        super(422, message);
        this.errors = errors;
    }

    public ErrorBag errors() {
        return errors;
    }

    /** 与 Laravel 字节级一致的 422 响应体 */
    public java.util.Map<String, Object> toJson() {
        return errors.toJson(getMessage());
    }

    /**
     * 异常消息里带上**全部**错误。
     *
     * <p>默认只给「给定数据无效」的话,日志里看到这个异常等于什么都没看到 ——
     * 还得去翻 ErrorBag。调试时最需要的信息应该在 getMessage() 里。
     */
    @Override
    public String getMessage() {
        String headline = super.getMessage();
        if (errors == null || errors.isEmpty()) return headline;

        var sb = new StringBuilder(headline);
        errors.messages().forEach((field, list) ->
                list.forEach(m -> sb.append("\n  ").append(field).append(": ").append(m)));
        return sb.toString();
    }

    /** 给响应体用的单行标题,不含明细 */
    public String headline() {
        return super.getMessage();
    }
}
