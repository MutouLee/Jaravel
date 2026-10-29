package io.jaravel.core.context;

/**
 * 在请求作用域之外调用请求级 API 时抛出。
 *
 * <p>这是对 Laravel 的**有意改进**:Laravel 的 {@code auth()->user()} 在队列任务里
 * 静默返回 null,导致「任务里 Auth::user() 莫名为空」成为经典坑。Jaravel 明确报错,
 * 并在消息里给出正确做法。
 */
public class OutsideRequestException extends IllegalStateException {

    public OutsideRequestException(String api) {
        super(api + " 只能在请求处理过程中调用。\n"
            + "  当前不在任何请求作用域内(可能是队列任务、控制台命令或后台线程)。\n"
            + "  修复:\n"
            + "    · 队列任务:在 dispatch 时传入所需数据,不要在任务内读请求上下文;\n"
            + "    · 控制台命令:显式传参;\n"
            + "    · 确实需要:用 ExecutionScope.runIn(...) 建立合成作用域。");
    }

    public OutsideRequestException(String api, String extra) {
        super(api + " 只能在请求处理过程中调用。" + (extra == null ? "" : "\n  " + extra));
    }
}
