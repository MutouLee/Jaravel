package io.jaravel.auth.access;

import java.util.Optional;

/**
 * 授权结果。
 *
 * <p>比 {@code boolean} 多两样东西:**原因**与**状态码**。
 *
 * <pre>{@code
 * Gate.define("publish", (User u, Post p) ->
 *     p.isComplete() ? Decision.allow() : Decision.deny("文章尚未完成", 422));
 * }</pre>
 *
 * <h2>为什么需要原因</h2>
 * <p>「403 Forbidden」对用户几乎没有信息量。真实场景里拒绝的原因差别很大:
 * 「不是你的文章」(改不了)、「文章还没填完」(填完就行)、
 * 「超出套餐额度」(升级套餐)—— 三者该给用户完全不同的提示。
 * 只返回 boolean 的话,这些信息在规则里产生、又在规则里丢掉。
 *
 * <p>状态码同理:权限不足是 403,而「数据状态不满足」更接近 422。
 * Laravel 的 {@code Response::deny($message, $code)} 做了同样的事。
 */
public final class Decision {

    private final boolean allowed;
    private final String reason;
    private final int status;

    private Decision(boolean allowed, String reason, int status) {
        this.allowed = allowed;
        this.reason = reason;
        this.status = status;
    }

    public static Decision allow() {
        return new Decision(true, null, 200);
    }

    public static Decision allow(String reason) {
        return new Decision(true, reason, 200);
    }

    public static Decision deny() {
        return new Decision(false, null, 403);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason, 403);
    }

    public static Decision deny(String reason, int status) {
        return new Decision(false, reason, status);
    }

    public static Decision of(boolean allowed) {
        return allowed ? allow() : deny();
    }

    public boolean allowed() {
        return allowed;
    }

    public boolean denied() {
        return !allowed;
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public int status() {
        return status;
    }

    /** 拒绝则抛 {@link AuthorizationException} */
    public Decision authorize(String ability) {
        if (allowed) return this;
        throw new AuthorizationException(ability, reason, status);
    }

    @Override public String toString() {
        return (allowed ? "允许" : "拒绝(" + status + ")")
             + (reason == null ? "" : ":" + reason);
    }
}
