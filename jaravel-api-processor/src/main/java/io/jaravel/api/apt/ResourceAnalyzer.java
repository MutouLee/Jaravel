package io.jaravel.api.apt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import io.jaravel.api.openapi.ResourceProperty;

/**
 * 从 {@code JsonResource.toArray} 里静态提取响应字段(docs/12 §5)。
 *
 * <p>只支持一种形状:
 *
 * <pre>{@code
 * return map(request)
 *     .put("id", model.id)
 *     .putWhen(model.published, "published_at", () -> model.createdAt)
 *     .putWhenLoadedOne("author", model.author(), AuthorResource::new)
 *     .build();
 * }</pre>
 *
 * <h2>★ 为什么只支持一种形状,而不是尽力而为</h2>
 * <p>一份**缺字段的 schema** 比没有 schema 更糟:前端照它生成模型类,
 * 然后在某个字段上拿到 undefined。所以这里不在复杂控制流上猜 ——
 * 有多个 return、把 builder 存进变量、{@code merge} 一组动态键时,
 * 直接报「这个资源提取不出来」,由命令在输出里明确说明。
 *
 * <h2>类型从哪里来</h2>
 * <p>字面量、强制转换、{@code model.x} 的字段类型(通过
 * {@code JsonResource<M>} 的类型参数找到模型类,再查字段)。
 * 查不到就**不写 type** —— 只保留字段名,不猜 string。
 */
final class ResourceAnalyzer {

    private static final String JSON_RESOURCE = "io.jaravel.api.JsonResource";

    sealed interface Result permits Success, Failure {
    }

    record Success(List<ResourceProperty> properties) implements Result {
    }

    record Failure(String reason) implements Result {
    }

    /** 一个字段指向另一个资源类;{@code array} 表示它是这些资源的数组 */
    private record Ref(String className, boolean array) {
    }

    private final Elements elements;
    private final Trees trees;

    ResourceAnalyzer(ProcessingEnvironment env) {
        this.elements = env.getElementUtils();
        this.trees = Trees.instance(env);
    }

    Result analyze(TypeElement resource) {
        ExecutableElement toArray = findToArray(resource);
        if (toArray == null) {
            return new Failure("找不到 toArray 方法");
        }

        MethodTree method = (MethodTree) trees.getTree(toArray);
        if (method == null) {
            return new Failure("toArray 没有源码(来自依赖 jar);该模块应自带索引");
        }
        if (method.getBody() == null) {
            return new Failure("toArray 没有方法体");
        }

        TreePath methodPath = trees.getPath(toArray);
        CompilationUnitTree unit = methodPath == null ? null : methodPath.getCompilationUnit();
        if (unit == null) {
            return new Failure("拿不到 toArray 的编译单元");
        }

        List<? extends ExpressionTree> returns = returnsOf(method);
        if (returns.isEmpty()) {
            return new Failure("toArray 没有 return");
        }
        if (returns.size() > 1) {
            return new Failure("toArray 有 " + returns.size()
                    + " 个 return —— 静态提取只支持单一的 map(...).put(...).build() 链");
        }
        ExpressionTree expression = returns.get(0);
        if (expression == null) {
            return new Failure("toArray 的 return 没有表达式");
        }

        List<MethodInvocationTree> calls = chainOf(strip(expression));
        if (calls.isEmpty()) {
            return new Failure("return 的表达式不是 map(request).put(...).build() 链");
        }
        if (!nameOf(calls.get(0)).equals("map")) {
            return new Failure("链不是从 map(request) 开始");
        }
        if (!nameOf(calls.get(calls.size() - 1)).equals("build")) {
            return new Failure("链的最后不是 build()");
        }

        TypeElement model = modelTypeOf(resource);
        var properties = new LinkedHashMap<String, ResourceProperty>();

        for (int i = 1; i < calls.size() - 1; i++) {
            MethodInvocationTree call = calls.get(i);
            String methodName = nameOf(call);

            int keyIndex = switch (methodName) {
                case "put", "putIfAbsent", "putWhenLoaded", "putWhenLoadedOne" -> 0;
                case "putWhen" -> 1;
                case "merge", "mergeWhen" -> -1;
                default -> -2;
            };
            if (keyIndex == -1) {
                return new Failure("merge 的键是动态的,静态提取看不到");
            }
            if (keyIndex == -2) {
                return new Failure("不认识的构建器方法 " + methodName + "()");
            }

            List<? extends ExpressionTree> args = call.getArguments();
            if (args.size() <= keyIndex) {
                return new Failure(methodName + "() 缺少字段名参数");
            }

            String key = keyOf(args.get(keyIndex), unit);
            if (key == null) {
                return new Failure("字段名不是字符串字面量/常量 —"
                        + " " + args.get(keyIndex));
            }

            boolean optional = !methodName.equals("put");
            String type = valueTypeOf(methodName, args, model, unit);

            // 值是另一个资源(单个构造器 / collection)→ 记下类名,
            // 生成器会把它解析成 components.schemas 里的 $ref
            Ref ref = valueRefOf(methodName, args, unit);
            if (ref != null) {
                type = ref.array() ? "array" : "object";
            }

            merge(properties, key, type, optional, ref == null ? null : ref.className());
        }

        return new Success(List.copyOf(properties.values()));
    }

    // ================= 找方法 =================

    /**
     * 自己声明的优先,然后逐层往父类找 —— 资源可以继承一个通用的
     * {@code toArray},只要那个父类也在源码里。
     */
    private ExecutableElement findToArray(TypeElement resource) {
        TypeElement current = resource;
        while (current != null
                && !current.getQualifiedName().contentEquals("java.lang.Object")) {

            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.METHOD
                        && member.getSimpleName().contentEquals("toArray")
                        && ((ExecutableElement) member).getParameters().size() == 1) {
                    return (ExecutableElement) member;
                }
            }

            TypeMirror superType = current.getSuperclass();
            if (superType.getKind() != TypeKind.DECLARED) return null;
            current = (TypeElement) ((DeclaredType) superType).asElement();
        }
        return null;
    }

    private List<? extends ExpressionTree> returnsOf(MethodTree method) {
        var returns = new ArrayList<ExpressionTree>();

        new TreeScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                returns.add(node.getExpression());
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                // 条件字段里的 lambda 里的 return 不是 toArray 的返回
                return null;
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null;
            }
        }.scan(method.getBody(), null);

        return returns;
    }

    /**
     * 把 {@code a().b().c()} 拆成 {@code [a, b, c]}。
     *
     * <p>从外往里剥;遇到不是方法调用的接收者(变量、字面量、类型名)
     * 就停 —— 那时链不完整,由调用方判断。
     */
    private List<MethodInvocationTree> chainOf(ExpressionTree expression) {
        var calls = new ArrayList<MethodInvocationTree>();
        ExpressionTree cursor = expression;

        while (cursor instanceof MethodInvocationTree call) {
            calls.add(0, call);
            ExpressionTree receiver = receiverOf(call);
            if (receiver == null) break;
            cursor = receiver;
        }
        return calls;
    }

    private ExpressionTree receiverOf(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        return select instanceof MemberSelectTree member ? member.getExpression() : null;
    }

    private String nameOf(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return "";
    }

    private ExpressionTree strip(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parens) {
            current = parens.getExpression();
        }
        return current;
    }

    // ================= 键 =================

    /**
     * 字段名:字符串字面量,或者编译期常量。
     *
     * <p>常量要支持,因为 {@code static final String ID = "id"} 是常见写法;
     * 如果它被当成「不是字面量」而整份资源失败,用户会觉得莫名其妙。
     */
    private String keyOf(ExpressionTree expression, CompilationUnitTree unit) {
        ExpressionTree value = strip(expression);

        if (value instanceof LiteralTree literal
                && literal.getKind() == Tree.Kind.STRING_LITERAL) {
            return String.valueOf(literal.getValue());
        }

        TreePath path = TreePath.getPath(unit, value);
        if (path != null) {
            Element element = trees.getElement(path);
            if (element instanceof VariableElement variable
                    && variable.getConstantValue() instanceof String constant) {
                return constant;
            }
        }
        return null;
    }

    // ================= 类型 =================

    private String valueTypeOf(String methodName, List<? extends ExpressionTree> args,
                               TypeElement model, CompilationUnitTree unit) {
        int valueIndex = switch (methodName) {
            case "put", "putIfAbsent" -> 1;
            case "putWhen", "putWhenLoaded", "putWhenLoadedOne" -> 2;
            default -> -1;
        };
        if (valueIndex < 0 || args.size() <= valueIndex) return null;
        return typeOf(args.get(valueIndex), model, unit);
    }

    private Ref valueRefOf(String methodName, List<? extends ExpressionTree> args,
                           CompilationUnitTree unit) {
        int valueIndex = switch (methodName) {
            case "put", "putIfAbsent" -> 1;
            case "putWhen", "putWhenLoaded", "putWhenLoadedOne" -> 2;
            default -> -1;
        };
        if (valueIndex < 0 || args.size() <= valueIndex) return null;
        return refOf(args.get(valueIndex), unit);
    }

    /**
     * 值表达式是不是另一个 {@code JsonResource}。
     *
     * <p>认三种写法:
     * <ul>
     *   <li>{@code new AuthorResource(model.author)} —— 单个;</li>
     *   <li>{@code AuthorResource::new} —— 作为 map 函数传给
     *       {@code putWhenLoadedOne} 之类;</li>
     *   <li>{@code AuthorResource::collection} /
     *       {@code AuthorResource.collection(...)} —— 数组。</li>
     * </ul>
     *
     * <p>lambda 会往里看一眼:{@code () -> new AuthorResource(...)}。
     * 只是 {@code model.author()} 这种返回模型本身的值**不算** ——
     * 它的 JSON 形状由字段上的类型转换决定,不是资源。
     */
    private Ref refOf(ExpressionTree expression, CompilationUnitTree unit) {
        ExpressionTree value = strip(expression);

        if (value instanceof LambdaExpressionTree lambda
                && lambda.getBody() instanceof ExpressionTree body) {
            return refOf(body, unit);
        }

        if (value instanceof MemberReferenceTree reference) {
            // ★ 构造器引用 `X::new` 的 getName() 不是 "new"(它是
            // `<init>`)—— 必须看 ReferenceMode,否则永远识别不到。
            if (reference.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
                TypeElement resource = resourceTypeOf(
                        reference.getQualifierExpression(), unit);
                return resource == null ? null : new Ref(binaryNameOf(resource), false);
            }

            if (reference.getMode() == MemberReferenceTree.ReferenceMode.INVOKE
                    && reference.getName().contentEquals("collection")) {
                TypeElement resource = resourceTypeOf(
                        reference.getQualifierExpression(), unit);
                return resource == null ? null : new Ref(binaryNameOf(resource), true);
            }
            return null;
        }

        if (value instanceof NewClassTree newClass) {
            Tree identifier = newClass.getIdentifier();
            if (identifier instanceof ParameterizedTypeTree parameterized) {
                identifier = parameterized.getType();
            }
            TypeElement resource = resourceTypeOf(identifier, unit);
            return resource == null ? null : new Ref(binaryNameOf(resource), false);
        }

        if (value instanceof MethodInvocationTree call && nameOf(call).equals("collection")) {
            TypeElement resource = resourceTypeOf(receiverOf(call), unit);
            return resource == null ? null : new Ref(binaryNameOf(resource), true);
        }

        return null;
    }

    private TypeElement resourceTypeOf(Tree expression, CompilationUnitTree unit) {
        if (expression == null) return null;
        TreePath path = TreePath.getPath(unit, expression);
        if (path == null) return null;

        Element element = trees.getElement(path);
        if (element instanceof TypeElement type && isJsonResource(type)) return type;

        TypeMirror mirror = trees.getTypeMirror(path);
        if (mirror != null && mirror.getKind() == TypeKind.DECLARED) {
            Element type = ((DeclaredType) mirror).asElement();
            if (type instanceof TypeElement declared && isJsonResource(declared)) {
                return declared;
            }
        }
        return null;
    }

    private boolean isJsonResource(TypeElement type) {
        TypeMirror superType = type.getSuperclass();
        while (superType != null && superType.getKind() == TypeKind.DECLARED) {
            TypeElement element = (TypeElement) ((DeclaredType) superType).asElement();
            if (element.getQualifiedName().contentEquals(JSON_RESOURCE)) return true;
            superType = element.getSuperclass();
        }
        return false;
    }

    private String binaryNameOf(TypeElement type) {
        return elements.getBinaryName(type).toString();
    }

    private String typeOf(ExpressionTree expression, TypeElement model,
                          CompilationUnitTree unit) {
        ExpressionTree value = strip(expression);

        if (value instanceof TypeCastTree cast) {
            return typeOfTypeTree(cast.getType(), unit);
        }

        if (value instanceof LiteralTree literal) {
            return switch (literal.getKind()) {
                case STRING_LITERAL, CHAR_LITERAL -> "string";
                case INT_LITERAL, LONG_LITERAL -> "integer";
                case FLOAT_LITERAL, DOUBLE_LITERAL -> "number";
                case BOOLEAN_LITERAL -> "boolean";
                default -> null;
            };
        }

        if (value instanceof NewArrayTree) return "array";

        if (value instanceof LambdaExpressionTree lambda) {
            return lambda.getBody() instanceof ExpressionTree body
                    ? typeOf(body, model, unit)
                    : null;
        }

        if (value instanceof ConditionalExpressionTree conditional) {
            String whenTrue = typeOf(conditional.getTrueExpression(), model, unit);
            String whenFalse = typeOf(conditional.getFalseExpression(), model, unit);
            return Objects.equals(whenTrue, whenFalse) ? whenTrue : null;
        }

        if (value instanceof MemberReferenceTree reference) {
            TreePath path = TreePath.getPath(unit, reference);
            if (path != null && trees.getElement(path) instanceof ExecutableElement method) {
                return typeOfMirror(method.getReturnType());
            }
            return null;
        }

        if (value instanceof MemberSelectTree select && isModel(select.getExpression())) {
            VariableElement field = findField(model, select.getIdentifier().toString());
            return field == null ? null : typeOfMirror(field.asType());
        }

        return elementTypeOf(value, unit);
    }

    private boolean isModel(ExpressionTree expression) {
        ExpressionTree value = strip(expression);
        if (value instanceof IdentifierTree identifier) {
            return identifier.getName().contentEquals("model");
        }
        if (value instanceof MemberSelectTree select) {
            return select.getIdentifier().contentEquals("model")
                    && select.getExpression() instanceof IdentifierTree owner
                    && owner.getName().contentEquals("this");
        }
        return false;
    }

    private VariableElement findField(TypeElement model, String name) {
        if (model == null) return null;
        try {
            for (Element member : elements.getAllMembers(model)) {
                if (member.getKind() == ElementKind.FIELD
                        && member.getSimpleName().contentEquals(name)) {
                    return (VariableElement) member;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 模型类本身有编译错误时 getAllMembers 会抛 —— 类型就当不知道
        }
        return null;
    }

    /** 局部变量 / 字段 / 方法调用 —— 交给编译器已经解析出来的元素 */
    private String elementTypeOf(ExpressionTree expression, CompilationUnitTree unit) {
        TreePath path = TreePath.getPath(unit, expression);
        if (path == null) return null;

        Element element = trees.getElement(path);
        if (element instanceof VariableElement variable) {
            return typeOfMirror(variable.asType());
        }
        if (element instanceof ExecutableElement method) {
            return typeOfMirror(method.getReturnType());
        }
        return null;
    }

    private String typeOfTypeTree(Tree typeTree, CompilationUnitTree unit) {
        TreePath path = TreePath.getPath(unit, typeTree);
        if (path != null) {
            String type = typeOfMirror(trees.getTypeMirror(path));
            if (type != null) return type;
        }

        // 类型树没有被解析出来时,退回到只认几个常见的名字
        return switch (typeTree.toString()) {
            case "String", "java.lang.String", "CharSequence", "java.lang.CharSequence" -> "string";
            case "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte" -> "integer";
            case "float", "Float", "double", "Double", "BigDecimal", "java.math.BigDecimal" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> null;
        };
    }

    private String typeOfMirror(TypeMirror mirror) {
        if (mirror == null) return null;

        if (mirror.getKind() == TypeKind.ARRAY) return "array";

        if (mirror.getKind().isPrimitive()) {
            return switch (mirror.getKind()) {
                case INT, LONG, SHORT, BYTE -> "integer";
                case FLOAT, DOUBLE -> "number";
                case BOOLEAN -> "boolean";
                case CHAR -> "string";
                default -> null;
            };
        }

        if (mirror.getKind() != TypeKind.DECLARED) return null;

        String name = ((TypeElement) ((DeclaredType) mirror).asElement())
                .getQualifiedName().toString();

        return switch (name) {
            case "java.lang.String", "java.lang.CharSequence", "java.util.UUID",
                 "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
                 "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.Instant",
                 "java.util.Date" -> "string";
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
                 "java.math.BigInteger" -> "integer";
            case "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "number";
            case "java.lang.Boolean" -> "boolean";
            case "java.util.List", "java.util.Set", "java.util.Collection",
                 "java.lang.Iterable", "java.util.stream.Stream" -> "array";
            case "java.util.Map" -> "object";
            default -> null;
        };
    }

    /**
     * 资源类 {@code JsonResource<M>} 里的那个 {@code M}。
     *
     * <p>逐层往父类走;父类是 {@code JsonResource<M>}(M 还是类型变量)时
     * 返回 null —— 不沿着类型变量替换做推导,因为那需要一个小的类型替换器,
     * 而它出错时是静默给出错误的字段类型。
     */
    private TypeElement modelTypeOf(TypeElement resource) {
        TypeMirror superType = resource.getSuperclass();

        while (superType != null && superType.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) superType;
            TypeElement element = (TypeElement) declared.asElement();

            if (element.getQualifiedName().contentEquals(JSON_RESOURCE)) {
                if (declared.getTypeArguments().isEmpty()) return null;
                TypeMirror model = declared.getTypeArguments().get(0);
                return model.getKind() == TypeKind.DECLARED
                        ? (TypeElement) ((DeclaredType) model).asElement()
                        : null;
            }
            superType = element.getSuperclass();
        }
        return null;
    }

    private void merge(LinkedHashMap<String, ResourceProperty> properties,
                       String key, String type, boolean optional, String ref) {
        ResourceProperty existing = properties.get(key);
        if (existing == null) {
            properties.put(key, new ResourceProperty(key, type, optional, ref));
            return;
        }

        boolean typeConflict = existing.type() != null && type != null
                && !existing.type().equals(type);
        String mergedType;
        if (typeConflict) {
            // 两个不同的已知类型 —— 不猜,退回到「不知道」
            mergedType = null;
        } else {
            mergedType = type != null ? type : existing.type();
        }

        String mergedRef;
        if (typeConflict
                || (existing.ref() != null && ref != null && !existing.ref().equals(ref))) {
            mergedRef = null;
        } else {
            mergedRef = ref != null ? ref : existing.ref();
        }

        properties.put(key, new ResourceProperty(
                key, mergedType, existing.optional() && optional, mergedRef));
    }
}
