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
        invoke(job, java.util.Map.of());
    }

    /**
     * 执行任务,并优先用 {@code contextual} 里的实例满足参数。
     *
     * <p>用途:批次回调的 {@code handle(Batch batch)}、链中断回调的
     * {@code handle(ChainFailure failure)}。这些对象**不在容器里** ——
     * 它们属于「这一次执行」,由信封上下文决定(见 {@link JobContext})。
     *
     * <p>上下文优先于容器。反过来的话,一个应用里恰好注册了
     * {@code Batch} 类型的 bean 就会把回调拿到的批次换成那个 bean ——
     * 而那种冲突极难发现。
     */
    public void invoke(ShouldQueue job, java.util.Map<Class<?>, Object> contextual) {
        Method handle = findHandle(job.getClass());

        Object[] args = new Object[handle.getParameterCount()];
        Class<?>[] types = handle.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            Object fromContext = contextual.get(types[i]);
            if (fromContext != null) {
                args[i] = fromContext;
                continue;
            }
            try {
                args[i] = resolver.apply(types[i]);
            } catch (RuntimeException e) {
                if (contextual.containsKey(types[i])) {
                    // 上下文里有这个键但值是 null —— 比如批次行已被 prune 掉。
                    // 报一个说得清的错,而不是让容器的「找不到 bean」
                    // 把真正的原因盖掉
                    throw new JobHandlerException("""
                            回调任务 %s 需要一个 %s,但它的来源(信封上下文)是空的。
                              常见原因:对应的批次记录已被删除或 prune 掉了。
                              prune 只应清理 finished_at 非空的批次。"""
                            .formatted(job.getClass().getSimpleName(),
                                       types[i].getSimpleName()), e);
                }
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
