package runtime;

import java.util.List;

/**
 * 一对多关联句柄。
 *
 * 关键:Rel 实例由 Model 基类按名字缓存(见 Model.rel()),
 * **不作为用户模型的字段存在** —— 因为 public 字段既无法拦截懒加载,
 * 又会被用户的显式初始化器静默覆盖(已验证)。
 */
public final class Rel<T> {

    private final String name;
    private final Class<T> target;
    private List<T> loaded;          // null = 未预加载

    Rel(String name, Class<T> target) {
        this.name = name;
        this.target = target;
    }

    public boolean isLoaded() { return loaded != null; }

    /** 由预加载器回填 */
    public void setLoaded(List<T> items) { this.loaded = items; }

    public List<T> get() {
        if (loaded != null) return loaded;
        // 真实实现:此处检查 preventLazyLoading 配置,
        // 开启则抛 LazyLoadingViolationException(见 docs/06 §4.3)
        throw new IllegalStateException(
            "关联 " + name + " 未预加载。修复:在查询上加 .with(" + name + ")");
    }

    public String name()       { return name; }
    public Class<T> target()   { return target; }
    @Override public String toString() {
        return "Rel<" + target.getSimpleName() + ">(" + name + (isLoaded() ? ", loaded=" + loaded.size() : ", not loaded") + ")";
    }
}
