package io.jaravel.eloquent.apt;

import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.support.Str;

/**
 * 生成 {@code PostBase} —— 用户的模型继承它。
 *
 * <p>这个文件是整个 parity 的核心:Laravel 的 {@code Post::find(1)}、
 * {@code $post->comments} 在 PHP 里靠魔术方法,在 Java 里只能靠生成。
 * 每少生成一个入口,用户就多写一行样板。
 */
final class BaseGenerator {

    private BaseGenerator() {}

    static String generate(ModelInfo m) {
        String name = m.simpleName();
        var c = new Code();

        c.line("// 由 Jaravel 生成,请勿修改。源:%s", m.qualifiedName());
        if (!m.packageName().isEmpty()) c.line("package %s;", m.packageName()).blank();

        c.line("import java.util.List;");
        c.line("import java.util.Map;");
        c.line("import java.util.Optional;");
        c.blank();
        c.line("import io.jaravel.eloquent.Model;");
        c.line("import io.jaravel.eloquent.meta.ModelMeta;");
        c.line("import io.jaravel.eloquent.relations.Rel;");
        c.line("import io.jaravel.eloquent.relations.Rel1;");
        c.line("import io.jaravel.support.Coll;");
        c.blank();

        c.doc("%s 的生成基类。".formatted(name),
              "",
              "<p>这里的静态方法替代 Laravel 的 {@code Model::__callStatic}:",
              "<pre>{@code",
              " * " + name + ".find(1)              // Laravel: " + name + "::find(1)",
              " * " + name + ".query().where(...)   // Laravel: " + name + "::where(...)",
              " * }</pre>",
              "",
              "<p>不要手改本文件 —— 每次编译都会重新生成。要加方法请写在 {@code " + name + "} 里。");
        c.open("public abstract class %s extends Model<%s> {".formatted(m.baseClass(), name));
        c.blank();

        c.line("@Override");
        c.open("public final ModelMeta<%s> meta() {".formatted(name));
        c.line("return %s.META;", m.metamodelClass());
        c.close();
        c.blank();

        emitStaticEntries(c, m, name);
        if (m.generateHelpers()) emitRelationAccessors(c, m);
        if (m.accessors()) emitFieldAccessors(c, m);
        emitScopeStatics(c, m, name);

        c.close();
        return c.toString();
    }

    // ================= 静态查询入口 =================

    private static void emitStaticEntries(Code c, ModelInfo m, String name) {
        String q = m.queryClass();

        c.line("// ================= 静态入口 =================");
        c.blank();

        c.doc("新建一个查询。链式方法返回 {@code " + q + "},",
              "因此可以在任何位置接作用域:{@code query().where(...).published()}。");
        c.open("public static %s query() {".formatted(q));
        c.line("return new %s();", q);
        c.close();
        c.blank();

        c.open("public static Optional<%s> find(Object key) {".formatted(name));
        c.line("return query().find(key);");
        c.close();
        c.blank();

        c.doc("找不到就抛 {@code ModelNotFoundException} —— 框架会把它翻译成 404。");
        c.open("public static %s findOrFail(Object key) {".formatted(name));
        c.line("return query().findOrFail(key);");
        c.close();
        c.blank();

        c.open("public static Coll<%s> all() {".formatted(name));
        c.line("return query().get();");
        c.close();
        c.blank();

        c.open("public static Coll<%s> findMany(java.util.Collection<?> keys) {".formatted(name));
        c.line("return query().whereIn(%s.%s, keys.stream().map(k -> (%s) k).toList()).get();",
                m.metamodelClass(), m.key().fieldName(), boxed(m.key().typeName()));
        c.close();
        c.blank();

        c.doc("按属性创建并保存。只有 fillable/未被 guarded 的列会被写入。");
        c.open("public static %s create(Map<String, ?> attributes) {".formatted(name));
        c.line("%s model = %s.META.instantiate();", name, m.metamodelClass());
        c.line("model.fill(attributes);");
        c.line("model.save();");
        c.line("return model;");
        c.close();
        c.blank();

        c.doc("存在则取、不存在则建。{@code attributes} 用于查找,{@code values} 仅在创建时填充。");
        c.open("public static %s firstOrCreate(Map<String, ?> attributes, Map<String, ?> values) {"
                .formatted(name));
        c.line("%s query = query();", m.queryClass());
        c.open("for (Map.Entry<String, ?> e : attributes.entrySet()) {");
        c.line("query.whereNamed(e.getKey(), e.getValue());");
        c.close();
        c.open("if (query.first().isPresent()) {");
        c.line("return query.first().get();");
        c.close();
        c.line("Map<String, Object> merged = new java.util.LinkedHashMap<>(attributes);");
        c.line("merged.putAll(values);");
        c.line("return create(merged);");
        c.close();
        c.blank();

        c.open("public static %s firstOrCreate(Map<String, ?> attributes) {".formatted(name));
        c.line("return firstOrCreate(attributes, Map.of());");
        c.close();
        c.blank();

        c.open("public static long count() {");
        c.line("return query().count();");
        c.close();
        c.blank();

        c.doc("批量插入。一条 SQL,不触发模型事件 —— 与 Laravel 的 {@code insert()} 一致。");
        c.open("public static int insert(List<Map<String, ?>> rows) {");
        c.line("return query().rawBuilder().insert(rows.stream()");
        c.line("        .map(r -> (Map<String, Object>) new java.util.LinkedHashMap<String, Object>(r))");
        c.line("        .toList());");
        c.close();
        c.blank();

        if (m.softDeletes()) {
            c.doc("含已软删的记录");
            c.open("public static %s withTrashed() {".formatted(m.queryClass()));
            c.line("return query().withTrashed();");
            c.close();
            c.blank();
            c.open("public static %s onlyTrashed() {".formatted(m.queryClass()));
            c.line("return query().onlyTrashed();");
            c.close();
            c.blank();
        }
    }

    // ================= 关联访问器 =================

    private static void emitRelationAccessors(Code c, ModelInfo m) {
        if (m.relations().isEmpty()) return;

        c.line("// ================= 关联 =================");
        c.doc("ADR-010 方案 E':关联用**方法**而不是字段。",
              "",
              "<p>V1 验证证明字段行不通 —— 父类构造里初始化的关联字段会被子类的",
              "显式字段初始化器静默覆盖成 null,而「有没有写 = null」这种差异",
              "不可能让用户自己注意到。");
        c.blank();

        for (var r : m.relations()) {
            String target = r.kind() == RelationDef.Kind.MORPH_TO ? null : r.targetType();

            if (r.kind() == RelationDef.Kind.MORPH_TO) {
                c.doc("多态归属 %s —— 目标类型由 {@code %s} 列决定。".formatted(
                        r.name(), r.morphPrefix() + "_type"),
                      "可能的类型:" + r.morphTypes());
                c.open("public final Model<?> %s() {".formatted(r.name()));
                c.line("return io.jaravel.eloquent.relations.MorphResolver.resolve(this, %s);",
                        Code.str(r.name()));
                c.close();
                c.blank();
                continue;
            }

            if (r.isMany()) {
                c.doc("%s(%s) —— 多值。{@code %s().get()} 返回 {@code Coll<%s>}。".formatted(
                        kindLabel(r.kind()), r.targetSimpleName(), r.name(), r.targetSimpleName()));
                c.open("public final Rel<%s> %s() {".formatted(target, r.name()));
                c.line("return rel(%s);", Code.str(r.name()));
                c.close();
            } else {
                c.doc("%s(%s) —— 单值。{@code %s().get()} 返回 {@code Optional<%s>}。".formatted(
                        kindLabel(r.kind()), r.targetSimpleName(), r.name(), r.targetSimpleName()));
                c.open("public final Rel1<%s> %s() {".formatted(target, r.name()));
                c.line("return rel1(%s);", Code.str(r.name()));
                c.close();
            }
            c.blank();
        }
    }

    private static String kindLabel(RelationDef.Kind kind) {
        return switch (kind) {
            case HAS_MANY          -> "一对多";
            case HAS_ONE           -> "一对一";
            case BELONGS_TO        -> "归属";
            case BELONGS_TO_MANY   -> "多对多";
            case HAS_MANY_THROUGH  -> "远程一对多";
            case MORPH_MANY        -> "多态一对多";
            case MORPH_TO          -> "多态归属";
        };
    }

    // ================= 可选的字段访问器 =================

    /**
     * {@code @Model(accessors = true)} 时额外生成 getter/setter。
     *
     * <p>默认关闭:public 字段已经够用,getter/setter 只是噪音。
     * 开着是为了与需要 JavaBean 约定的第三方库(Jackson 的某些配置、
     * 模板引擎的反射取值)配合。
     */
    private static void emitFieldAccessors(Code c, ModelInfo m) {
        c.line("// ================= JavaBean 访问器(accessors = true) =================");
        c.blank();
        for (var col : m.columns()) {
            String studly = Str.studly(col.fieldName());
            boolean isBool = col.typeName().equals("boolean") || col.typeName().equals("Boolean");
            c.open("public final %s %s%s() {".formatted(
                    col.typeName(), isBool ? "is" : "get", studly));
            c.line("return this.%s;", col.fieldName());
            c.close();
            c.open("public final void set%s(%s value) {".formatted(studly, col.typeName()));
            c.line("this.%s = value;", col.fieldName());
            c.close();
            c.blank();
        }
    }

    // ================= 作用域的静态快捷方式 =================

    /**
     * {@code @Scope} 方法在生成的 Query 上有实例方法,这里再补一个静态入口,
     * 让 {@code Post.published()} 等价于 {@code Post.query().published()}。
     */
    private static void emitScopeStatics(Code c, ModelInfo m, String name) {
        if (m.scopes().isEmpty()) return;

        c.line("// ================= 作用域静态入口 =================");
        c.blank();
        for (var s : m.scopes()) {
            String params = signature(s);
            String args = String.join(", ", s.paramNames());
            c.open("public static %s %s(%s) {".formatted(m.queryClass(), s.methodName(), params));
            c.line("return query().%s(%s);", s.methodName(), args);
            c.close();
            c.blank();
        }
    }

    static String signature(ModelInfo.ScopeInfo s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(s.paramTypes().get(i)).append(' ').append(s.paramNames().get(i));
        }
        return sb.toString();
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
