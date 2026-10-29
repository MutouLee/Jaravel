package io.jaravel.queue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 调用任务的 {@code handle(...)},参数从容器注入。
 *
 * <pre>{@code
 * public void handle(Mailer mailer, InvoiceRenderer renderer) { ... }
 * }</pre>
 *
 * <h2>为什么 handle 不在接口上</h2>
 * <p>见 {@link ShouldQueue} 的类注释:参数因任务而异,接口化会迫使
 * 所有任务都用无参签名,然后在方法体里手写
 * {@code Application.make(Mailer.class)} —— 那就把依赖藏起来了,
 * 任务也变得没法在单元测试里直接 new 出来调。
 *
 * <h2>代价与补偿</h2>
 * <p>签名错误要到运行时才发现。所以这里的错误消息必须把
 * 「它找到了什么、期望什么」都说清楚 —— 一个
 * {@code NoSuchMethodException} 对用户毫无帮助。
 */
public final class JobInvoker {

    /** 类型 → 实例。实践中是容器的 {@code make}。 */
    private final Function<Class<?>, Object> resolver;

    public JobInvoker(Function<Class<?>, Object> resolver) {
        this.resolver = resolver;
    }

    /** 不注入任何依赖 —— 只能调无参的 handle。测试用。 */
    public static JobInvoker withoutInjection() {
        return new JobInvoker(type -> {
            throw new IllegalStateException("""
                    这个 JobInvoker 不支持依赖注入,但 handle 需要一个 %s。
                      真实运行时由容器注入;单元测试里直接 new 任务并调它的
                      handle(...) 更直接,不必经过 JobInvoker。"""
                    .formatted(type.getSimpleName()));
        });
    }

    /**
     * 执行任务。
     *
     * @throws JobHandlerException 找不到可调用的 handle
     */
    public void invoke(ShouldQueue job) {
        Method handle = findHandle(job.getClass());

        Object[] args = new Object[handle.getParameterCount()];
        Class<?>[] types = handle.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            try {
                args[i] = resolver.apply(types[i]);
            } catch (RuntimeException e) {
                throw new JobHandlerException("""
                        无法为 %s.handle 的第 %d 个参数(%s)解析依赖:%s
                          handle 的参数由容器注入 —— 给它的实现类加
                          @Component,或在 @Configuration 里提供 @Bean。"""
                        .formatted(job.getClass().getSimpleName(), i + 1,
                                   types[i].getSimpleName(), e.getMessage()), e);
            }
        }

        try {
            handle.setAccessible(true);
            handle.invoke(job, args);
        } catch (InvocationTargetException e) {
            // 任务自己抛的异常要**原样**冒出去 —— Worker 要靠它判断
            // 「是否可重试」,而包一层反射异常会让那个判断失效
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new JobHandlerException(
                    "任务 " + job.getClass().getSimpleName() + " 执行失败", cause);
        } catch (IllegalAccessException e) {
            throw new JobHandlerException(
                    "无法调用 " + job.getClass().getSimpleName() + ".handle:" + e.getMessage(), e);
        }
    }

    /**
     * 找 handle 方法。
     *
     * <p>要求**恰好一个**名为 handle 的方法。重载的话无从选择 ——
     * 按参数个数挑会让「加一个注入参数」悄悄改变被调用的那一个。
     */
    static Method findHandle(Class<?> jobType) {
        List<Method> candidates = new ArrayList<>();
        for (Method m : jobType.getMethods()) {
            if (m.getName().equals("handle") && !m.isSynthetic()) candidates.add(m);
        }
        // getMethods 不含 private/package-private 的,补一遍自己声明的
        for (Method m : jobType.getDeclaredMethods()) {
            if (m.getName().equals("handle") && !m.isSynthetic()
                    && !candidates.contains(m)) {
                candidates.add(m);
            }
        }

        if (candidates.isEmpty()) {
            throw new JobHandlerException("""
                    任务 %s 没有 handle 方法。
                      任务的执行体是一个名为 handle 的方法,参数从容器注入:
                        public void handle() { ... }
                        public void handle(Mailer mailer) { ... }
                      它**不在 ShouldQueue 接口上** —— 因为参数因任务而异
                      (见 ShouldQueue 的类注释)。
                      该类声明的方法:%s"""
                    .formatted(jobType.getName(), methodNames(jobType)), null);
        }
        if (candidates.size() > 1) {
            throw new JobHandlerException("""
                    任务 %s 有 %d 个 handle 方法,无从选择。
                      重载的 handle 不被支持:按参数个数挑会让「加一个注入参数」
                      悄悄改变被调用的那一个,而那是个没有任何征兆的行为变化。
                      候选:%s"""
                    .formatted(jobType.getName(), candidates.size(),
                               candidates.stream().map(Method::toGenericString).toList()),
                    null);
        }
        return candidates.get(0);
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /** handle 的解析或调用问题。**不可重试** —— 代码不改,再试也一样。 */
    public static class JobHandlerException extends RuntimeException {
        public JobHandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
