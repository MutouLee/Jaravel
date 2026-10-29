package io.jaravel.events.apt;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * {@code @Listener} 的注解处理器。
 *
 * <p>为每个 {@code handle} 方法生成一个 {@code ListenerBinding} 实现,
 * 外加一份 {@code META-INF/jaravel/listeners.idx}。
 *
 * <h2>为什么用 APT 而不是运行时扫包</h2>
 * <p>Laravel 的事件发现要么扫目录、要么反射读参数类型,两者都在启动时
 * 付代价,而且**错误发现得晚**:一个 {@code handle(String s)} 要等到
 * 第一次分发才暴露。
 *
 * <p>APT 把这些都搬到编译期:
 * <ul>
 *   <li>handle 参数不是一个合法事件类型 → 编译错误;</li>
 *   <li>一个 {@code @Listener} 一个 handle 都没有 → 编译错误
 *       (那是个什么都不做的监听器,几乎总是忘了写方法名);</li>
 *   <li>运行时既不扫包也不反射 —— 生成的 invoke 是一次直接调用。</li>
 * </ul>
 *
 * <h2>生成的绑定为什么是「每个 handle 一个类」</h2>
 * <p>一个监听器可以监听多个事件(多个 handle 重载)。做成「一个类
 * 带一个 switch」要在运行时按类型分派,那又回到了反射方案的问题;
 * 一个 handle 一个绑定则让总线的 map 直接是 `事件类型 → 绑定列表`,
 * 分发时不需要任何类型判断。
 */
@SupportedAnnotationTypes("io.jaravel.events.Listener")
public class ListenerProcessor extends AbstractProcessor {

    private static final String BINDING = "io.jaravel.events.ListenerBinding";
    private static final String SHOULD_QUEUE = "io.jaravel.queue.ShouldQueue";

    /** 生成过的绑定类全名,写进索引 */
    private final Set<String> generated = new LinkedHashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement("io.jaravel.events.Listener"))) {

            if (element.getKind() != ElementKind.CLASS) {
                error(element, """
                        @Listener 只能标在类上。
                          监听器是一个有若干 handle 方法的普通类:
                            @Listener
                            public class NotifyWarehouse {
                                public void handle(OrderPaid e) { ... }
                            }""");
                continue;
            }
            processListener((TypeElement) element);
        }

        if (round.processingOver() && !generated.isEmpty()) {
            writeIndex();
        }
        return false;
    }

    private void processListener(TypeElement listener) {
        if (listener.getModifiers().contains(Modifier.ABSTRACT)) {
            error(listener, "@Listener 不能标在抽象类上 —— 它没法被实例化。");
            return;
        }
        if (listener.getNestingKind().isNested()
                && !listener.getModifiers().contains(Modifier.STATIC)) {
            error(listener, """
                    @Listener 不能标在非静态内部类上。
                      内部类实例需要一个外部类实例,容器没法凭类型建出它。
                      加 static,或者挪到顶层。""");
            return;
        }

        var handles = new ArrayList<ExecutableElement>();
        for (Element member : listener.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;
            if (!method.getSimpleName().contentEquals("handle")) continue;
            handles.add(method);
        }

        if (handles.isEmpty()) {
            error(listener, """
                    @Listener 标注的 %s 没有 handle 方法 —— 它不会收到任何事件。
                      监听器的执行体必须叫 handle,参数是事件类型:
                        public void handle(OrderPaid e) { ... }
                      一个类监听多个事件就写多个 handle 重载。
                      这里报错而不是放过:一个什么都不做的监听器
                      几乎总是「方法名写错了」,而那个错误静默起来
                      表现为「监听器不触发」,极难查。"""
                    .formatted(listener.getSimpleName()));
            return;
        }

        boolean queued = implementsShouldQueue(listener);
        int priority = priorityOf(listener);

        var seenEventTypes = new LinkedHashSet<String>();
        for (ExecutableElement handle : handles) {
            String eventType = validateHandle(listener, handle);
            if (eventType == null) continue;

            if (!seenEventTypes.add(eventType)) {
                error(handle, """
                        %s 有两个 handle 都接收 %s。
                          同一个监听器对同一个事件只能有一个 handle ——
                          两个的话无从选择,而按声明顺序挑会让
                          「调整方法顺序」悄悄改变行为。"""
                        .formatted(listener.getSimpleName(), eventType));
                continue;
            }
            generateBinding(listener, handle, eventType, priority, queued);
        }
    }

    /**
     * 校验一个 handle 并返回它的事件类型全名。
     *
     * @return null 表示这个 handle 不合法(已报错)
     */
    private String validateHandle(TypeElement listener, ExecutableElement handle) {
        if (handle.getParameters().size() != 1) {
            error(handle, """
                    handle 必须恰好有一个参数(事件)。
                      %s.handle 有 %d 个。
                      监听器的依赖从**构造器**注入,不是 handle 的参数 ——
                      这一点与 Job 的 handle 不同:Job 是反序列化出来的,
                      没有构造器注入的机会,而监听器是容器建的 bean。"""
                    .formatted(listener.getSimpleName(), handle.getParameters().size()));
            return null;
        }

        if (!handle.getModifiers().contains(Modifier.PUBLIC)) {
            error(handle, """
                    handle 必须是 public —— 生成的绑定是另一个包里的类,
                      它调不到非 public 的方法。""");
            return null;
        }
        if (handle.getModifiers().contains(Modifier.STATIC)) {
            error(handle, "handle 不能是 static —— 监听器是容器建的实例。");
            return null;
        }

        TypeMirror eventType = handle.getParameters().get(0).asType();
        if (eventType.getKind() != TypeKind.DECLARED) {
            error(handle, """
                    handle 的参数必须是一个类/record 类型,不能是 %s。
                      事件推荐用 record:不可变、简洁、自带 equals/toString。"""
                    .formatted(eventType.getKind()));
            return null;
        }

        TypeMirror returnType = handle.getReturnType();
        boolean validReturn = returnType.getKind() == TypeKind.VOID
                || returnType.getKind() == TypeKind.BOOLEAN
                || returnType.toString().equals("java.lang.Boolean");
        if (!validReturn) {
            error(handle, """
                    handle 的返回类型只能是 void 或 boolean,不能是 %s。
                      void    —— 永不中止后续监听器;
                      boolean —— 返回 false 中止传播(docs/10 §2.3)。
                      返回别的类型没有意义:总线不会用那个值,
                      而一个被静默丢弃的返回值会让人以为它有作用。"""
                    .formatted(returnType));
            return null;
        }

        return erasedName(eventType);
    }

    private void generateBinding(TypeElement listener, ExecutableElement handle,
                                 String eventTypeName, int priority, boolean queued) {

        String listenerName = listener.getQualifiedName().toString();

        // ★ 包名要从 Elements 取,**不能**从限定名里切最后一个点。
        //
        // 嵌套类的限定名是 `com.app.OuterTest.Inner`,按点切会把
        // `com.app.OuterTest` 当成包名 —— 于是生成的文件声明了一个
        // 与外层类同名的包,javac 报「程序包与带有相同名称的类冲突」。
        //
        // 这个 bug 只在监听器是嵌套类时出现,而测试里的监听器
        // 恰恰几乎总是嵌套类
        String packageName = processingEnv.getElementUtils()
                .getPackageOf(listener).getQualifiedName().toString();

        // 绑定类名:监听器简单名 + $$ + 事件简单名。
        // 用 $$ 是为了与用户自己可能写的类名不会撞 —— 它在 Java 标识符里
        // 合法但没人会手写
        String simpleEvent = simpleName(eventTypeName);
        String bindingSimple = nestedSafeName(listener) + "$$" + simpleEvent;
        String bindingName = packageName.isEmpty()
                ? bindingSimple
                : packageName + "." + bindingSimple;

        String body = """
                %s
                // 由 jaravel-events-processor 生成,请勿手工修改。
                //
                // 这个类的存在让事件分发在运行时**既不反射也不扫包**:
                // invoke 里是一次直接的强类型调用。
                public final class %s implements %s {

                    @Override
                    public Class<?> eventType() {
                        return %s.class;
                    }

                    @Override
                    public Class<?> listenerType() {
                        return %s.class;
                    }

                    @Override
                    public int priority() {
                        return %d;
                    }

                    @Override
                    public boolean queued() {
                        return %s;
                    }

                    @Override
                    public boolean invoke(Object listener, Object event) {
                        %s
                    }

                    @Override
                    public String describe() {
                        return "%s.handle(%s)";
                    }
                }
                """.formatted(
                packageName.isEmpty() ? "" : "package " + packageName + ";\n",
                bindingSimple,
                BINDING,
                eventTypeName,
                listenerName,
                priority,
                queued,
                invokeBody(listenerName, eventTypeName, handle),
                listener.getSimpleName(), simpleEvent);

        try {
            var file = processingEnv.getFiler().createSourceFile(bindingName, listener);
            try (Writer writer = file.openWriter()) {
                writer.write(body);
            }
            generated.add(bindingName);
        } catch (IOException e) {
            error(listener, "生成监听器绑定失败:" + e.getMessage());
        }
    }

    /**
     * invoke 的方法体。
     *
     * <p>按 handle 的返回类型分支 —— 这是**用户写 void 时不会出错**的关键:
     * 手写绑定的方案里「忘了 return true」会让后续监听器全被跳过,
     * 而那个 bug 的症状是「某些监听器有时不触发」。
     */
    private String invokeBody(String listenerName, String eventTypeName,
                              ExecutableElement handle) {
        String call = "((%s) listener).handle((%s) event)".formatted(
                listenerName, eventTypeName);

        if (handle.getReturnType().getKind() == TypeKind.VOID) {
            return call + ";\n            return true;";
        }
        // Boolean 装箱类型可能是 null —— 当成 true(不中止)。
        // 把 null 当 false 会让一个忘了 return 的分支静默中止整条链
        if (handle.getReturnType().toString().equals("java.lang.Boolean")) {
            return "Boolean result = " + call + ";\n"
                    + "            return result == null || result;";
        }
        return "return " + call + ";";
    }

    private void writeIndex() {
        try {
            var file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "META-INF/jaravel/listeners.idx");
            try (Writer writer = file.openWriter()) {
                writer.write("# jaravel 监听器绑定索引 —— 由 APT 生成\n");
                writer.write("# 启动时读它来装配事件总线,避免运行时扫包\n");
                for (String name : generated) {
                    writer.write(name);
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "写 listeners.idx 失败:" + e.getMessage()
                            + " —— 事件总线将退回扫描 Spring 容器里的 @Listener bean。");
        }
    }

    // ================= 工具 =================

    private boolean implementsShouldQueue(TypeElement listener) {
        var shouldQueue = processingEnv.getElementUtils().getTypeElement(SHOULD_QUEUE);
        if (shouldQueue == null) return false;      // jaravel-queue 不在 classpath 上

        return processingEnv.getTypeUtils().isAssignable(
                listener.asType(),
                processingEnv.getTypeUtils().erasure(shouldQueue.asType()));
    }

    private int priorityOf(TypeElement listener) {
        var annotation = listener.getAnnotationMirrors().stream()
                .filter(a -> a.getAnnotationType().toString()
                        .equals("io.jaravel.events.Listener"))
                .findFirst();

        if (annotation.isEmpty()) return 0;

        for (var entry : annotation.get().getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals("priority")) {
                return ((Number) entry.getValue().getValue()).intValue();
            }
        }
        return 0;
    }

    /**
     * 嵌套类的安全名字。
     *
     * <p>{@code Outer.Inner} 的简单名是 {@code Inner},而同一个包里可能
     * 有另一个 {@code Other.Inner} —— 两者会生成同名的绑定类。
     * 所以嵌套类要把外层名字带上。
     */
    private String nestedSafeName(TypeElement listener) {
        var parts = new ArrayList<String>();
        Element current = listener;
        while (current instanceof TypeElement type) {
            parts.add(0, type.getSimpleName().toString());
            current = type.getEnclosingElement();
        }
        return String.join("_", parts);
    }

    private String erasedName(TypeMirror type) {
        return processingEnv.getTypeUtils().erasure(type).toString();
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private void error(Element where, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, where);
    }
}
