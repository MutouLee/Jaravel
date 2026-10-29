package io.jaravel.validation;

import java.util.LinkedHashMap;
import java.util.Map;

import io.jaravel.http.HttpException;
import io.jaravel.http.Request;

/**
 * 把「鉴权 + 校验 + 类型化取值」收进一个类。
 *
 * <pre>{@code
 * public class StorePostRequest extends FormRequest {
 *     public boolean authorize() { return true; }
 *
 *     public Map<String, String> rules() {
 *         return Map.of("title", "required|string|max:200",
 *                       "body",  "required|string");
 *     }
 *
 *     public String title() { return validated().string("title"); }
 * }
 *
 * // 控制器
 * public Response store(StorePostRequest req) {
 *     Post.create(Map.of("title", req.title(), "body", req.body()));
 * }
 * }</pre>
 *
 * <p><b>它解决的问题</b>:控制器里混着「这个请求合法吗」「数据对吗」「取值转型」
 * 三件事,是 Web 代码最常见的膨胀来源。把它们移到一个有名字的类里,
 * 控制器就只剩业务动作 —— 这也是 parity 上的直接收益。
 *
 * <p>执行顺序由参数绑定器驱动:
 * {@code 实例化 → prepareForValidation() → authorize()(失败 403)
 *  → 规则校验 + withValidator → 失败 failedValidation → 成功注入}。
 */
public abstract class FormRequest {

    private Request request;
    private Validated validated;
    private Map<String, Object> input;

    // ================= 子类覆盖点 =================

    /** 规则。字符串写法。 */
    public abstract Map<String, String> rules();

    /**
     * 是否允许这个请求。
     *
     * <p>默认 {@code true} 而非 {@code false}:绝大多数 FormRequest 只管校验,
     * 默认拒绝会让每个类都要写一行 {@code return true;}。鉴权是显式决定,
     * 不写就等于不限制 —— 真正的权限控制应该在中间件或 Gate 里,
     * 那里有统一的审计点。
     */
    public boolean authorize() {
        return true;
    }

    /** 自定义消息,键为 {@code field.rule} 或 {@code rule} */
    public Map<String, String> messages() {
        return Map.of();
    }

    /** 字段的人类可读名,用于默认消息里的 {@code :attribute} */
    public Map<String, String> attributes() {
        return Map.of();
    }

    /** 校验前改数据(补 slug、规范化标签等) */
    protected void prepareForValidation() {
        // 默认什么都不做
    }

    /** 挂跨字段校验 */
    protected void withValidator(Validator validator) {
        // 默认什么都不做
    }

    /**
     * 校验失败怎么办。
     *
     * <p>默认抛 {@link ValidationException},由框架的异常处理器转成
     * 422 JSON 或回跳。覆盖它只在需要特殊响应时 —— 比如重定向到别的页面。
     */
    protected void failedValidation(ErrorBag errors) {
        throw new ValidationException(errors, Messages.defaults().headline());
    }

    protected String locale() {
        return "zh_CN";
    }

    // ================= 框架调用 =================

    /**
     * 由参数绑定器调用。
     *
     * @throws HttpException 403(鉴权失败)或 422(校验失败)
     */
    public final void bind(Request request) {
        this.request = request;
        this.input = new LinkedHashMap<>(request.all());

        prepareForValidation();

        if (!authorize()) {
            throw new HttpException(403, "没有执行此操作的权限。");
        }

        Validator validator = Validator.make(input, rules())
                .messages(messages())
                .attributes(attributes())
                .locale(locale());

        withValidator(validator);

        if (validator.fails()) {
            failedValidation(validator.errors());
            // failedValidation 被覆盖且没抛异常时,不能让调用方拿到未校验的数据
            throw new IllegalStateException(
                    getClass().getSimpleName() + ".failedValidation() 没有抛出异常。"
                  + "\n  覆盖这个方法时必须抛出异常(或 HttpException),"
                  + "否则请求会带着**未通过校验的数据**继续执行。");
        }
        this.validated = validator.validated();
    }

    // ================= 子类可用的取值 =================

    /** 通过校验的数据。只含 rules() 里声明过的字段。 */
    public final Validated validated() {
        if (validated == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 还没有绑定请求。"
                  + "\n  FormRequest 由框架在参数绑定时调用 bind(request);"
                  + "\n  测试里请手动调用:req.bind(testRequest)。");
        }
        return validated;
    }

    public final Request request() {
        requireBound();
        return request;
    }

    /** 原始输入(未校验)。prepareForValidation 里用它读值。 */
    protected final Object input(String key) {
        return input == null ? null : Data.get(input, key);
    }

    protected final String input(String key, String fallback) {
        Object value = input(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 往输入里写值 —— prepareForValidation 的主要用途 */
    protected final void merge(String key, Object value) {
        if (input == null) input = new LinkedHashMap<>();
        Data.set(input, key, value);
    }

    protected final void remove(String key) {
        if (input != null) Data.remove(input, key);
    }

    private void requireBound() {
        if (request == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 还没有绑定请求(见 validated() 的说明)。");
        }
    }
}
