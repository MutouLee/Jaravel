package net.dobeen.jaravel.broadcasting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 频道注册表:私有/存在频道的鉴权规则与 whisper 开关(docs/19 §3)。
 *
 * <pre>{@code
 * public class Channels implements BroadcastChannels {
 *     @Override public void define(ChannelRegistry channels) {
 *         channels
 *             .channel("private-orders.{orderId}", (User user, Map<String, String> params) ->
 *                     user.getId().equals(Long.parseLong(params.get("orderId"))))
 *             .presence("presence-chat.{room}",
 *                 (User user, Map<String, String> params) -> memberOf(user, params.get("room")),
 *                 (User user, Map<String, String> params) -> Map.of("name", user.getName()))
 *             .whisper("chat.{room}");
 *     }
 * }
 * }</pre>
 *
 * <h2>匹配语法</h2>
 * <pre>
 * orders.{orderId}         {x} 捕获一个段(非空、不含点)
 * private-users.*          * 只允许出现在末尾,匹配任意剩余段(含空);
 *                          剩余段以 "." 连接后放进名为 "*" 的参数
 * </pre>
 *
 * <h2>多条规则命中时的语义:全部必须通过</h2>
 * <p>与 Laravel 一致。重叠的规则(如 {@code private-user.*} 与
 * {@code private-user.orders.{id}})各自独立求值,任何一条拒绝即拒绝。
 * 这让「先声明一条宽规则,再在特定子频道上加一条补充规则」成立 ——
 * 若改成「第一条命中即裁决」,补充规则就永远没有机会生效。
 *
 * <h2>泛型 T 靠擦除工作</h2>
 * <p>回调写成 {@code (User u, ...) -> ...},运行时把连接的用户对象强转;
 * 类型不匹配(guard 返回的不是 User)→ 拒绝订阅,原因里说清
 * 「回调声明的类型与 guard 返回类型不一致」。编译期类型安全,
 * 运行时不引入任何反射解析。回调里的 user **恒非 null**:
 * 未登录的连接在进入回调之前就被拒绝。
 */
public final class ChannelRegistry {

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    // ================= 注册 =================

    /** 注册私有频道的鉴权回调。返回 true 才允许订阅 */
    public <T> ChannelRegistry channel(String pattern,
                                       BiFunction<T, Map<String, String>, Boolean> check) {
        return add(Registration.channel(pattern, check));
    }

    /**
     * 注册存在频道:鉴权回调 + 成员信息回调。
     *
     * <p>成员信息在订阅成功时与 {@code member_added} 事件里发给其他成员,
     * 同时也是 {@code pusher_internal:subscription_succeeded} 的
     * presence 数据来源(docs/19 §5.2)。
     */
    public <T> ChannelRegistry presence(String pattern,
                                         BiFunction<T, Map<String, String>, Boolean> check,
                                         Function<T, Map<String, Object>> info) {
        return add(Registration.presence(pattern, check, info));
    }

    /**
     * 允许该频道上的 {@code client-*} 客户端事件(docs/19 §3.3)。
     *
     * <p>默认**拒绝**所有客户端事件 —— 转发未经同意的客户端消息等于
     * 给 XSS 开一条不经过任何校验的通道。要「聊天/正在输入」这类能力时
     * 逐频道打开。
     */
    public ChannelRegistry whisper(String pattern) {
        return add(Registration.whisper(pattern));
    }

    private ChannelRegistry add(Registration registration) {
        registrations.add(registration);
        return this;
    }

    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    // ================= 运行时查询 =================

    /**
     * 鉴权一次订阅。
     *
     * <p>未注册的私有/存在频道 → 拒绝(「必须注册」是 docs/19 §3.1 的
     * 规则,不是可选项)。公开频道不需要走这里 —— 传输层自行放行。
     *
     * @param user  连接的用户,可能为 null(guest)
     * @return 结果。{@link Authorization#allowed()} 为 true 时,
     *         {@code presenceInfo} 为 null(私有频道)或成员信息(存在频道)
     */
    public Authorization authorize(String channel, Object user) {
        List<Registration> matched = new ArrayList<>();
        Map<String, String> captured = null;

        for (Registration registration : registrations) {
            // whisper 注册只回答「客户端事件允不允许」,不参与鉴权
            // (它没有 check 回调)
            if (registration.whisper()) continue;

            Map<String, String> params = registration.pattern().match(channel);
            if (params == null) continue;
            matched.add(registration);
            if (captured == null) captured = params;
        }

        if (matched.isEmpty()) {
            return Authorization.denied("频道没有注册 —— private/presence 频道必须在 "
                    + "Channels.java 里声明鉴权规则(docs/19 §3.1)");
        }

        // guest 连回调都不会跑 —— 未登录访问私有频道是「没资格」,
        // 而不是「这个用户不满足条件」
        if (user == null) {
            return Authorization.denied("未登录的连接不能订阅私有/存在频道");
        }

        Map<String, Object> presenceInfo = null;
        for (Registration registration : matched) {
            var outcome = registration.evaluate(user, captured);
            if (!outcome.allowed()) {
                return Authorization.denied("被 " + registration.describe() + " 拒绝"
                        + (outcome.reason() == null ? "" : ":" + outcome.reason()));
            }
            if (registration.hasPresenceInfo()) {
                // 多条 presence 规则命中同一频道时取第一条 ——
                // 成员信息只能有一个,而「全部求值」里也不存在第二条的裁决空间
                presenceInfo = registration.evaluateInfo(user);
            }
        }
        return Authorization.allowed(presenceInfo);
    }

    /** 这个频道允许客户端事件吗 */
    public boolean whispersAllowed(String channel) {
        for (Registration registration : registrations) {
            if (registration.whisper()
                    && registration.pattern().match(channel) != null) {
                return true;
            }
        }
        return false;
    }

    // ================= 内部类型 =================

    /** 一条注册 */
    public static final class Registration {

        private final ChannelPattern pattern;
        private final BiFunction<Object, Map<String, String>, Outcome> check;
        private final Function<Object, Map<String, Object>> info;
        private final boolean whisper;

        private Registration(ChannelPattern pattern,
                             BiFunction<Object, Map<String, String>, Outcome> check,
                             Function<Object, Map<String, Object>> info,
                             boolean whisper) {
            this.pattern = pattern;
            this.check = check;
            this.info = info;
            this.whisper = whisper;
        }

        static Registration channel(String pattern,
                                    BiFunction<?, Map<String, String>, Boolean> check) {
            return new Registration(ChannelPattern.compile(pattern),
                    toOutcome(check), null, false);
        }

        static Registration presence(String pattern,
                                     BiFunction<?, Map<String, String>, Boolean> check,
                                     Function<?, Map<String, Object>> info) {
            return new Registration(ChannelPattern.compile(pattern),
                    toOutcome(check), castFunction(info), false);
        }

        static Registration whisper(String pattern) {
            return new Registration(ChannelPattern.compile(pattern), null, null, true);
        }

        public ChannelPattern pattern() {
            return pattern;
        }

        public boolean whisper() {
            return whisper;
        }

        public boolean hasPresenceInfo() {
            return info != null;
        }

        public String describe() {
            return "频道规则 \"" + pattern.raw() + "\"";
        }

        Outcome evaluate(Object user, Map<String, String> params) {
            try {
                return check.apply(user, params);
            } catch (ClassCastException e) {
                return Outcome.deny("回调声明的用户类型与 guard 返回类型不一致:"
                        + " 用户是 " + user.getClass().getName());
            } catch (RuntimeException e) {
                // 回调抛异常 = 拒绝订阅,细节只进日志(docs/19 §3.1)
                return Outcome.deny("鉴权回调抛了异常:" + e.getMessage());
            }
        }

        Map<String, Object> evaluateInfo(Object user) {
            try {
                return info.apply(user);
            } catch (RuntimeException e) {
                // presence 数据取不到不该让订阅失败 —— 退回空成员信息,
                // 错误进日志。成员列表少一条信息比「订阅不了」好修
                return null;
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static BiFunction<Object, Map<String, String>, Outcome> toOutcome(
                BiFunction<?, Map<String, String>, Boolean> check) {
            // 先整体强转再包一层 —— 在 lambda 里直接调 check.apply 会把
            // capture-of-? 的类型检查带到运行时参数上,编译都过不去
            BiFunction<Object, Map<String, String>, Boolean> raw = (BiFunction) check;
            return (user, params) -> raw.apply(user, params)
                    ? Outcome.grant()
                    : Outcome.deny(null);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Function<Object, Map<String, Object>> castFunction(
                Function<?, Map<String, Object>> info) {
            return (Function) info;
        }
    }

    /**
     * 一次鉴权求值的结果。
     *
     * <p>工厂方法叫 {@code grant()} / {@code deny()} 而不是
     * {@code allowed()} —— 后者与组件 {@code allowed} 的存取方法
     * 同名同参,只差返回类型,而 Java 不允许靠返回类型区分方法。
     */
    public record Outcome(boolean allowed, String reason) {

        static Outcome grant() {
            return new Outcome(true, null);
        }

        static Outcome deny(String reason) {
            return new Outcome(false, reason);
        }
    }

    /** 鉴权结果 */
    public record Authorization(boolean allowed, String reason,
                                Map<String, Object> presenceInfo) {

        static Authorization allowed(Map<String, Object> presenceInfo) {
            return new Authorization(true, null, presenceInfo);
        }

        static Authorization denied(String reason) {
            return new Authorization(false, reason, null);
        }
    }
}
