package io.jaravel.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 种子数据。
 *
 * <pre>{@code
 * public class DatabaseSeeder implements Seeder {
 *     public void run() {
 *         call(UserSeeder.class, PostSeeder.class);
 *     }
 * }
 * }</pre>
 */
public interface Seeder {

    void run();

    /**
     * 调用其它 Seeder。
     *
     * <p>默认实现带**环检测**:{@code A → B → A} 在 Laravel 里会一直跑到
     * 栈溢出,堆栈里全是框架帧,看不出是哪两个 Seeder 互相调用。
     */
    default void call(Class<? extends Seeder>... seeders) {
        Runner.current().call(List.of(seeders));
    }

    default void call(Class<? extends Seeder> seeder) {
        Runner.current().call(List.of(seeder));
    }

    /**
     * Seeder 的执行器。
     *
     * <p>单独一个类而不是把逻辑放进 default 方法:环检测需要跨调用保存状态,
     * 接口里没地方放。
     */
    final class Runner {

        private static final ThreadLocal<Runner> ACTIVE = new ThreadLocal<>();

        private final Set<Class<?>> inProgress = new LinkedHashSet<>();
        private final List<String> executed = new ArrayList<>();

        public static Runner current() {
            Runner runner = ACTIVE.get();
            if (runner == null) {
                throw new IllegalStateException(
                        "call() 只能在 Seeder.run() 内部使用。"
                      + "\n  顶层请用 new Seeder.Runner().run(new DatabaseSeeder())。");
            }
            return runner;
        }

        /** 执行一个 Seeder 及其 call 出来的全部下游 */
        public List<String> run(Seeder seeder) {
            Runner previous = ACTIVE.get();
            ACTIVE.set(this);
            try {
                invoke(seeder);
                return List.copyOf(executed);
            } finally {
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        }

        void call(List<Class<? extends Seeder>> seeders) {
            for (Class<? extends Seeder> type : seeders) {
                invoke(instantiate(type));
            }
        }

        private void invoke(Seeder seeder) {
            Class<?> type = seeder.getClass();
            if (!inProgress.add(type)) {
                var cycle = new ArrayList<>(inProgress);
                cycle.add(type);
                throw new IllegalStateException(
                        "Seeder 之间存在循环调用:"
                      + "\n  " + cycle.stream().map(Class::getSimpleName).reduce((a, b) -> a + " → " + b).orElse("")
                      + "\n  修复:把共享的数据抽成第三个 Seeder,让两边都 call 它,"
                      + "而不是互相 call。");
            }
            try {
                seeder.run();
                executed.add(type.getName());
            } finally {
                inProgress.remove(type);
            }
        }

        private static Seeder instantiate(Class<? extends Seeder> type) {
            try {
                var ctor = type.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "无法实例化 Seeder " + type.getName() + ":" + e.getMessage()
                      + "\n  Seeder 需要一个可访问的无参构造函数。", e);
            }
        }
    }
}
