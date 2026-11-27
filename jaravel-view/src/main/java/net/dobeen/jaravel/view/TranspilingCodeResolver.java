package net.dobeen.jaravel.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import gg.jte.CodeResolver;

/**
 * jte 索要模板源码时,按需把 {@code .jblade.html} 转写成 jte 源码。
 *
 * <p>不落盘,因此没有中间文件的同步问题;行号映射与转写同时产生,
 * 一一对应。开发模式的热重载靠 {@link #getLastModified} 反映源文件时间戳。
 *
 * <p>从 {@link JbladeEngine} 的内联类提出来,因为 v0.9 的构建期预编译器
 * 需要复用**同一套**转写逻辑 —— 两份实现迟早会分叉,而分叉的症状是
 * 「开发模式能跑、预编译打包后挂」。
 */
final class TranspilingCodeResolver implements CodeResolver {

    private final JbladeEngine.TemplateRepository repository;
    private final ClassLoader typeLoader;

    /** 模板名 → 行号映射。转写时写入,报错时查询。 */
    private final Map<String, LineMap> lineMaps = new ConcurrentHashMap<>();
    /** 模板名 → @params 声明。报错时用于反射出候选成员名,生成「是否想写 X ?」建议。 */
    private final Map<String, List<String>> declaredParams = new ConcurrentHashMap<>();
    /** 模板名 → 它调用的组件。热重载要把组件的时间戳算进调用方(见 getLastModified)。 */
    private final Map<String, List<String>> componentDeps = new ConcurrentHashMap<>();
    /**
     * 组件名 → 它的 @props 名字。属性袋的路由要查它,每个带组件的模板转写时都查。
     *
     * <p>带时间戳:开发模式下组件的 @props 改了就要重新扫 ——
     * 否则「加了一个 prop,组件里读不到」会一直是陈旧缓存的锅。
     * 生产模式(classpath)的时间戳是常量,等于缓存一次到底。
     */
    private final Map<String, CachedProps> propsCache = new ConcurrentHashMap<>();

    private record CachedProps(long stamp, Optional<List<String>> props) {}

    TranspilingCodeResolver(JbladeEngine.TemplateRepository repository, ClassLoader typeLoader) {
        this.repository = repository;
        this.typeLoader = typeLoader != null ? typeLoader : JbladeEngine.class.getClassLoader();
    }

    @Override
    public String resolve(String jtePath) {
        String viewName = JbladeEngine.fromJtePath(jtePath);
        String blade = repository.read(viewName).orElse(null);
        if (blade == null) return null;

        var result = JbladeTranspiler.transpile(blade, viewName + JbladeEngine.SUFFIX,
                this::propsOf, this::classComponentOf);
        lineMaps.put(viewName, result.lineMap());
        declaredParams.put(viewName, result.declaredParams());
        componentDeps.put(viewName, result.components());
        return result.jteSource();
    }

    /** 组件视图 → 类组件信息;不是类组件时返回 empty(按匿名组件处理) */
    private Optional<JbladeTranspiler.ClassComponent> classComponentOf(String componentView) {
        return ClassComponentResolver.resolve(
                componentView, repository.read(componentView), typeLoader);
    }

    /**
     * 组件的 {@code @props} 声明 —— 属性袋要靠它分出「prop」和「透传」。
     *
     * <p>只扫组件源码的 {@code @props} 那一行,不做完整转写:
     * 这个查询在每次转写一个带组件的模板时都会发生。
     */
    private Optional<List<String>> propsOf(String componentView) {
        long stamp = repository.lastModified(componentView);
        CachedProps hit = propsCache.get(componentView);
        if (hit != null && hit.stamp() == stamp) return hit.props();

        Optional<List<String>> props =
                repository.read(componentView).flatMap(JbladeEngine::declaredPropNames);
        propsCache.put(componentView, new CachedProps(stamp, props));
        return props;
    }

    /**
     * 热重载:**一个模板的时间戳要算上它调用的组件**。
     *
     * <p>不然:给组件的 {@code @props} 加一个属性,调用方的生成码里
     * 那个属性仍然走属性袋(路由是转写期决定的),而调用方的文件没动过,
     * jte 不会重新转写它。症状是「我声明了 prop,组件里却读不到」——
     * 改完组件再去动一下调用方的文件就好了,所以极难复现。
     */
    @Override
    public long getLastModified(String jtePath) {
        String viewName = JbladeEngine.fromJtePath(jtePath);
        long own = repository.lastModified(viewName);
        for (String component : componentDeps.getOrDefault(viewName, List.of())) {
            own = Math.max(own, repository.lastModified(component));
        }
        return own;
    }

    @Override
    public boolean exists(String jtePath) {
        return repository.read(JbladeEngine.fromJtePath(jtePath)).isPresent();
    }

    @Override
    public List<String> resolveAllTemplateNames() {
        return repository.allNames().stream().map(JbladeEngine::toJtePath).toList();
    }

    // ================= 预编译 / 诊断需要的访问口 =================

    JbladeEngine.TemplateRepository repository() { return repository; }

    LineMap lineMap(String viewName) { return lineMaps.get(viewName); }

    List<String> declaredParams(String viewName) {
        return declaredParams.getOrDefault(viewName, List.of());
    }

    Map<String, LineMap> lineMaps() { return lineMaps; }

    Map<String, List<String>> declaredParamsMap() { return declaredParams; }

    /** 预编译产物还原 metadata 时使用 */
    void putMetadata(String viewName, LineMap lineMap, List<String> params) {
        if (lineMap != null) lineMaps.put(viewName, lineMap);
        if (params != null) declaredParams.put(viewName, List.copyOf(params));
    }

    void invalidate() {
        lineMaps.clear();
        componentDeps.clear();
        propsCache.clear();
    }

    /** 调试用:返回某个模板转写后的 jte 源码 */
    String transpiledSource(String viewName) {
        String blade = repository.read(viewName)
                .orElseThrow(() -> new JbladeException(viewName + JbladeEngine.SUFFIX, 0, "模板不存在"));
        return JbladeTranspiler.transpile(blade, viewName + JbladeEngine.SUFFIX,
                this::propsOf, this::classComponentOf).jteSource();
    }
}
