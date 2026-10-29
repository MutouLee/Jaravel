package io.jaravel.routing;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 从方法引用中提取控制器类、方法名、参数类型。
 *
 * <p>靠 {@code Serializable} lambda 的 {@code writeReplace()} 取 {@link SerializedLambda}。
 * 只在路由注册时调用,结果缓存。
 */
final class LambdaIntrospector {

    private LambdaIntrospector() {}

    record Target(Class<?> controller, String method, List<Class<?>> paramTypes) {}

    private static final Map<Class<?>, Target> CACHE = new ConcurrentHashMap<>();

    static Target inspect(Serializable methodRef) {
        return CACHE.computeIfAbsent(methodRef.getClass(), k -> doInspect(methodRef));
    }

    private static Target doInspect(Serializable methodRef) {
        SerializedLambda sl;
        try {
            Method writeReplace = methodRef.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            sl = (SerializedLambda) writeReplace.invoke(methodRef);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "无法解析方法引用。路由的第二个参数必须是**方法引用**(如 UserController::show),"
                    + "不能是 lambda 表达式或匿名类 —— 后者没有可提取的目标方法。\n"
                    + "  若需要内联逻辑,请用 r.view(...) 或先定义一个控制器方法。", e);
        }

        validateIsUnboundMethodReference(sl);

        String implClass = sl.getImplClass().replace('/', '.');
        Class<?> controller;
        try {
            controller = Class.forName(implClass, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                controller = Class.forName(implClass);
            } catch (ClassNotFoundException e2) {
                throw new IllegalStateException("无法加载控制器类 " + implClass, e2);
            }
        }

        List<Class<?>> params = parseDescriptorParams(sl.getImplMethodSignature(), controller.getClassLoader());
        return new Target(controller, sl.getImplMethodName(), params);
    }

    // java.lang.invoke.MethodHandleInfo 的常量,此处直接写字面量以免引入依赖
    private static final int REF_invokeVirtual   = 5;
    private static final int REF_invokeSpecial   = 7;
    private static final int REF_invokeInterface = 9;

    /**
     * 路由的处理器必须是**未绑定的实例方法引用**({@code UserController::show})。
     *
     * <p>实测的区分信号(见 verification 的探针):
     * <ul>
     *   <li>未绑定实例方法引用 → {@code kind=5(invokeVirtual)},方法名是真实名字;
     *   <li>lambda            → {@code kind=6(invokeStatic)},方法名形如 {@code lambda$main$1};
     *   <li>匿名类            → 没有 {@code writeReplace},在上层已被捕获。
     * </ul>
     *
     * <p>必须拦住 lambda:否则会注册出一条指向**合成方法**的路由,
     * 控制器实例无法从容器解析,到请求时才以更费解的方式失败。
     */
    private static void validateIsUnboundMethodReference(SerializedLambda sl) {
        String method = sl.getImplMethodName();
        int kind = sl.getImplMethodKind();

        if (method.startsWith("lambda$")) {
            throw new IllegalArgumentException(
                    "路由的处理器不能是 lambda 表达式,必须是**方法引用**。\n"
                  + "  收到的是编译器合成的方法 " + sl.getImplClass().replace('/', '.')
                  + "#" + method + "\n"
                  + "  原因:lambda 没有对应的控制器类,容器无法解析接收者实例。\n"
                  + "  修复:\n"
                  + "    · 写成 r.get(\"/x\", SomeController::action);\n"
                  + "    · 只想直出视图:r.view(\"/x\", \"pages.x\");\n"
                  + "    · 只想重定向:  r.redirect(\"/old\", \"/new\");");
        }

        if (kind != REF_invokeVirtual && kind != REF_invokeInterface && kind != REF_invokeSpecial) {
            throw new IllegalArgumentException(
                    "路由的处理器必须是**实例**方法引用(Controller::action),"
                  + "不能是静态方法引用。\n"
                  + "  收到:" + sl.getImplClass().replace('/', '.') + "#" + method
                  + "(methodKind=" + kind + ")\n"
                  + "  原因:控制器实例由容器注入,静态方法没有接收者,也无法做依赖注入。");
        }
    }

    /** 解析 JVM 方法描述符的参数部分,如 {@code (Ljava/lang/Long;I)Ljava/lang/Object;} */
    static List<Class<?>> parseDescriptorParams(String descriptor, ClassLoader loader) {
        List<Class<?>> out = new ArrayList<>();
        int i = descriptor.indexOf('(') + 1;
        int end = descriptor.indexOf(')');
        while (i < end) {
            int arrayDepth = 0;
            while (descriptor.charAt(i) == '[') { arrayDepth++; i++; }
            char c = descriptor.charAt(i);
            Class<?> type;
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                String name = descriptor.substring(i + 1, semi).replace('/', '.');
                type = loadClass(name, loader);
                i = semi + 1;
            } else {
                type = primitiveOf(c);
                i++;
            }
            for (int d = 0; d < arrayDepth; d++) type = java.lang.reflect.Array.newInstance(type, 0).getClass();
            out.add(type);
        }
        return out;
    }

    private static Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader != null ? loader
                    : Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try { return Class.forName(name); }
            catch (ClassNotFoundException e2) { throw new IllegalStateException("无法加载参数类型 " + name, e2); }
        }
    }

    private static Class<?> primitiveOf(char c) {
        return switch (c) {
            case 'I' -> int.class;     case 'J' -> long.class;
            case 'Z' -> boolean.class; case 'D' -> double.class;
            case 'F' -> float.class;   case 'S' -> short.class;
            case 'B' -> byte.class;    case 'C' -> char.class;
            case 'V' -> void.class;
            default -> throw new IllegalArgumentException("未知的描述符字符:" + c);
        };
    }
}
