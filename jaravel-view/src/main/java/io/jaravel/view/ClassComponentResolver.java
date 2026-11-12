package io.jaravel.view;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从类组件的模板里认出它的 Java 类(docs/04 §1.7)。
 *
 * <p>约定:模板第一行 {@code @params(PostCard self)}。这个类型就是组件类,
 * 而它的**公开构造函数**就是组件的属性表 —— 属性名与构造参数名配对,
 * 类型交给 javac 检查。
 *
 * <h2>为什么不用 APT 索引</h2>
 * <p>类名就写在模板里,模板源码在转写时已经在手上。再生成一份索引意味着
 * 类名有两个出处(模板与索引),而它们会漂 —— 症状是「模板改了类名,
 * 索引还是旧的」,报错却指向一个不存在的类。
 *
 * <p>代价是:构造函数参数名需要 {@code -parameters}(框架的 parent pom
 * 与示例工程都加了)。缺了它时 {@link Parameter#isNamePresent()} 为 false,
 * 这里会直接报错并说清怎么加 —— 而不是把 {@code arg0} 当成属性名,
 * 生成一份没人看得懂的报错。
 */
final class ClassComponentResolver {

    private static final Pattern PARAMS = Pattern.compile("@params\\s*\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*@import\\s*\\(([^)]*)\\)");

    private ClassComponentResolver() {
    }

    /**
     * @param view     组件视图名(如 {@code components.post-card})
     * @param source   模板源码;empty 表示模板不存在
     * @param loader   应用类加载器
     * @return 不是类组件时返回 empty(按匿名组件处理)
     */
    static Optional<JbladeTranspiler.ClassComponent> resolve(
            String view, Optional<String> source, ClassLoader loader) {

        if (source.isEmpty()) return Optional.empty();

        var params = PARAMS.matcher(source.get());
        if (!params.find()) return Optional.empty();

        int line = (int) source.get().substring(0, params.start())
                .lines().count() + 1;

        String first = firstParameter(params.group(1));
        if (first == null) return Optional.empty();

        int space = lastWhitespace(first);
        if (space < 0) {
            throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                    "类组件的 @params 缺少参数名。\n"
                  + "  写成 @params(PostCard self) —— 第二个词是模板里用的变量名。");
        }

        String typeName = stripGenerics(first.substring(0, space).trim());
        String selfName = first.substring(space + 1).trim();

        String sourceName = resolveTypeName(typeName, source.get());
        if (sourceName == null) return Optional.empty();

        Class<?> type = load(sourceName, loader);
        if (type == null || !Component.class.isAssignableFrom(type)) {
            return Optional.empty();
        }

        if (!Modifier.isPublic(type.getModifiers())) {
            throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                    "类组件 " + type.getName() + " 必须是 public ——\n"
                  + "  生成的代码在别的包里 new 它。");
        }

        Constructor<?> constructor = constructorOf(type, view, line);

        var parameters = new ArrayList<JbladeTranspiler.ConstructorParam>();
        for (Parameter parameter : constructor.getParameters()) {
            if (!parameter.isNamePresent()) {
                throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                        "拿不到 " + type.getSimpleName() + " 构造函数的参数名 ——\n"
                      + "  类组件的属性按参数名配对,而这需要编译时加 -parameters。\n"
                      + "  Spring Boot 的 parent pom 默认已经加了;自定义构建要补上。");
            }
            parameters.add(new JbladeTranspiler.ConstructorParam(
                    parameter.getName(), parameter.getType().getSimpleName()));
        }

        return Optional.of(new JbladeTranspiler.ClassComponent(
                sourceName, view, selfName, List.copyOf(parameters)));
    }

    // ================= @params =================

    private static String firstParameter(String raw) {
        int depth = 0;
        int start = 0;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                return stripDefault(raw.substring(start, i));
            }
        }
        return stripDefault(raw.substring(start));
    }

    private static String stripDefault(String parameter) {
        int depth = 0;
        for (int i = 0; i < parameter.length(); i++) {
            char c = parameter.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == '=' && depth == 0) {
                return parameter.substring(0, i).trim();
            }
        }
        return parameter.trim();
    }

    private static int lastWhitespace(String parameter) {
        for (int i = parameter.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(parameter.charAt(i))) return i;
        }
        return -1;
    }

    private static String stripGenerics(String type) {
        int generic = type.indexOf('<');
        return generic < 0 ? type : type.substring(0, generic).trim();
    }

    /** 简单名靠 {@code @import} 解析;限定名原样使用 */
    private static String resolveTypeName(String typeName, String source) {
        if (typeName.contains(".")) return typeName;

        Map<String, String> imports = new LinkedHashMap<>();
        var importsMatcher = IMPORT.matcher(source);
        while (importsMatcher.find()) {
            String imported = importsMatcher.group(1).trim();
            if (imported.startsWith("static ")) continue;
            int dot = imported.lastIndexOf('.');
            if (dot < 0) continue;
            imports.put(imported.substring(dot + 1), imported);
        }
        return imports.get(typeName);
    }

    /**
     * 加载类。嵌套类在源码里写成 {@code Outer.Inner},而 JVM 名是
     * {@code Outer$Inner} —— 从右往左把点换成 {@code $} 试一遍。
     */
    private static Class<?> load(String name, ClassLoader loader) {
        String candidate = name;
        while (true) {
            try {
                return Class.forName(candidate, false, loader);
            } catch (ClassNotFoundException ignored) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) return null;
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }

    private static Constructor<?> constructorOf(Class<?> type, String view, int line) {
        Constructor<?>[] constructors = type.getConstructors();

        if (constructors.length == 0) {
            throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                    "类组件 " + type.getSimpleName() + " 没有 public 构造函数。\n"
                  + "  组件标签会 new 它,构造函数必须能被别的包访问。");
        }
        if (constructors.length > 1) {
            throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                    "类组件 " + type.getSimpleName() + " 有 " + constructors.length
                  + " 个 public 构造函数,无法决定用哪一个。\n"
                  + "  只保留一个 public 构造函数,其余改成包内可见或 static 工厂。");
        }
        return constructors[0];
    }
}
