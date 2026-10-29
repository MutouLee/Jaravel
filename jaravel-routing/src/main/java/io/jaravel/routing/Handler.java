package io.jaravel.routing;

import java.io.Serializable;

/**
 * 控制器方法引用的函数式接口族,按 arity 分。
 *
 * <p><b>V2 验证的结论:</b>javac 能正确按 arity 解析方法引用重载
 * ({@code C::index} → H0、{@code C::show} → H1,无歧义),
 * 但**漏一个 arity 重载就会编译失败**,报错为
 * {@code invalid method reference / unexpected instance method ... found in unbound lookup}。
 * 因此必须覆盖全部常用 arity。
 *
 * <p><b>为什么 extends Serializable:</b>方法引用在运行时不暴露目标类与方法名。
 * 继承 {@code Serializable} 后 JVM 会为 lambda 生成 {@code writeReplace()},
 * 由此可取到 {@code SerializedLambda} —— 其中含 implClass / implMethodName /
 * implMethodSignature。这是 jOOQ、QueryDSL 等库的既有手法。
 * 仅在**路由注册时**调用一次,不在请求路径上,无性能影响。
 *
 * <p>v0.2 的 APT 会在编译期生成零反射的调用适配器;本机制作为运行时回落保留
 * (动态注册路由时 APT 看不到,必须有这条路)。
 *
 * <p>第一个类型参数恒为控制器类型 {@code C}:{@code UserController::show} 是
 * 未绑定实例的方法引用,转换后第一个参数就是接收者。
 */
public final class Handler {

    private Handler() {}

    @FunctionalInterface public interface H0<C> extends Serializable             { Object handle(C c); }
    @FunctionalInterface public interface H1<C, A> extends Serializable          { Object handle(C c, A a); }
    @FunctionalInterface public interface H2<C, A, B> extends Serializable       { Object handle(C c, A a, B b); }
    @FunctionalInterface public interface H3<C, A, B, D> extends Serializable    { Object handle(C c, A a, B b, D d); }
    @FunctionalInterface public interface H4<C, A, B, D, E> extends Serializable { Object handle(C c, A a, B b, D d, E e); }

    /** 运行时的统一调用形态。路由层把上面任一 H* 包装成它。 */
    @FunctionalInterface
    public interface Invoker {
        Object invoke(Object controller, Object[] args);
    }

    // ---------- H* → Invoker 的包装 ----------

    @SuppressWarnings("unchecked")
    static <C> Invoker wrap(H0<C> h) {
        return (c, args) -> h.handle((C) c);
    }

    @SuppressWarnings("unchecked")
    static <C, A> Invoker wrap(H1<C, A> h) {
        return (c, args) -> h.handle((C) c, (A) args[0]);
    }

    @SuppressWarnings("unchecked")
    static <C, A, B> Invoker wrap(H2<C, A, B> h) {
        return (c, args) -> h.handle((C) c, (A) args[0], (B) args[1]);
    }

    @SuppressWarnings("unchecked")
    static <C, A, B, D> Invoker wrap(H3<C, A, B, D> h) {
        return (c, args) -> h.handle((C) c, (A) args[0], (B) args[1], (D) args[2]);
    }

    @SuppressWarnings("unchecked")
    static <C, A, B, D, E> Invoker wrap(H4<C, A, B, D, E> h) {
        return (c, args) -> h.handle((C) c, (A) args[0], (B) args[1], (D) args[2], (E) args[3]);
    }
}
