package io.jaravel.view;

import java.util.List;
import java.util.Optional;

/**
 * 模板指令要读的「当前请求」状态:CSRF 令牌、校验错误、旧输入。
 *
 * <p>{@code @csrf} / {@code @error} / {@code @old} 这三个指令需要的数据
 * 全在会话里(令牌、上一次请求闪存的 errors 与 old)。而
 * {@code jaravel-view} **不能**依赖 session / http —— 一个只渲染邮件
 * 模板的应用不该为此背上会话栈,ArchUnit 也钉着这条边界。
 *
 * <p>所以这里是一个 SPI:starter 在启动时装一个实现
 * ({@link #use}),它每次调用都去读**当时**的请求作用域。
 * 装的是一个长期有效的实现,不是一份快照 —— 快照会让
 * 「第二个请求读到第一个请求的令牌」。
 *
 * <h2>没装的时候</h2>
 * <p>{@code errors} / {@code old} 返回空(一个没有 Web 请求的渲染里
 * 「没有校验错误」是对的),而 {@link Source#csrfToken()} 由实现自己
 * 决定要不要报错 —— 默认实现报错,理由见 {@link Jb#csrfField()}:
 * 一个静默返回空令牌的 {@code @csrf} 会让表单在 419 上失败,
 * 而那个 419 与「模板里写了 @csrf」离得很远。
 */
public final class ViewRequestState {

    /** 由 starter 实现 —— 每次调用都读当时的请求作用域 */
    public interface Source {

        /** 当前会话的 CSRF 令牌 */
        String csrfToken();

        /** 某个字段的校验错误消息;没有就是空列表 */
        List<String> errors(String field);

        /** 某个字段的旧输入 */
        Optional<Object> old(String field);

        /**
         * 这个 guard 上有登录用户吗。{@code null} = 默认 guard。
         *
         * <p>{@code @auth} / {@code @guest} 的落点。
         */
        boolean authenticated(String guard);

        /**
         * 当前用户能做这件事吗 —— {@code @can} / {@code @cannot} 的落点。
         *
         * @param ability   能力名({@code "update"})
         * @param arguments 目标,通常是一个模型实例;也可以没有
         *                  (「能不能新建」那类操作还没有实例)
         */
        boolean allows(String ability, Object... arguments);
    }

    /**
     * 没装实现时的行为。
     *
     * <p>errors / old 给空值:邮件模板、CLI 渲染里「没有校验错误」
     * 是正确答案,报错只会让一个无关的模板渲染不出来。
     *
     * <p>csrf 报错:见类注释。
     */
    private static final Source NONE = new Source() {

        @Override
        public String csrfToken() {
            throw new IllegalStateException("""
                    @csrf 需要一个 Web 请求与会话,而当前渲染里没有。
                      出现这个错误的两种情况:
                        1. 在邮件/CLI 模板里写了 @csrf —— 删掉它,那里没有表单提交;
                        2. 单元测试里直接渲染模板 —— 用 FeatureTest 走真实管道,
                           或在测试里调 ViewRequestState.use(...) 装一个假实现。""");
        }

        @Override
        public List<String> errors(String field) {
            return List.of();
        }

        @Override
        public Optional<Object> old(String field) {
            return Optional.empty();
        }

        /**
         * 没有请求时「没有登录用户」。
         *
         * <p>这是对的而不是将就:一封从队列任务里渲染的邮件模板,
         * 它的 {@code @auth} 块本来就不该出现 —— 那里没有「当前用户」
         * 这个概念。报错会让一个无关的邮件渲染失败。
         */
        @Override
        public boolean authenticated(String guard) {
            return false;
        }

        /**
         * 没有请求时**拒绝**一切授权判断。
         *
         * <p>与 {@link #authenticated} 同理,但方向更重要:
         * 授权的默认值只能是拒绝。一个「没有上下文就放过」的 @can
         * 会在邮件模板里把管理员按钮渲染给所有人。
         */
        @Override
        public boolean allows(String ability, Object... arguments) {
            return false;
        }
    };

    private static volatile Source source = NONE;

    private ViewRequestState() {
    }

    public static void use(Source configured) {
        source = configured == null ? NONE : configured;
    }

    public static Source current() {
        return source;
    }

    /** 测试用:回到「没有请求」的状态 */
    public static void reset() {
        source = NONE;
    }
}
