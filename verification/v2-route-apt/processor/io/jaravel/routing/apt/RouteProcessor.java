package io.jaravel.routing.apt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * V2 验证:路由表达式的编译期解析与校验。
 *
 * 用的是 com.sun.source.util.Trees —— jdk.compiler 模块**正式导出**的受支持 API
 * (javax.lang.model 的 AST 补充),不是 com.sun.tools.javac.* 那种内部实现。
 * 因此不需要 --add-exports,不随 JDK 小版本漂移。
 *
 * 校验项(全部是 javac 自身看不到的):
 *   1. 路径参数 {id} 与控制器方法的参数名是否对得上
 *   2. 路径参数个数与方法参数个数是否一致
 *   3. 同一 (method, uri) 是否重复定义
 *   4. 路由名是否重复
 *   5. resource() 展开出的 7 个方法是否都存在于控制器上
 */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class RouteProcessor extends AbstractProcessor {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "any");

    private static final List<String> RESOURCE_ACTIONS =
            List.of("index", "create", "store", "show", "edit", "update", "destroy");

    private static final List<String> API_RESOURCE_ACTIONS =
            List.of("index", "store", "show", "update", "destroy");

    private static final Pattern PARAM = Pattern.compile("\\{(\\w+)\\??\\*?}");

    private Trees trees;
    private Elements elements;
    private Types types;

    /** 收集到的路由,用于跨文件的重复检测与生成产物 */
    private final List<RouteInfo> collected = new ArrayList<>();

    record RouteInfo(String httpMethod, String uri, String controller,
                     String action, String name, String source) {}

    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }

    @Override public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.trees = Trees.instance(env);         // ← 关键:取得 AST 访问能力
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        note("RouteProcessor 已启动,Trees = " + (trees != null ? "可用" : "不可用"));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            reportSummary();
            return false;
        }

        TypeElement routeDef = elements.getTypeElement("io.jaravel.routing.RouteDefinition");
        if (routeDef == null) return false;

        for (Element root : round.getRootElements()) {
            if (root.getKind() != ElementKind.CLASS) continue;
            TypeElement type = (TypeElement) root;
            if (!implementsInterface(type, routeDef.asType())) continue;

            note("发现路由定义类:" + type.getQualifiedName());
            scanDefineMethod(type);
        }
        return false;
    }

    private boolean implementsInterface(TypeElement type, TypeMirror iface) {
        for (TypeMirror i : type.getInterfaces()) {
            if (types.isAssignable(types.erasure(i), types.erasure(iface))) return true;
        }
        return false;
    }

    /** 核心:取出 define(Router) 的方法体 AST 并遍历 */
    private void scanDefineMethod(TypeElement type) {
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) member;
            if (!m.getSimpleName().contentEquals("define")) continue;

            TreePath path = trees.getPath(m);
            if (path == null) {
                error(m, "无法取得 define() 的 AST(可能是编译自 class 文件)");
                continue;
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            new RouteScanner(unit, type, m).scan(path, null);
        }
    }

    // ---------------- AST 遍历 ----------------

    private class RouteScanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final TypeElement owner;
        private final Element anchor;      // 诊断锚点:define() 方法

        RouteScanner(CompilationUnitTree unit, TypeElement owner, Element anchor) {
            this.unit = unit;
            this.owner = owner;
            this.anchor = anchor;
        }

        /**
         * V2 发现:Messager 没有接受 Tree 的 printMessage 重载,
         * 标准 APT 无法把诊断定位到方法体内的具体行。
         * 退路:锚点用 define() 这个 Element(IDE 至少能跳到方法),
         * 精确行号写进消息文本(由 Trees.getSourcePositions 取得)。
         */
        private void err(Tree node, String msg) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[jaravel-routing] " + where(node) + "\n  " + msg, anchor);
        }

        private void wrn(Tree node, String msg) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[jaravel-routing] " + where(node) + "\n  " + msg, anchor);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            String called = calledMethodName(node);

            if (called != null && HTTP_METHODS.contains(called) && node.getArguments().size() == 2) {
                handleHttpRoute(node, called);
            } else if ("resource".equals(called) || "apiResource".equals(called)) {
                handleResourceRoute(node, called);
            }
            return super.visitMethodInvocation(node, p);
        }

        private String calledMethodName(MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree ms) {
                return ms.getIdentifier().toString();
            }
            return null;
        }

        /** 处理 r.get("/users/{id}", UserController::show) */
        private void handleHttpRoute(MethodInvocationTree node, String httpMethod) {
            ExpressionTree uriArg = node.getArguments().get(0);
            ExpressionTree handlerArg = node.getArguments().get(1);

            if (!(uriArg instanceof LiteralTree lit) || !(lit.getValue() instanceof String uri)) {
                // 非字面量 URI(动态构造)—— 跳过校验,这是预期的降级
                note("跳过非字面量 URI 的路由(" + where(node) + ")");
                return;
            }

            if (!(handlerArg instanceof MemberReferenceTree ref)) {
                note("跳过非方法引用的处理器(" + where(node) + ")");
                return;
            }

            // 解析方法引用:Controller::action
            String controllerName = ref.getQualifierExpression().toString();
            String action = ref.getName().toString();

            TypeElement controller = resolveController(controllerName);
            String fqcn = controller != null
                    ? controller.getQualifiedName().toString() : controllerName;

            // ---- 校验 1/2:路径参数 vs 方法参数 ----
            List<String> pathParams = extractParams(uri);
            if (controller != null) {
                ExecutableElement target = findMethod(controller, action);
                if (target == null) {
                    // javac 通常已报错;这里兜底(例如重载解析成功但名字写错的场景)
                    err(node, "控制器 " + controller.getSimpleName()
                            + " 上找不到方法 " + action + "()");
                } else {
                    checkParams(node, uri, pathParams, target, controller, action);
                }
            }

            // ---- 校验 3/4:重复 ----
            String name = extractChainedName(getCurrentPath());
            RouteInfo info = new RouteInfo(httpMethod, uri, fqcn, action, name, where(node));
            checkDuplicates(node, info);
            collected.add(info);
        }

        /** 处理 r.resource("/posts", PostController.class) */
        private void handleResourceRoute(MethodInvocationTree node, String kind) {
            if (node.getArguments().size() != 2) return;
            ExpressionTree uriArg = node.getArguments().get(0);
            if (!(uriArg instanceof LiteralTree lit) || !(lit.getValue() instanceof String uri)) return;

            String classExpr = node.getArguments().get(1).toString();   // "PostController.class"
            if (!classExpr.endsWith(".class")) return;
            String simple = classExpr.substring(0, classExpr.length() - ".class".length());

            TypeElement controller = resolveController(simple);
            if (controller == null) return;

            List<String> required = "resource".equals(kind) ? RESOURCE_ACTIONS : API_RESOURCE_ACTIONS;
            List<String> excepted = extractChainedStrings(getCurrentPath(), "except");
            List<String> onlyList = extractChainedStrings(getCurrentPath(), "only");

            List<String> missing = new ArrayList<>();
            for (String act : required) {
                if (!onlyList.isEmpty() && !onlyList.contains(act)) continue;
                if (excepted.contains(act)) continue;
                if (findMethod(controller, act) == null) missing.add(act);
            }

            if (!missing.isEmpty()) {
                err(node, kind + "(\"" + uri + "\", " + controller.getSimpleName()
                        + ".class) 需要以下方法,但控制器上不存在:" + missing
                        + "\n  修复:实现这些方法,或用 .except(\""
                        + String.join("\", \"", missing) + "\") 排除");
            } else {
                note(kind + " 校验通过:" + controller.getSimpleName()
                        + " 具备全部所需方法 " + required);
            }
        }

        // ---- 参数校验 ----

        /**
         * 按**名字**匹配,而非按个数。
         *
         * 这是 V2 验证中修正的一个设计错误:方法参数不全来自路径,
         * 还可能来自请求体(@Body)、查询串(@Query)、容器注入(服务)。
         * 因此规则是:**每个路径参数都必须能在方法参数中找到同名项**;
         * 方法上多出来的参数是合法的。
         */
        private void checkParams(Tree node, String uri, List<String> pathParams,
                                 ExecutableElement target, TypeElement controller, String action) {
            List<String> methodParams = target.getParameters().stream()
                    .map(v -> v.getSimpleName().toString()).toList();

            List<String> unmatched = new ArrayList<>();
            for (String p : pathParams) {
                if (!methodParams.contains(p)) unmatched.add(p);
            }

            if (!unmatched.isEmpty()) {
                String suggestion = methodParams.isEmpty()
                        ? "该方法没有任何参数"
                        : "方法参数为 " + methodParams;
                err(node, "路径 \"" + uri + "\" 的参数 " + unmatched
                        + " 在 " + controller.getSimpleName() + "#" + action
                        + " 上找不到同名参数(" + suggestion + ")"
                        + "\n  修复:给方法加上对应参数,或改写路径;"
                        + "若参数名有意不同,用 @Path(\"" + unmatched.get(0) + "\") 显式绑定");
            }

            // 反向检查:方法有参数但路径无参数,且参数名看起来像路径参数(启发式告警)
            if (pathParams.isEmpty() && !methodParams.isEmpty()) {
                for (String mp : methodParams) {
                    if (mp.equals("id") || mp.endsWith("Id")) {
                        wrn(node, controller.getSimpleName() + "#" + action
                                + " 有参数 `" + mp + "`,但路径 \"" + uri
                                + "\" 未声明任何参数。它将从查询串或容器解析 —— 确认这是预期行为");
                    }
                }
            }
        }

        private void checkDuplicates(Tree node, RouteInfo info) {
            for (RouteInfo prev : collected) {
                if (prev.httpMethod.equals(info.httpMethod) && prev.uri.equals(info.uri)) {
                    err(node, "路由重复定义:" + info.httpMethod.toUpperCase()
                            + " " + info.uri + "\n  已定义于 " + prev.source);
                }
                if (info.name != null && info.name.equals(prev.name)) {
                    err(node, "路由名重复:\"" + info.name
                            + "\"\n  已用于 " + prev.source);
                }
            }
        }

        // ---- 链式调用解析:从 AST 父链上找 .name("x") / .except("a","b") ----

        private String extractChainedName(TreePath path) {
            List<String> v = extractChainedStrings(path, "name");
            return v.isEmpty() ? null : v.get(0);
        }

        private List<String> extractChainedStrings(TreePath path, String method) {
            List<String> out = new ArrayList<>();
            TreePath cur = path.getParentPath();
            while (cur != null) {
                Tree t = cur.getLeaf();
                if (t instanceof MethodInvocationTree mi
                        && mi.getMethodSelect() instanceof MemberSelectTree ms
                        && ms.getIdentifier().contentEquals(method)) {
                    for (ExpressionTree arg : mi.getArguments()) {
                        if (arg instanceof LiteralTree l && l.getValue() instanceof String s) out.add(s);
                    }
                }
                // 只向上走表达式链,遇到语句就停
                if (t.getKind() == Tree.Kind.EXPRESSION_STATEMENT || t.getKind() == Tree.Kind.BLOCK) break;
                cur = cur.getParentPath();
            }
            return out;
        }

        private String where(Tree node) {
            long line = unit.getLineMap().getLineNumber(
                    trees.getSourcePositions().getStartPosition(unit, node));
            return owner.getSimpleName() + ".java:" + line;
        }
    }

    // ---------------- 工具 ----------------

    /** 把 "UserController" 这样的简单名解析成 TypeElement(先试同包,再试 import 过的全名) */
    private TypeElement resolveController(String name) {
        if (name.contains(".")) {
            TypeElement t = elements.getTypeElement(name);
            if (t != null) return t;
        }
        for (String pkg : List.of("app.http.controllers", "", "sample")) {
            String fq = pkg.isEmpty() ? name : pkg + "." + name;
            TypeElement t = elements.getTypeElement(fq);
            if (t != null) return t;
        }
        return null;
    }

    private ExecutableElement findMethod(TypeElement type, String name) {
        for (Element e : elements.getAllMembers(type)) {
            if (e.getKind() == ElementKind.METHOD
                    && e.getSimpleName().contentEquals(name)
                    && e.getModifiers().contains(Modifier.PUBLIC)) {
                return (ExecutableElement) e;
            }
        }
        return null;
    }

    private static List<String> extractParams(String uri) {
        List<String> out = new ArrayList<>();
        Matcher m = PARAM.matcher(uri);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private void reportSummary() {
        note("———— 路由表(共 " + collected.size() + " 条)————");
        for (RouteInfo r : collected) {
            note(String.format("  %-6s %-24s → %s#%s%s",
                    r.httpMethod.toUpperCase(), r.uri,
                    simple(r.controller), r.action,
                    r.name != null ? "  [" + r.name + "]" : ""));
        }
    }

    private static String simple(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    private void note(String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[jaravel-routing] " + msg);
    }
    private void warn(Tree node, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "[jaravel-routing] " + msg);
    }
    private void error(Tree node, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[jaravel-routing] " + msg);
    }
    private void error(Element e, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[jaravel-routing] " + msg, e);
    }
}
