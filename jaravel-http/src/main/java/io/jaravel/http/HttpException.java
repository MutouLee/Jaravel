package io.jaravel.http;

import java.util.Map;

/**
 * 带 HTTP 状态码的异常。由 {@code Response.abort(...)} 抛出,
 * 交给 ExceptionHandler 按内容协商渲染为 JSON 或错误页。
 */
public class HttpException extends RuntimeException {

    private final int status;
    private final Map<String, String> headers;

    public HttpException(int status) {
        this(status, defaultMessage(status), Map.of());
    }

    public HttpException(int status, String message) {
        this(status, message, Map.of());
    }

    public HttpException(int status, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.headers = headers == null ? Map.of() : headers;
    }

    public int status() { return status; }

    public Map<String, String> headers() { return headers; }

    public static String defaultMessage(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthenticated.";
            case 403 -> "This action is unauthorized.";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 419 -> "CSRF token mismatch.";
            case 422 -> "The given data was invalid.";
            case 429 -> "Too Many Requests";
            case 500 -> "Server Error";
            case 503 -> "Service Unavailable";
            default  -> "HTTP " + status;
        };
    }
}
