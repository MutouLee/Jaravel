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

    /** 开发期:丢弃已编译的模板,下次渲染重新编译 */
    default void invalidate() {}
}
