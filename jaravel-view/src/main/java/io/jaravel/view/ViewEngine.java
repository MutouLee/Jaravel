package io.jaravel.view;

import java.util.Map;

/**
 * 视图引擎契约。
 *
 * <p>逃生口(docs/04 §4):实现本接口即可换掉 jblade ——
 * Thymeleaf / Freemarker 的适配器实现同一个接口即可注册。
 */
public interface ViewEngine {

    /** 渲染模板。{@code name} 是点号路径,如 {@code posts.index}。 */
    String render(String name, Map<String, Object> data);

    boolean exists(String name);

    /**
     * 只渲染模板里的一个 {@code @fragment}(docs/04 §1.8)。
     *
     * <p>用于 htmx / turbo 的局部刷新:一次提交只换掉表格里那一行,
     * 而那一行的 HTML 与整页渲染出来的是**同一段模板代码**。
     *
     * <p>默认实现报错而不是回落到整页渲染:静默返回整页会让局部刷新
     * 把一整个 {@code <html>} 塞进一个 {@code <tr>} 里,
     * 而那种页面错乱很难联想到「引擎不支持片段」。
     */
    default String renderFragment(String name, String fragment, Map<String, Object> data) {
        throw new UnsupportedOperationException(
                "这个视图引擎(%s)不支持片段渲染。".formatted(getClass().getName())
              + "\n  @fragment 是 jblade 的能力;换了引擎的话局部刷新要自己写一个 partial 模板。");
    }

    /** 开发期:丢弃已编译的模板,下次渲染重新编译 */
    default void invalidate() {}
}
