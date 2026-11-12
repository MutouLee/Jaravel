package io.jaravel.view;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        List<ParamDecl> declared = parametersOf(params.group(1));

        String selfType = null;
        String selfName = null;
        Set<String> contentParams = new LinkedHashSet<>();

        for (ParamDecl parameter : declared) {
            if (isContent(parameter.type())) {
                contentParams.add(parameter.name());
                continue;
            }

            String resolved = resolveTypeName(parameter.type(), source.get());
            if (resolved == null) continue;

            Class<?> candidate = load(resolved, loader);
            if (candidate == null || !Component.class.isAssignableFrom(candidate)) continue;

            if (selfType != null) {
                throw new JbladeException(view + JbladeEngine.SUFFIX, line,
                        "类组件的 @params 里有两个组件类参数:"
                      + simpleName(selfType) + " 与 " + simpleName(parameter.type()) + "。\n"
                      + "  只保留一个 —— 标签调用需要知道 new 哪一个。");
            }
            selfType = parameter.type();
            selfName = parameter.name();
        }

        if (selfType == null) return Optional.empty();

        String sourceName = resolveTypeName(selfType, source.get());
        Class<?> type = load(sourceName, loader);
        if (type == null) return Optional.empty();

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
                sourceName, view, selfName, List.copyOf(parameters),
                Set.copyOf(contentParams), declaresShouldRender(type)));
    }

    /** 类自己声明了 {@code shouldRender()} 才需要生成「先构造、再判断」的代码 */
    private static boolean declaresShouldRender(Class<?> type) {
        try {
            var method = type.getDeclaredMethod("shouldRender");
            return method.getReturnType() == boolean.class && method.getParameterCount() == 0;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ================= @params =================

    private record ParamDecl(String type, String name) {}

    private static List<ParamDecl> parametersOf(String raw) {
        var parameters = new ArrayList<ParamDecl>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i <= raw.length(); i++) {
            char c = i < raw.length() ? raw.charAt(i) : ',';

            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                addParameter(parameters, raw.substring(start, i));
                start = i + 1;
            }
        }
        return parameters;
    }

    private static void addParameter(List<ParamDecl> parameters, String raw) {
        String parameter = stripDefault(raw).trim();
        if (parameter.isEmpty()) return;

        int space = lastWhitespace(parameter);
        if (space < 0) return;

        parameters.add(new ParamDecl(
                stripGenerics(parameter.substring(0, space).trim()),
                parameter.substring(space + 1).trim()));
    }

    /** jte 的 Content;简写 {@code Content} 也算(模板自带 import 的常见写法) */
    private static boolean isContent(String type) {
        return type.equals("Content") || type.equals("gg.jte.Content");
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
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
