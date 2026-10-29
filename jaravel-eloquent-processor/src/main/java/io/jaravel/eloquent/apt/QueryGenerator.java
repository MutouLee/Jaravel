package io.jaravel.eloquent.apt;

import java.util.List;

import io.jaravel.eloquent.meta.RelationDef;

/**
 * 生成 {@code PostQuery} —— 带模型专属方法的查询。
 *
 * <p>**为什么要重写一堆父类方法**:{@code Query<M>} 的链式方法返回
 * {@code Query<M>},于是 {@code Post.query().where(...).published()} 会编译失败 ——
 * {@code where} 的返回类型上没有 {@code published()}。
 *
 * <p>三个解法里选了这个:
 * <ul>
 *   <li>自引用泛型 {@code Query<M, Q extends Query<M,Q>>} —— 会污染用户手写的
 *       作用域签名({@code Query<Post>} 变成 {@code Query<Post, PostQuery>});</li>
 *   <li>要求用户先调作用域再加条件 —— 把实现细节变成了使用规则;</li>
 *   <li><b>在生成的子类里协变重写</b> —— 代码量由生成器承担,用户完全看不见。</li>
 * </ul>
 */
final class QueryGenerator {

    private QueryGenerator() {}

    /** 需要协变重写的父类方法。只覆盖会出现在链式中间的那些。 */
    private static final List<String[]> COVARIANT = List.of(
            // {签名(含泛型声明), 调用实参}
            new String[] {"<V> %Q% where(Col<%M%, V> col, V value)", "where(col, value)"},
            new String[] {"<V> %Q% where(Col<%M%, V> col, io.jaravel.database.query.QueryState.Op op, V value)",
                          "where(col, op, value)"},
            new String[] {"<V> %Q% orWhere(Col<%M%, V> col, V value)", "orWhere(col, value)"},
            new String[] {"<V> %Q% whereIn(Col<%M%, V> col, java.util.Collection<V> values)",
                          "whereIn(col, values)"},
            new String[] {"<V> %Q% whereNotIn(Col<%M%, V> col, java.util.Collection<V> values)",
                          "whereNotIn(col, values)"},
            new String[] {"%Q% whereNull(Col<%M%, ?> col)", "whereNull(col)"},
            new String[] {"%Q% whereNotNull(Col<%M%, ?> col)", "whereNotNull(col)"},
            new String[] {"<V> %Q% whereBetween(Col<%M%, V> col, V low, V high)",
                          "whereBetween(col, low, high)"},
            new String[] {"%Q% whereLike(Col<%M%, String> col, String pattern)",
                          "whereLike(col, pattern)"},
            new String[] {"%Q% where(java.util.function.Consumer<Query<%M%>> group)", "where(group)"},
            new String[] {"%Q% whereKey(Object value)", "whereKey(value)"},
            new String[] {"%Q% whereNamed(String column, Object value)", "whereNamed(column, value)"},
            new String[] {"%Q% whereRaw(String sql, Object... bindings)", "whereRaw(sql, bindings)"},
            new String[] {"%Q% when(boolean condition, java.util.function.Consumer<Query<%M%>> then)",
                          "when(condition, then)"},
            new String[] {"%Q% tap(java.util.function.Consumer<Query<%M%>> action)", "tap(action)"},
            new String[] {"%Q% orderBy(Col<%M%, ?> col)", "orderBy(col)"},
            new String[] {"%Q% orderByDesc(Col<%M%, ?> col)", "orderByDesc(col)"},
            new String[] {"%Q% latest()", "latest()"},
            new String[] {"%Q% orderByKey()", "orderByKey()"},
            new String[] {"%Q% orderByKeyDesc()", "orderByKeyDesc()"},
            new String[] {"%Q% limit(int n)", "limit(n)"},
            new String[] {"%Q% offset(int n)", "offset(n)"},
            new String[] {"%Q% forPage(int page, int perPage)", "forPage(page, perPage)"},
            new String[] {"%Q% withoutGlobalScope(String name)", "withoutGlobalScope(name)"},
            new String[] {"%Q% withoutGlobalScopes()", "withoutGlobalScopes()"},
            new String[] {"%Q% withTrashed()", "withTrashed()"},
            new String[] {"%Q% onlyTrashed()", "onlyTrashed()"}
    );

    static String generate(ModelInfo m) {
        String name = m.simpleName();
        String q = m.queryClass();
        var c = new Code();

        c.line("// 由 Jaravel 生成,请勿修改。源:%s", m.qualifiedName());
        if (!m.packageName().isEmpty()) c.line("package %s;", m.packageName()).blank();

        c.line("import io.jaravel.eloquent.meta.Col;");
        c.line("import io.jaravel.eloquent.meta.RelPath;");
        c.line("import io.jaravel.eloquent.query.Query;");
        c.blank();

        c.doc("%s 的类型化查询。".formatted(name),
              "",
              "<p>父类的链式方法在这里被协变重写成返回 {@code " + q + "},",
              "因此作用域可以出现在链条的任何位置。");
        c.open("public class %s extends Query<%s> {".formatted(q, name));
        c.blank();

        c.open("public %s() {".formatted(q));
        c.line("super(%s.META);", m.metamodelClass());
        c.close();
        c.blank();

        emitCovariantOverrides(c, m, name, q);
        emitWith(c, m, name, q);
        emitColumnHelpers(c, m, q);
        emitScopes(c, m, q);
        emitGlobalScopes(c, m, name);

        c.close();
        return c.toString();
    }

    private static void emitCovariantOverrides(Code c, ModelInfo m, String name, String q) {
        c.line("// ================= 协变重写(保持链式类型) =================");
        c.blank();
        for (String[] entry : COVARIANT) {
            String sig = entry[0].replace("%Q%", q).replace("%M%", name);
            String call = entry[1];
            boolean varargs = sig.contains("...");
            c.line("@Override");
            if (varargs) c.line("@SafeVarargs");
            // 带 varargs 的重写必须是 final(父类里就是 final),这里跟随
            c.open("public %s%s {".formatted(varargs ? "final " : "", sig));
            c.line("super.%s;", call);
            c.line("return this;");
            c.close();
            c.blank();
        }
    }

    /** {@code whereIn(col, V...)} 和 {@code with(RelPath...)} 在父类是 final,不能重写 —— 换名提供 */
    private static void emitWith(Code c, ModelInfo m, String name, String q) {
        c.line("// ================= 预加载 =================");
        c.blank();
        c.doc("预加载。父类的 {@code with} 是 final varargs(类型安全需要),",
              "这里重新声明一个同名方法无法协变,因此提供返回 {@code " + q + "} 的版本。");
        c.line("@SafeVarargs");
        c.open("public final %s load(RelPath<%s, ?>... paths) {".formatted(q, name));
        c.line("super.with(paths);");
        c.line("return this;");
        c.close();
        c.blank();

        // 每个关联一个单参快捷方法:with 的 99% 用法是单个关联,这样连 Post_ 都不用写
        for (var r : m.relations()) {
            if (r.kind() == RelationDef.Kind.MORPH_TO) continue;
            String method = "with" + io.jaravel.support.Str.studly(r.name());
            c.doc("预加载 %s —— 等价于 {@code load(%s.%s)}。".formatted(
                    r.name(), m.metamodelClass(), r.name()));
            c.open("public %s %s() {".formatted(q, method));
            c.line("super.with(%s.%s);", m.metamodelClass(), r.name());
            c.line("return this;");
            c.close();
            c.blank();

            c.doc("预加载 %s 并对子查询加约束。".formatted(r.name()));
            c.open("public %s %s(java.util.function.Consumer<Query<%s>> constraint) {"
                    .formatted(q, method, r.targetType()));
            c.line("super.with(%s.%s.where(constraint));", m.metamodelClass(), r.name());
            c.line("return this;");
            c.close();
            c.blank();
        }
    }

    /**
     * 每列一个 {@code whereXxx(value)}。
     *
     * <p>这是对 parity 影响最大的一组方法:{@code Post.query().whereSlug(s)} 对上
     * Laravel 的 {@code Post::where('slug', $s)},而不带它就得写
     * {@code Post.query().where(Post_.slug, s)} —— 长一截,且要求用户知道 Post_ 的存在。
     */
    private static void emitColumnHelpers(Code c, ModelInfo m, String q) {
        if (!m.generateHelpers()) return;

        c.line("// ================= 按列筛选的快捷方法 =================");
        c.blank();
        for (var col : m.columns()) {
            String type = boxed(col.typeName());
            String method = Naming.whereHelper(col.fieldName());

            c.open("public %s %s(%s value) {".formatted(q, method, type));
            c.line("super.where(%s.%s, value);", m.metamodelClass(), col.fieldName());
            c.line("return this;");
            c.close();
            c.blank();

            c.open("public %s %sIn(java.util.Collection<%s> values) {".formatted(q, method, type));
            c.line("super.whereIn(%s.%s, values);", m.metamodelClass(), col.fieldName());
            c.line("return this;");
            c.close();
            c.blank();

            if (type.equals("String")) {
                c.open("public %s %sLike(String pattern) {".formatted(q, method));
                c.line("super.whereLike(%s.%s, pattern);", m.metamodelClass(), col.fieldName());
                c.line("return this;");
                c.close();
                c.blank();
            }
            if (isComparable(type)) {
                c.open("public %s %sGt(%s value) {".formatted(q, method, type));
                c.line("super.where(%s.%s, io.jaravel.database.query.QueryState.Op.GT, value);",
                        m.metamodelClass(), col.fieldName());
                c.line("return this;");
                c.close();
                c.blank();
                c.open("public %s %sLt(%s value) {".formatted(q, method, type));
                c.line("super.where(%s.%s, io.jaravel.database.query.QueryState.Op.LT, value);",
                        m.metamodelClass(), col.fieldName());
                c.line("return this;");
                c.close();
                c.blank();
                c.open("public %s %sBetween(%s low, %s high) {".formatted(q, method, type, type));
                c.line("super.whereBetween(%s.%s, low, high);", m.metamodelClass(), col.fieldName());
                c.line("return this;");
                c.close();
                c.blank();
            }
            c.open("public %s orderBy%s() {".formatted(q, io.jaravel.support.Str.studly(col.fieldName())));
            c.line("super.orderBy(%s.%s);", m.metamodelClass(), col.fieldName());
            c.line("return this;");
            c.close();
            c.blank();
            c.open("public %s orderBy%sDesc() {".formatted(
                    q, io.jaravel.support.Str.studly(col.fieldName())));
            c.line("super.orderByDesc(%s.%s);", m.metamodelClass(), col.fieldName());
            c.line("return this;");
            c.close();
            c.blank();
        }
    }

    private static void emitScopes(Code c, ModelInfo m, String q) {
        if (m.scopes().isEmpty()) return;

        c.line("// ================= 作用域 =================");
        c.blank();
        for (var s : m.scopes()) {
            String params = BaseGenerator.signature(s);
            String args = s.paramNames().isEmpty() ? "" : ", " + String.join(", ", s.paramNames());
            c.doc("调用 {@code %s.%s} —— 由 @Scope 生成。".formatted(m.simpleName(), s.methodName()));
            c.open("public %s %s(%s) {".formatted(q, s.methodName(), params));
            c.line("%s.%s(this%s);", m.simpleName(), s.methodName(), args);
            c.line("return this;");
            c.close();
            c.blank();
        }
    }

    /**
     * 全局作用域的套用点。
     *
     * <p>父类在执行查询前调一次 {@code applyGlobalScopes()};每个作用域都先问
     * {@code isGlobalScopeEnabled},这样 {@code withoutGlobalScope("tenant")} 才有效。
     */
    private static void emitGlobalScopes(Code c, ModelInfo m, String name) {
        if (m.globalScopes().isEmpty()) return;

        c.line("// ================= 全局作用域 =================");
        c.blank();
        c.line("@Override");
        c.open("protected void applyGlobalScopes() {");
        for (var s : m.globalScopes()) {
            c.open("if (isGlobalScopeEnabled(%s)) {".formatted(Code.str(s.scopeName())));
            c.line("%s.%s(this);", name, s.methodName());
            c.close();
        }
        c.close();
        c.blank();
    }

    private static boolean isComparable(String type) {
        return List.of("Integer", "Long", "Short", "Byte", "Float", "Double",
                       "java.math.BigDecimal", "java.time.Instant", "java.time.LocalDate",
                       "java.time.LocalDateTime", "java.time.LocalTime", "String")
                .contains(type);
    }

    private static String boxed(String typeName) {
        return switch (typeName) {
            case "boolean" -> "Boolean";
            case "byte"    -> "Byte";
            case "short"   -> "Short";
            case "int"     -> "Integer";
            case "long"    -> "Long";
            case "char"    -> "Character";
            case "float"   -> "Float";
            case "double"  -> "Double";
            default        -> typeName;
        };
    }
}
