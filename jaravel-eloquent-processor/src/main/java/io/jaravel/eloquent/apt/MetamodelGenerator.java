package io.jaravel.eloquent.apt;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.eloquent.meta.RelationDef;

/**
 * 生成 {@code Post_} —— 列常量、关联路径常量、以及 {@code ModelMeta}。
 *
 * <p>命名用小写({@code Post_.title} 而非 {@code Post_.TITLE}),这是本项目的
 * 已决事项:它读起来像在引用字段本身,而大写常量风格会让每个查询都在喊。
 */
final class MetamodelGenerator {

    private MetamodelGenerator() {}

    static String generate(ModelInfo m) {
        var c = new Code();
        c.line("// 由 Jaravel 生成,请勿修改。源:%s", m.qualifiedName());
        if (!m.packageName().isEmpty()) c.line("package %s;", m.packageName()).blank();

        c.line("import io.jaravel.eloquent.meta.Col;");
        c.line("import io.jaravel.eloquent.meta.ModelMeta;");
        c.line("import io.jaravel.eloquent.meta.ModelRegistry;");
        c.line("import io.jaravel.eloquent.meta.RelPath;");
        c.line("import io.jaravel.eloquent.meta.RelationDef;");
        c.blank();

        c.doc("%s 的元模型。".formatted(m.simpleName()),
              "",
              "<p>列常量带两个类型参数 {@code Col<" + m.simpleName() + ", V>},",
              "因此把别的模型的列传进本模型的查询会**编译失败** —— 这是",
              "Laravel 的字符串列名做不到的保护。");
        c.open("public final class %s {".formatted(m.metamodelClass()));
        c.blank();
        c.line("private %s() {}", m.metamodelClass());
        c.blank();

        c.line("public static final String table = %s;", Code.str(m.table()));
        c.blank();

        // ---- 列 ----
        c.line("// ================= 列 =================");
        for (var col : m.columns()) {
            emitColumn(c, m, col);
        }
        c.blank();

        // ---- 关联路径 ----
        if (!m.relations().isEmpty()) {
            c.line("// ================= 关联路径(用于 with) =================");
            for (var r : m.relations()) {
                if (r.kind() == RelationDef.Kind.MORPH_TO) {
                    c.line("// %s 是多态归属,目标类型运行时才确定,没有静态 RelPath", r.name());
                    continue;
                }
                c.line("public static final RelPath<%s, %s> %s = RelPath.of(%s, %s.class);",
                        m.simpleName(), r.targetType(), r.name(),
                        Code.str(r.name()), r.targetType());
            }
            c.blank();
        }

        // ---- ModelMeta ----
        c.line("// ================= 元信息 =================");
        c.blank();
        emitMeta(c, m);

        c.blank();
        c.doc("类加载即注册。",
              "",
              "<p>{@code ModelRegistry} 在按约定找不到模型时会 {@code Class.forName(\"X_\")},",
              "触发这个静态块 —— 所以用户不需要在任何地方手动注册模型。");
        c.open("static {");
        c.line("ModelRegistry.register(META);");
        c.close();

        c.close();
        return c.toString();
    }

    private static void emitColumn(Code c, ModelInfo m, ModelInfo.ColumnInfo col) {
        String declaredType = boxed(col.typeName());
        String getter = "o -> o.%s".formatted(col.fieldName());
        String setter = "(o, v) -> o.%s = v".formatted(col.fieldName());

        // 原始类型的 setter 不能接 null —— 生成一个带默认值的写入
        if (isPrimitive(col.typeName())) {
            setter = "(o, v) -> o.%s = (v == null ? %s : v)"
                    .formatted(col.fieldName(), primitiveDefault(col.typeName()));
        }

        // 显式类型见证不可省:链上接 .asHidden() 后,赋值的目标类型不再参与推断,
        // javac 会把 lambda 的参数推成 Object,于是 `o -> o.password` 找不到字段。
        String witness = "<%s, %s>".formatted(m.simpleName(), declaredType);

        String expr;
        if (col.castType() != null) {
            expr = "Col.%scast(%s, %s.class, %d, %s, %s, new %s(%s))".formatted(
                    witness, Code.str(col.columnName()), col.boxedType(), col.index(),
                    getter, setter, col.castType(), Code.strList(col.castArgs()));
        } else {
            expr = "Col.%sof(%s, %s.class, %d, %s, %s)".formatted(
                    witness, Code.str(col.columnName()), col.boxedType(), col.index(),
                    getter, setter);
        }
        if (col.hidden()) expr += ".asHidden()";

        c.line("public static final Col<%s, %s> %s = %s;",
                m.simpleName(), declaredType, col.fieldName(), expr);
    }

    private static void emitMeta(Code c, ModelInfo m) {
        c.line("public static final ModelMeta<%s> META =", m.simpleName());
        c.line("        ModelMeta.builder(%s.class, table)", m.simpleName());

        var lines = new ArrayList<String>();
        lines.add(".key(%s, %s)".formatted(m.key().fieldName(), m.incrementing()));
        lines.add(".columns(%s)".formatted(
                m.columns().stream().map(ModelInfo.ColumnInfo::fieldName)
                        .reduce((a, b) -> a + ", " + b).orElse("")));
        lines.add(".factory(%s::new)".formatted(m.simpleName()));
        lines.add(".timestamps(%s)".formatted(m.timestamps()));
        lines.add(".identityMap(%s)".formatted(m.identityMap()));
        if (m.connection() != null) lines.add(".connection(%s)".formatted(Code.str(m.connection())));
        if (m.softDeletes()) lines.add(".softDeletes(%s)".formatted(Code.str(m.softDeleteColumn())));
        if (!m.guarded().isEmpty()) lines.add(".guarded(%s)".formatted(Code.strList(m.guarded())));
        if (!m.fillable().isEmpty()) lines.add(".fillable(%s)".formatted(Code.strList(m.fillable())));
        if (!m.globalScopes().isEmpty()) {
            lines.add(".globalScopes(%s)".formatted(Code.strList(
                    m.globalScopes().stream().map(ModelInfo.ScopeInfo::scopeName).toList())));
        }
        if (!m.relations().isEmpty()) {
            lines.add(".relations(");
            var defs = m.relations().stream().map(MetamodelGenerator::relationDef).toList();
            for (int i = 0; i < defs.size(); i++) {
                lines.add("        " + defs.get(i) + (i < defs.size() - 1 ? "," : ""));
            }
            lines.add(")");
        }
        for (String l : lines) c.line("        %s", l);
        c.line("        .build();");
    }

    private static String relationDef(ModelInfo.RelationInfo r) {
        return switch (r.kind()) {
            case HAS_MANY -> "RelationDef.hasMany(%s, %s.class, %s, %s)".formatted(
                    Code.str(r.name()), r.targetType(), Code.str(r.foreignKey()),
                    Code.str(blankToNull(r.localKey())));
            case HAS_ONE -> "RelationDef.hasOne(%s, %s.class, %s, %s)".formatted(
                    Code.str(r.name()), r.targetType(), Code.str(r.foreignKey()),
                    Code.str(blankToNull(r.localKey())));
            case BELONGS_TO -> "RelationDef.belongsTo(%s, %s.class, %s, %s)".formatted(
                    Code.str(r.name()), r.targetType(), Code.str(r.foreignKey()),
                    Code.str(blankToNull(r.localKey())));
            case BELONGS_TO_MANY -> "RelationDef.belongsToMany(%s, %s.class, %s, %s, %s, java.util.List.of(%s), %s, %s)"
                    .formatted(Code.str(r.name()), r.targetType(), Code.str(r.pivotTable()),
                               Code.str(r.foreignPivotKey()), Code.str(r.relatedPivotKey()),
                               Code.strList(r.withPivot()), r.pivotTimestamps(),
                               Code.str(blankToNull(r.localKey())));
            case HAS_MANY_THROUGH -> "RelationDef.hasManyThrough(%s, %s.class, %s.class, %s, %s, %s)"
                    .formatted(Code.str(r.name()), r.targetType(), r.throughType(),
                               Code.str(r.foreignKey()), Code.str(r.secondKey()),
                               Code.str(blankToNull(r.localKey())));
            case MORPH_MANY -> "RelationDef.morphMany(%s, %s.class, %s, %s)".formatted(
                    Code.str(r.name()), r.targetType(), Code.str(r.morphPrefix()),
                    Code.str(blankToNull(r.localKey())));
            case MORPH_TO -> "RelationDef.morphTo(%s, java.util.List.of(%s), %s)".formatted(
                    Code.str(r.name()),
                    r.morphTypes().stream().map(t -> t + ".class")
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    Code.str(r.morphPrefix()));
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean isPrimitive(String typeName) {
        return List.of("boolean", "byte", "short", "int", "long", "char", "float", "double")
                .contains(typeName);
    }

    private static String primitiveDefault(String typeName) {
        return switch (typeName) {
            case "boolean" -> "false";
            case "char"    -> "'\\0'";
            case "float"   -> "0f";
            case "double"  -> "0d";
            case "long"    -> "0L";
            default        -> "0";
        };
    }

    /** {@code Col<M, V>} 的 V 不能是原始类型 */
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
