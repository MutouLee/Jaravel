package io.jaravel.routing;

/**
 * 路由 DSL(V2 验证用的最小子集)。
 *
 * 按 arity 重载,让不同签名的控制器方法引用都能作为参数:
 *   H0 —— Object m()
 *   H1 —— Object m(A)
 *   H2 —— Object m(A, B)
 *
 * 这个重载能否被 javac 正确解析,是 V2 要验证的第一件事。
 */
public interface Router {

    <C> Route get(String uri, H0<C> action);
    <C, A> Route get(String uri, H1<C, A> action);
    <C, A, B> Route get(String uri, H2<C, A, B> action);

    <C> Route post(String uri, H0<C> action);
    <C, A> Route post(String uri, H1<C, A> action);

    <C, A> Route put(String uri, H1<C, A> action);
    <C, A, B> Route put(String uri, H2<C, A, B> action);          // ← V2 发现:必须补全 arity
    <C, A> Route delete(String uri, H1<C, A> action);

    /** 资源路由:APT 需校验展开出的 7 个方法都存在 */
    Route resource(String uri, Class<?> controller);

    Route apiResource(String uri, Class<?> controller);

    /** 控制器方法引用:绑定实例 + 参数 */
    @FunctionalInterface interface H0<C>          { Object handle(C c); }
    @FunctionalInterface interface H1<C, A>       { Object handle(C c, A a); }
    @FunctionalInterface interface H2<C, A, B>    { Object handle(C c, A a, B b); }
}
