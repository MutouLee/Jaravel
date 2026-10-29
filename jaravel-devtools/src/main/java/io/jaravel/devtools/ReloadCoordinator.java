package io.jaravel.devtools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把监听、分类、执行接起来。
 *
 * <pre>{@code
 * var coordinator = new ReloadCoordinator(gate)
 *         .onResource(() -> views.invalidate())
 *         .onRestartLoader(() -> context.restart());
 *
 * new FileWatcher(roots, coordinator::handle).start();
 * }</pre>
 *
 * <h2>谁决定「什么时候」</h2>
 * <p>本类只决定「怎么做」。**什么时候做**由 {@link RestartGate} 决定 ——
 * 资源变更立即生效(代价是毫秒级,没必要等),而重启类变更
 * 登记为待处理,由下一个请求触发。
 *
 * <p>这个分工的理由见 RestartGate 的类注释:连续编辑 10 个文件
 * 不该触发 10 次重启。
 */
public final class ReloadCoordinator {

    private final RestartGate gate;
    private final ChangeClassifier classifier;

    private Runnable onResource = () -> {};
    private Runnable onRestartLoader = () -> {};
    private Runnable onHotswap;
    private Runnable onRestartJvm;

    private final List<Listener> listeners = new ArrayList<>();

    private final AtomicLong resourceReloads = new AtomicLong();
    private volatile ReloadStrategy lastStrategy = ReloadStrategy.IGNORE;
    private volatile Set<Path> lastChanged = Set.of();

    public ReloadCoordinator(RestartGate gate) {
        this(gate, new ChangeClassifier());
    }

    public ReloadCoordinator(RestartGate gate, ChangeClassifier classifier) {
        this.gate = gate;
        this.classifier = classifier;
    }

    // ================= 装配 =================

    /** L1:丢弃模板缓存、重读配置 */
    public ReloadCoordinator onResource(Runnable action) {
        this.onResource = action;
        return this;
    }

    /** L3:关闭旧容器、丢弃 app 类加载器、重建 */
    public ReloadCoordinator onRestartLoader(Runnable action) {
        this.onRestartLoader = action;
        return this;
    }

    /** L2。不设置则方法体变更降级到 L3 —— 那是兜底保证(docs/15 §3.4) */
    public ReloadCoordinator onHotswap(Runnable action) {
        this.onHotswap = action;
        return this;
    }

    /**
     * L4。不设置时只打印提示。
     *
     * <p>默认不自动重启 JVM:那需要一个父进程(`jaravel serve`),
     * 而在 IDE 里跑的时候自作主张退出进程会非常突然。
     * 提示用户手动重启是更好的默认。
     */
    public ReloadCoordinator onRestartJvm(Runnable action) {
        this.onRestartJvm = action;
        return this;
    }

    public ReloadCoordinator addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    // ================= 事件入口 =================

    /** 由 {@link FileWatcher} 调用 */
    public void handle(Set<Path> changed) {
        ReloadStrategy strategy = classifier.classify(changed);
        lastStrategy = strategy;
        lastChanged = Set.copyOf(changed);

        listeners.forEach(l -> l.onClassified(changed, strategy));

        switch (strategy) {
            case IGNORE -> { }

            // 资源:立即做。毫秒级,没有「攒着等请求」的必要,
            // 而立即生效让「改模板 → 刷新」之间没有任何额外步骤
            case RESOURCE -> {
                onResource.run();
                resourceReloads.incrementAndGet();
                listeners.forEach(l -> l.onApplied(ReloadStrategy.RESOURCE));
            }

            // 重启类:登记,由下一个请求触发(见 RestartGate 的增强二)
            case HOTSWAP, RESTART_LOADER -> gate.recordChange(strategy);

            case RESTART_JVM -> {
                if (onRestartJvm != null) {
                    onRestartJvm.run();
                } else {
                    System.out.println("""

                            [jaravel] 检测到 %s 变更,需要**手动重启** JVM。
                              pom.xml / .env 的变更会改变 classpath 或启动前的属性,
                              类加载器重启处理不了它们。"""
                            .formatted(describe(changed)));
                }
            }
        }
    }

    /**
     * 把待处理的变更真正执行掉。由请求侧经 {@link RestartGate#enter} 调用。
     */
    public RestartGate.Reloader reloader() {
        return strategy -> {
            long start = System.nanoTime();
            switch (strategy) {
                case HOTSWAP -> {
                    if (onHotswap != null) {
                        onHotswap.run();
                    } else {
                        // L2 不可用 —— 降级。这是刻意的:核心体验不押在
                        // 非标准 JVM 上,L3 的 < 2s 是兜底保证
                        onRestartLoader.run();
                    }
                }
                case RESTART_LOADER -> onRestartLoader.run();
                case RESOURCE -> onResource.run();
                default -> { }
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            listeners.forEach(l -> l.onApplied(strategy));
            System.out.printf("[jaravel] %s 完成,耗时 %d ms%n", strategy.label(), millis);
        };
    }

    /** 请求进入时调用:必要时先重载再放行 */
    public boolean enterRequest() {
        return gate.enter(reloader());
    }

    // ================= 诊断 =================

    public ReloadStrategy lastStrategy()  { return lastStrategy; }
    public Set<Path> lastChanged()        { return lastChanged; }
    public long resourceReloads()         { return resourceReloads.get(); }
    public RestartGate gate()             { return gate; }

    private static String describe(Set<Path> changed) {
        return changed.stream()
                .map(p -> p.getFileName() == null ? p.toString() : p.getFileName().toString())
                .distinct().limit(3)
                .reduce((a, b) -> a + ", " + b).orElse("文件");
    }

    /** 观察重载过程 —— 给调试栏与测试用 */
    public interface Listener {
        default void onClassified(Set<Path> changed, ReloadStrategy strategy) {}

        default void onApplied(ReloadStrategy strategy) {}
    }

    /** 合理的监听根目录。不存在的会被 FileWatcher 跳过。 */
    public static List<Path> conventionalRoots() {
        return List.of(
                Path.of("src", "main", "java"),
                Path.of("src", "main", "resources"),
                Path.of("target", "classes"),
                Path.of("."));          // 为了 pom.xml 与 .env
    }

    /** 默认的等待上限 —— 与 RestartGate 的默认一致,放在这里便于文档引用 */
    public static Duration defaultMaxWait() {
        return Duration.ofSeconds(10);
    }
}
