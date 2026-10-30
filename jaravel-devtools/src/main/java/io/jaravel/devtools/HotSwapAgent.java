package io.jaravel.devtools;

import java.lang.instrument.Instrumentation;

/**
 * JVM agent —— L2 热替换需要的 {@link Instrumentation} 从这里来。
 *
 * <p>两个入口:
 * <ul>
 *   <li>{@link #premain} —— 用户加了 {@code -javaagent:jaravel-devtools.jar};</li>
 *   <li>{@link #agentmain} —— {@link HotSwapper} 在运行时自挂载。</li>
 * </ul>
 *
 * <h2>★ 为什么把 Instrumentation 放进 System.getProperties()</h2>
 * <p>看起来很脏,但它是这里唯一可靠的通道。
 *
 * <p>agent 类由**系统类加载器**加载(JVM 规定),而
 * {@code HotSwapper} 跑在应用的类加载器里 —— 开发期尤其如此:
 * spring-boot-devtools 把整个应用(包括 Jaravel 的 jar)放进一个
 * {@code RestartClassLoader}。于是两边看到的是**两个不同的
 * {@code HotSwapAgent} 类**,一个静态字段传不过去。
 *
 * <p>{@code System.getProperties()} 是一个 {@code Hashtable},
 * 由引导类加载器持有,对所有类加载器可见,而且 {@code put} 接受
 * 任意 {@code Object}(只有 {@code getProperty} 那条路径限定 String)。
 * 这是 JVM 里少数几个真正全局的位置之一。
 *
 * <p>替代方案是让 agent 把 Instrumentation 塞进一个由引导类加载器
 * 加载的桥接类,那需要再生成一个 jar 并 append 到 bootstrap
 * classpath —— 为一个开发期功能增加的复杂度不值得。
 *
 * <p>⚠ 这个键是实现细节,应用不该读它。
 */
public final class HotSwapAgent {

    /** Instrumentation 在系统属性里的键 */
    public static final String INSTRUMENTATION_KEY = "io.jaravel.devtools.instrumentation";

    private HotSwapAgent() {
    }

    /** {@code -javaagent:} 的入口 */
    public static void premain(String args, Instrumentation instrumentation) {
        publish(instrumentation);
    }

    /** 运行时自挂载的入口 */
    public static void agentmain(String args, Instrumentation instrumentation) {
        publish(instrumentation);
    }

    private static void publish(Instrumentation instrumentation) {
        System.getProperties().put(INSTRUMENTATION_KEY, instrumentation);
    }
}
