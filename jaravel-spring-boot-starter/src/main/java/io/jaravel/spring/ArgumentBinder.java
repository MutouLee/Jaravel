package io.jaravel.spring;

import java.util.List;

import io.jaravel.core.container.Container;
import io.jaravel.http.Request;
import io.jaravel.routing.Route;
import io.jaravel.support.Arr;
import io.jaravel.support.Cast;

/**
 * 控制器方法的参数绑定。
 *
 * <p>绑定规则(docs/03 §3.2):
 * <ol>
 *   <li>{@link Request} 类型 → 注入请求本身;
 *   <li>简单类型(String / 数值 / boolean / 枚举 / 时间)→ 按**声明顺序**取路径参数,
 *       取不到再取查询串;
 *   <li>其余类型 → 从容器解析(服务注入)。
 * </ol>
 *
 * <p>v0.1 按位置绑定路径参数。v0.2 的 APT 会固化参数名(V2 验证已确认
 * 路径参数名与方法参数名的一致性可在编译期校验),届时改为按名绑定。
 */
final class ArgumentBinder {

    private ArgumentBinder() {}

    static Object[] bind(Request request, Route route, Container container) {
        var paramTypes = route.parameterTypes();
        if (paramTypes.isEmpty()) return new Object[0];

        List<String> pathParamNames = route.paramNames();
        var args = new Object[paramTypes.size()];
        int pathIndex = 0;

        for (int i = 0; i < paramTypes.size(); i++) {
            Class<?> type = paramTypes.get(i);

            if (Request.class.isAssignableFrom(type)) {
                args[i] = request;
                continue;
            }

            if (isSimple(type)) {
                args[i] = bindSimple(request, route, pathParamNames, pathIndex++, type);
                continue;
            }

            // 复杂类型 → 容器解析(v0.2 会在此接入 FormRequest 自动校验)
            args[i] = container.make(type);
        }
        return args;
    }

    private static Object bindSimple(Request request, Route route,
                                     List<String> pathParamNames, int pathIndex, Class<?> type) {
        if (pathIndex < pathParamNames.size()) {
            String name = pathParamNames.get(pathIndex);
            String raw = request.routeParam(name).orElse(null);
            if (raw == null) return defaultFor(type);
            try {
                return Cast.to(raw, box(type));
            } catch (Cast.CastException e) {
                // 路径参数类型不符是客户端错误(404 更合适:该资源标识不合法)
                throw new io.jaravel.http.HttpException(404,
                        "路径参数 {" + name + "} 的值 \"" + raw + "\" 不是合法的 "
                        + type.getSimpleName());
            }
        }

        // 路径参数用完了,回落到查询串。按参数位置猜名字不可靠,
        // 因此只在只有一个候选时生效,否则报错要求显式注解。
        var queryKeys = request.all().keySet();
        if (queryKeys.size() == 1) {
            String only = queryKeys.iterator().next();
            return Cast.to(request.input(only).orElse(null), box(type));
        }

        throw new IllegalStateException(
                "无法为 " + route.controller().getSimpleName() + "#" + route.action()
              + " 的第 " + (pathIndex + 1) + " 个简单类型参数(" + type.getSimpleName() + ")找到来源。"
              + "\n  路径 " + route.uri() + " 声明的参数:" + pathParamNames
              + "\n  请求的输入键:" + queryKeys
              + "\n  修复:在路径中声明对应参数,或(v0.2)用 @Query(\"name\") 显式指定来源。");
    }

    private static boolean isSimple(Class<?> t) {
        return t.isPrimitive() || t == String.class || Number.class.isAssignableFrom(t)
            || t == Boolean.class || t == Character.class || t.isEnum()
            || t == java.time.Instant.class || t == java.time.LocalDate.class
            || t == java.time.LocalDateTime.class || t == java.util.UUID.class;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == char.class) return '\0';
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        return 0;
    }
}
