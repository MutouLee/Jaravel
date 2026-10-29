package io.jaravel.devtools;

/**
 * 一次文件变更该用哪一层机制(docs/15 §2)。
 *
 * <p>顺序就是成本顺序:每次变更选**成本最低的可行机制**。
 */
public enum ReloadStrategy {

    /** 什么都不用做 —— 变更文件与运行时无关(日志、.git、IDE 临时文件) */
    IGNORE(0, "忽略"),

    /**
     * L1:资源热替换。模板、配置、语言包、静态文件。
     *
     * <p>0 重启,毫秒级。
     */
    RESOURCE(1, "资源热替换"),

    /**
     * L2:方法体热替换(HotSwap)。
     *
     * <p>0 重启,但**依赖 JVM 能力**:标准 HotSwap 只支持方法体,
     * 要改字段/增删方法需要 JBR 或 DCEVM。本层不可用时自动降到
     * {@link #RESTART_LOADER} —— 所以它是优化而非保证。
     */
    HOTSWAP(2, "方法体热替换"),

    /**
     * L3:应用类加载器重启。结构性变更(新增类、改签名、动了注解)。
     *
     * <p>目标 &lt; 2s。三方依赖的类不重新加载,这是能做到 2s 的关键。
     */
    RESTART_LOADER(3, "类加载器重启"),

    /**
     * L4:全量 JVM 重启。只在 pom.xml 改动时。
     *
     * <p>~10s。依赖变了之后 classpath 本身就不同了,没有更便宜的办法。
     */
    RESTART_JVM(4, "JVM 重启");

    private final int cost;
    private final String label;

    ReloadStrategy(int cost, String label) {
        this.cost = cost;
        this.label = label;
    }

    public int cost() {
        return cost;
    }

    public String label() {
        return label;
    }

    /**
     * 一批变更里取成本最高的那个 —— 它决定整批的处理方式。
     *
     * <p>取最高而不是最低:同时改了模板与新增了一个类时,
     * 只做资源替换会让新类不生效,而那个不一致比多等 2 秒糟得多。
     */
    public ReloadStrategy escalate(ReloadStrategy other) {
        return other.cost > this.cost ? other : this;
    }
}
