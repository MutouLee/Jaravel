package io.jaravel.facades;

import java.util.Map;

import io.jaravel.core.facade.Facade;
import io.jaravel.view.ViewEngine;

/**
 * 视图门面。
 *
 * <p>控制器通常直接返回 {@link io.jaravel.http.View},不需要用这个门面;
 * 它的用途是「渲染成字符串」(邮件正文、导出 HTML)与存在性检查。
 *
 * <p>命名为 {@code Views} 而非 {@code View} 是为了避开
 * {@link io.jaravel.http.View} —— 后者是控制器返回值,更常用,
 * 应当占据更短的名字。
 */
public final class Views extends Facade {

    private Views() {}

    private static ViewEngine engine() {
        return resolve(ViewEngine.class);
    }

    /** 渲染为字符串 */
    public static String render(String name, Map<String, Object> data) {
        return engine().render(name, data);
    }

    public static boolean exists(String name) {
        return engine().exists(name);
    }

    /** 开发期:丢弃已编译的模板 */
    public static void invalidate() {
        engine().invalidate();
    }
}
