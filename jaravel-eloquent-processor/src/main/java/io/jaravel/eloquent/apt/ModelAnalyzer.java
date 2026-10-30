package io.jaravel.eloquent.apt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.BelongsToMany;
import io.jaravel.eloquent.annotations.Column;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.GlobalScope;
import io.jaravel.eloquent.annotations.Guarded;
import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.HasManyThrough;
import io.jaravel.eloquent.annotations.HasOneThrough;
import io.jaravel.eloquent.annotations.HasOne;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.MorphMany;
import io.jaravel.eloquent.annotations.MorphOne;
import io.jaravel.eloquent.annotations.MorphTo;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.annotations.SoftDeletes;
import io.jaravel.eloquent.annotations.Transient;
import io.jaravel.eloquent.meta.Cast;
import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.support.Arr;

/**
 * 把 {@code @Model} 标注的类读成 {@link ModelInfo},顺带做**全部编译期校验**。
 *
 * <p>一条硬约束(已由 spike 验证):处理期被注解类的父类是**错误类型**
 * ({@code TypeKind.ERROR}),因为那个父类正是本处理器要生成的。因此这里
 * 只能看 {@code getEnclosedElements()} —— 本类自己声明的成员 —— 绝不能
 * 走 {@code getSuperclass()} 向上遍历。
 */
final class ModelAnalyzer {

    private final Elements elements;
    private final Types types;
    private final Messager messager;
    private boolean failed;

    ModelAnalyzer(Elements elements, Types types, Messager messager) {
        this.elements = elements;
        this.types = types;
        this.messager = messager;
    }

    boolean hasErrors() { return failed; }

    /** 分析失败返回 null —— 错误已经报给 Messager */
    ModelInfo analyze(TypeElement type) {
        var model = type.getAnnotation(io.jaravel.eloquent.annotations.Model.class);
        if (model == null) return null;

        if (!checkShape(type)) return null;

        String simpleName = type.getSimpleName().toString();
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();

        List<ModelInfo.ColumnInfo> columns = readColumns(type);
        if (columns.isEmpty()) {
            error(type, """
                    模型 %s 没有任何持久化字段。
                      Jaravel 用 public 字段表示列(ADR-010 方案 E'),例如:
                        @Id public Long id;
                        public String title;
                      如果某字段不想入库,标 @Transient。"""
                    .formatted(simpleName));
            return null;
        }

        ModelInfo.ColumnInfo key = findKey(type, columns, simpleName);
        if (key == null) return null;
        boolean incrementing = incrementing(type, key);

        String softDelete = null;
        var sd = type.getAnnotation(SoftDeletes.class);
        if (sd != null) {
            final String sdColumn = sd.column();
            softDelete = sdColumn;
            if (columns.stream().noneMatch(c -> c.columnName().equals(sdColumn))) {
                error(type, """
                        @SoftDeletes 需要列 `%s`,但模型 %s 上没有这个字段。
                          已声明的列:%s
                          修复:加上 `public java.time.Instant %s;`
                                或 @SoftDeletes(column = "<已有的时间列>")"""
                        .formatted(softDelete, simpleName, columnNames(columns),
                                   io.jaravel.support.Str.camel(softDelete)));
                return null;
            }
        }

        List<ModelInfo.RelationInfo> relations = readRelations(type, simpleName, columns);
        List<String> guarded = readNames(type, Guarded.class, columns, "@Guarded");
        List<String> fillable = readNames(type, Fillable.class, columns, "@Fillable");
        var scopes = readScopes(type, Scope.class);
        var globalScopes = readScopes(type, GlobalScope.class);

        checkNameCollisions(type, columns, relations, scopes);

        if (failed) return null;

        String table = model.table().isBlank() ? Naming.table(simpleName) : model.table();

        return new ModelInfo(
                type, packageName, simpleName, type.getQualifiedName().toString(),
                table, model.connection().isBlank() ? null : model.connection(),
                model.timestamps(), model.identityMap(), model.generateHelpers(), model.accessors(),
                softDelete, columns, key, incrementing,
                guarded, fillable, relations, scopes, globalScopes);
    }

    // ================= 形态校验 =================

    /** 类本身必须能承载生成的代码 */
    private boolean checkShape(TypeElement type) {
        String simpleName = type.getSimpleName().toString();
        boolean ok = true;

        if (type.getKind() != ElementKind.CLASS) {
            error(type, "@Model 只能标在 class 上,而 " + simpleName + " 是 " + type.getKind()
                      + "。\n  record/interface 不能有可变的 public 字段,无法承载 Active Record。");
            return false;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, "@Model 不能标在 abstract 类上(" + simpleName + ")。"
                      + "\n  抽象类无法实例化,查询结果无处可放。"
                      + "\n  如果想共享字段,把公共字段放进一个普通父类并让各模型重复声明,"
                      + "或改用组合。");
            ok = false;
        }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "@Model 不能标在非静态内部类上(" + simpleName + ")。"
                      + "\n  内部类实例持有外层引用,无法用无参构造创建。"
                      + "\n  修复:加 static,或移到独立文件。");
            ok = false;
        }

        // 必须 extends 生成的基类,否则 Post.query() 之类的入口不存在
        TypeMirror superclass = type.getSuperclass();
        String expected = Naming.baseClass(simpleName);
        // 注意:此时 expected 还没生成,superclass 是 ERROR 类型 —— 只能比字符串
        String actual = superclass.getKind() == TypeKind.NONE ? "" : superclass.toString();
        String actualSimple = actual.contains(".") ? actual.substring(actual.lastIndexOf('.') + 1) : actual;
        if (!actualSimple.equals(expected)) {
            error(type, """
                    模型 %s 必须继承生成的基类 %s。
                      当前父类:%s
                      修复:public class %s extends %s { ... }
                      (%s 由 Jaravel 自动生成,提供 query()/find()/all() 等静态入口
                       和关联访问器;它在你第一次编译后出现在 target/generated-sources)"""
                    .formatted(simpleName, expected, actual.isEmpty() ? "java.lang.Object" : actual,
                               simpleName, expected, expected));
            ok = false;
        }

        if (!hasAccessibleNoArgConstructor(type)) {
            error(type, """
                    模型 %s 需要一个可访问的无参构造函数 —— 查询结果要靠它实例化。
                      你声明了带参构造,Java 便不再提供默认构造。
                      修复:补上 `public %s() {}`"""
                    .formatted(simpleName, simpleName));
            ok = false;
        }
        return ok;
    }

    private boolean hasAccessibleNoArgConstructor(TypeElement type) {
        var ctors = type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        if (ctors.isEmpty()) return true;                 // 隐式默认构造
        return ctors.stream().anyMatch(c -> c.getParameters().isEmpty()
                && !c.getModifiers().contains(Modifier.PRIVATE));
    }

    // ================= 列 =================

    private List<ModelInfo.ColumnInfo> readColumns(TypeElement type) {
        var out = new ArrayList<ModelInfo.ColumnInfo>();
        int index = 0;

        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) continue;
            var field = (VariableElement) member;
            var mods = field.getModifiers();

            if (mods.contains(Modifier.STATIC)) continue;
            if (field.getAnnotation(Transient.class) != null) continue;

            String fieldName = field.getSimpleName().toString();

            if (!mods.contains(Modifier.PUBLIC)) {
                error(field, """
                        字段 %s.%s 不是 public,Jaravel 读不到也写不进。
                          Active Record 用 public 字段换取零反射(ADR-010 方案 E'):
                          生成的 Col 直接 `m -> m.%s` 访问,私有字段做不到。
                          修复:改成 public,或标 @Transient 排除。"""
                        .formatted(type.getSimpleName(), fieldName, fieldName));
                continue;
            }
            if (mods.contains(Modifier.FINAL)) {
                error(field, "字段 " + type.getSimpleName() + "." + fieldName
                          + " 是 final,数据库回填时无法赋值。"
                          + "\n  修复:去掉 final,或标 @Transient。");
                continue;
            }

            var columnAnn = field.getAnnotation(Column.class);
            String columnName = columnAnn != null && !columnAnn.value().isBlank()
                    ? columnAnn.value() : Naming.column(fieldName);

            var idAnn = field.getAnnotation(Id.class);
            if (idAnn != null && !idAnn.name().isBlank()) columnName = idAnn.name();

            String castType = null;
            List<String> castArgs = List.of();
            var use = field.getAnnotation(Cast.Use.class);
            if (use != null) {
                castType = readCastType(field);
                castArgs = List.of(use.args());
                if (castType != null) checkCastCompatible(field, castType);
            }

            out.add(new ModelInfo.ColumnInfo(
                    field, fieldName, columnName,
                    field.asType().toString(),
                    boxedLiteral(field.asType()),
                    castType, castArgs,
                    field.getAnnotation(Hidden.class) != null,
                    index++));
        }
        return out;
    }

    /** {@code @Cast.Use(Json.class)} 的 Class 值要从 mirror 取(直接 .value() 会抛 MirroredTypeException) */
    private String readCastType(VariableElement field) {
        for (AnnotationMirror m : field.getAnnotationMirrors()) {
            if (!m.getAnnotationType().toString().equals(Cast.Use.class.getCanonicalName())) continue;
            for (var e : m.getElementValues().entrySet()) {
                if (e.getKey().getSimpleName().contentEquals("value")) {
                    return e.getValue().getValue().toString();
                }
            }
        }
        return null;
    }

    /**
     * 转换器的 {@code Cast<M,D>} 的 M 必须与字段类型相容。
     *
     * <p>这是 docs/06 §6 想要的那类报错在**新设计下的等价物**:
     * 关联改成类级注解后,「注解目标与字段泛型不符」不可能发生了,
     * 但 Cast 仍然存在同类的「注解声明的类型 ≠ 字段类型」问题。
     */
    private void checkCastCompatible(VariableElement field, String castTypeName) {
        TypeElement castType = elements.getTypeElement(castTypeName);
        if (castType == null) return;                      // 另一轮才生成,交给 javac

        TypeMirror modelSide = castModelSide(castType);
        if (modelSide == null) return;

        TypeMirror fieldType = field.asType();
        if (types.isAssignable(types.erasure(modelSide), types.erasure(fieldType))) return;

        error(field, """
                @Cast.Use(%s.class) 的模型侧类型是 %s,但字段 %s 的类型是 %s。
                  修复:把字段类型改成 %s,或换一个 Cast<%s, ?> 的实现。"""
                .formatted(simple(castTypeName), modelSide, field.getSimpleName(), fieldType,
                           modelSide, fieldType));
    }

    /** 取 {@code implements Cast<M, D>} 里的 M */
    private TypeMirror castModelSide(TypeElement castType) {
        for (TypeMirror itf : castType.getInterfaces()) {
            if (!(itf instanceof DeclaredType dt)) continue;
            if (!types.erasure(dt).toString().equals(Cast.class.getCanonicalName())) continue;
            var args = dt.getTypeArguments();
            if (args.size() == 2) return args.get(0);
        }
        // 可能从父类继承的实现,一层层找代价大且少见,放过交给运行时
        return null;
    }

    private ModelInfo.ColumnInfo findKey(TypeElement type, List<ModelInfo.ColumnInfo> columns,
                                         String simpleName) {
        var annotated = columns.stream()
                .filter(c -> c.element().getAnnotation(Id.class) != null)
                .toList();

        if (annotated.size() > 1) {
            error(annotated.get(1).element(), """
                    模型 %s 上有 %d 个 @Id(%s)。
                      Jaravel 当前只支持单列主键。
                      复合主键请用一个代理自增列作 @Id,再对业务列加唯一索引
                      —— 这也是 Laravel 的做法。"""
                    .formatted(simpleName, annotated.size(),
                               annotated.stream().map(ModelInfo.ColumnInfo::fieldName).toList()));
            return null;
        }
        if (annotated.size() == 1) return annotated.get(0);

        // 回落到约定:名为 id 的列
        var byConvention = columns.stream()
                .filter(c -> c.columnName().equals("id"))
                .findFirst();
        if (byConvention.isPresent()) return byConvention.get();

        error(type, """
                模型 %s 没有主键:既没有 @Id,也没有名为 `id` 的列。
                  已声明的列:%s
                  修复:加 `@Id public Long id;`,或给已有字段标 @Id。"""
                .formatted(simpleName, columnNames(columns)));
        return null;
    }

    private boolean incrementing(TypeElement type, ModelInfo.ColumnInfo key) {
        var ann = key.element().getAnnotation(Id.class);
        return ann == null || ann.incrementing();
    }

    // ================= 关联 =================

    private List<ModelInfo.RelationInfo> readRelations(
            TypeElement type, String simpleName, List<ModelInfo.ColumnInfo> columns) {

        var out = new ArrayList<ModelInfo.RelationInfo>();
        var seen = new LinkedHashSet<String>();

        for (var a : type.getAnnotationsByType(HasMany.class)) {
            String fk = a.foreignKey().isBlank() ? Naming.foreignKey(simpleName) : a.foreignKey();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.HAS_MANY,
                    targetOf(type, HasMany.class, a.name(), "target"), fk, a.localKey(),
                    null, null, null, null, null, List.of(), false, null, List.of()));
        }
        for (var a : type.getAnnotationsByType(HasOne.class)) {
            String fk = a.foreignKey().isBlank() ? Naming.foreignKey(simpleName) : a.foreignKey();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.HAS_ONE,
                    targetOf(type, HasOne.class, a.name(), "target"), fk, a.localKey(),
                    null, null, null, null, null, List.of(), false, null, List.of()));
        }
        for (var a : type.getAnnotationsByType(BelongsTo.class)) {
            String target = targetOf(type, BelongsTo.class, a.name(), "target");
            String fk = a.foreignKey().isBlank() ? Naming.foreignKey(simple(target)) : a.foreignKey();
            // 唯一一类外键落在**本模型**上的关联,因此能在编译期核对
            checkForeignKeyOnSelf(type, simpleName, columns, a.name(), fk, target, a.foreignKey().isBlank());
            out.add(relation(type, seen, a.name(), RelationDef.Kind.BELONGS_TO,
                    target, fk, a.ownerKey(),
                    null, null, null, null, null, List.of(), false, null, List.of()));
        }
        for (var a : type.getAnnotationsByType(BelongsToMany.class)) {
            String target = targetOf(type, BelongsToMany.class, a.name(), "target");
            String pivot = a.pivot().isBlank() ? Naming.pivotTable(simpleName, simple(target)) : a.pivot();
            String fpk = a.foreignPivotKey().isBlank() ? Naming.foreignKey(simpleName) : a.foreignPivotKey();
            String rpk = a.relatedPivotKey().isBlank() ? Naming.foreignKey(simple(target)) : a.relatedPivotKey();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.BELONGS_TO_MANY,
                    target, null, null, null, null, pivot, fpk, rpk,
                    List.of(a.withPivot()), a.pivotTimestamps(), null, List.of()));
        }
        for (var a : type.getAnnotationsByType(HasManyThrough.class)) {
            String target = targetOf(type, HasManyThrough.class, a.name(), "target");
            String through = targetOf(type, HasManyThrough.class, a.name(), "through");
            String first = a.firstKey().isBlank() ? Naming.foreignKey(simpleName) : a.firstKey();
            String second = a.secondKey().isBlank() ? Naming.foreignKey(simple(through)) : a.secondKey();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.HAS_MANY_THROUGH,
                    target, first, null, through, second, null, null, null,
                    List.of(), false, null, List.of()));
        }
        // 与 HasManyThrough 完全平行 —— 只是 Kind 不同。
        //
        // 没把两个循环合成一个泛型方法:注解类型不共父类,
        // getAnnotationsByType 也没有共同接口，强行抽象要走反射。
        // 重复六行比那好
        for (var a : type.getAnnotationsByType(HasOneThrough.class)) {
            String target = targetOf(type, HasOneThrough.class, a.name(), "target");
            String through = targetOf(type, HasOneThrough.class, a.name(), "through");
            String first = a.firstKey().isBlank() ? Naming.foreignKey(simpleName) : a.firstKey();
            String second = a.secondKey().isBlank() ? Naming.foreignKey(simple(through)) : a.secondKey();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.HAS_ONE_THROUGH,
                    target, first, null, through, second, null, null, null,
                    List.of(), false, null, List.of()));
        }
        for (var a : type.getAnnotationsByType(MorphMany.class)) {
            String prefix = a.morphName().isBlank() ? Naming.morphPrefix(a.name()) : a.morphName();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.MORPH_MANY,
                    targetOf(type, MorphMany.class, a.name(), "target"), null, null,
                    null, null, null, null, null, List.of(), false, prefix, List.of()));
        }
        for (var a : type.getAnnotationsByType(MorphOne.class)) {
            String prefix = a.morphName().isBlank() ? Naming.morphPrefix(a.name()) : a.morphName();
            out.add(relation(type, seen, a.name(), RelationDef.Kind.MORPH_ONE,
                    targetOf(type, MorphOne.class, a.name(), "target"), null, null,
                    null, null, null, null, null, List.of(), false, prefix, List.of()));
        }
        for (var a : type.getAnnotationsByType(MorphTo.class)) {
            String prefix = a.morphName().isBlank() ? Naming.morphPrefix(a.name()) : a.morphName();
            List<String> candidates = morphTypesOf(type, a.name());
            if (candidates.isEmpty()) {
                error(type, "@MorphTo(name = \"" + a.name() + "\") 的 types 为空 —— "
                          + "多态归属必须列出所有可能的宿主类型,否则运行时拿到 "
                          + prefix + "_type 的值也无法解析成模型。");
            }
            checkColumnExists(type, columns, prefix + "_id", "@MorphTo(\"" + a.name() + "\")");
            checkColumnExists(type, columns, prefix + "_type", "@MorphTo(\"" + a.name() + "\")");
            out.add(relation(type, seen, a.name(), RelationDef.Kind.MORPH_TO,
                    null, null, null, null, null, null, null, null,
                    List.of(), false, prefix, candidates));
        }
        return out;
    }

    private ModelInfo.RelationInfo relation(
            TypeElement owner, Set<String> seen, String name, RelationDef.Kind kind,
            String target, String foreignKey, String localKey,
            String throughType, String secondKey,
            String pivot, String fpk, String rpk, List<String> withPivot, boolean pivotTs,
            String morphPrefix, List<String> morphTypes) {

        if (name.isBlank()) {
            error(owner, kind + " 关联的 name 不能为空 —— 它既是访问器方法名,"
                      + "也是 with(...) 里的路径段。");
        } else if (!seen.add(name)) {
            error(owner, "模型 " + owner.getSimpleName() + " 上有两个名为 \"" + name
                      + "\" 的关联。关联名要生成同名访问器方法,不能重复。");
        } else if (!name.matches("[a-z][A-Za-z0-9]*")) {
            error(owner, "关联名 \"" + name + "\" 不是合法的 Java 方法名(需小驼峰)。"
                      + "\n  它会生成访问器 " + owner.getSimpleName() + "." + name + "()。");
        }

        if (target != null) checkIsModel(owner, target, name);
        if (throughType != null) checkIsModel(owner, throughType, name);

        return new ModelInfo.RelationInfo(
                name, kind, target, target == null ? null : simple(target),
                foreignKey, localKey, throughType, secondKey,
                pivot, fpk, rpk, withPivot, pivotTs, morphPrefix, morphTypes);
    }

    /** target() 必须是 @Model 标注的类型 —— 否则生成的访问器返回类型不是 Model,编译不过 */
    private void checkIsModel(TypeElement owner, String targetName, String relationName) {
        TypeElement target = elements.getTypeElement(targetName);
        if (target == null) return;                        // 可能本轮才生成
        if (target.getAnnotation(io.jaravel.eloquent.annotations.Model.class) != null) return;

        error(owner, """
                关联 "%s" 的目标 %s 没有 @Model 注解。
                  关联目标必须是 Jaravel 模型:生成的访问器要返回 Rel<%s>,
                  而 Rel<T> 要求 T extends Model<T>。
                  修复:给 %s 加 @Model,或改为指向一个已有模型。"""
                .formatted(relationName, simple(targetName), simple(targetName), simple(targetName)));
    }

    /**
     * {@code @BelongsTo} 推断出的外键必须真的在本模型上。
     *
     * <p>这是 docs/06 §6 第二条错误的实现。能做到是因为 BelongsTo 的外键
     * 在**本**模型上,APT 看得见;HasMany 的外键在目标模型上,跨文件编译顺序
     * 不保证,只能留到运行时报(那条消息在 RelationSupport#missingColumn)。
     */
    private void checkForeignKeyOnSelf(TypeElement type, String simpleName,
                                       List<ModelInfo.ColumnInfo> columns,
                                       String relationName, String fk, String target,
                                       boolean inferred) {
        if (columns.stream().anyMatch(c -> c.columnName().equals(fk))) return;

        String hint = Arr.closest(fk, columnNames(columns))
                .map(c -> "\n  是否想用 `" + c + "` ?").orElse("");
        String fixField = io.jaravel.support.Str.camel(fk);

        error(type, """
                @BelongsTo(name = "%s") %s的外键 `%s` 在模型 %s 上不存在%s
                  已声明的列:%s
                  修复:@BelongsTo(name = "%s", target = %s.class, foreignKey = "<已有列>")
                        或补上字段 `public Long %s;`"""
                .formatted(relationName,
                           inferred ? "按约定推断(目标单数名 + _id)" : "显式指定",
                           fk, simpleName, hint,
                           columnNames(columns),
                           relationName, simple(target), fixField));
    }

    private void checkColumnExists(TypeElement type, List<ModelInfo.ColumnInfo> columns,
                                   String column, String who) {
        if (columns.stream().anyMatch(c -> c.columnName().equals(column))) return;
        String hint = Arr.closest(column, columnNames(columns))
                .map(c -> " 是否想用 `" + c + "` ?").orElse("");
        error(type, who + " 需要列 `" + column + "`,但模型 " + type.getSimpleName()
                  + " 上没有。" + hint
                  + "\n  已声明的列:" + columnNames(columns)
                  + "\n  修复:补上该字段,或用 morphName 指定别的前缀。");
    }

    // ================= @Guarded / @Fillable =================

    /** 列名拼错在这里拦住,并给出「是否想写」—— docs/06 §6 第三条错误 */
    private List<String> readNames(TypeElement type, Class<? extends java.lang.annotation.Annotation> ann,
                                   List<ModelInfo.ColumnInfo> columns, String label) {
        String[] values;
        if (ann == Guarded.class) {
            var a = type.getAnnotation(Guarded.class);
            if (a == null) return List.of();
            values = a.value();
        } else {
            var a = type.getAnnotation(Fillable.class);
            if (a == null) return List.of();
            values = a.value();
        }

        var names = columnNames(columns);
        for (String v : values) {
            if (v.equals("*")) continue;                   // @Guarded("*") = 全部守卫
            if (names.contains(v)) continue;
            String hint = Arr.closest(v, names)
                    .map(c -> " 是否想写 `" + c + "` ?").orElse("");
            error(type, label + " 列出的列 `" + v + "` 不存在。" + hint
                      + "\n  已声明的列:" + names);
        }
        return List.of(values);
    }

    // ================= Scope =================

    private List<ModelInfo.ScopeInfo> readScopes(
            TypeElement type, Class<? extends java.lang.annotation.Annotation> ann) {

        boolean global = ann == GlobalScope.class;
        var out = new ArrayList<ModelInfo.ScopeInfo>();
        String simpleName = type.getSimpleName().toString();
        String queryType = "io.jaravel.eloquent.query.Query<" + type.getQualifiedName() + ">";

        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            if (member.getAnnotation(ann) == null) continue;
            var method = (ExecutableElement) member;
            String methodName = method.getSimpleName().toString();

            if (!method.getModifiers().contains(Modifier.STATIC)) {
                error(method, "@" + ann.getSimpleName() + " 方法 " + methodName
                          + " 必须是 static —— 作用域作用在查询上,此刻还没有模型实例。"
                          + "\n  修复:public static Query<" + simpleName + "> " + methodName
                          + "(Query<" + simpleName + "> q) { ... }");
                continue;
            }

            var params = method.getParameters();
            boolean firstIsQuery = !params.isEmpty()
                    && types.erasure(params.get(0).asType()).toString()
                            .equals("io.jaravel.eloquent.query.Query");
            if (!firstIsQuery) {
                error(method, """
                        @%s 方法 %s 的第一个参数必须是 Query<%s>。
                          当前签名:%s(%s)
                          修复:public static Query<%s> %s(Query<%s> q%s) { ... }"""
                        .formatted(ann.getSimpleName(), methodName, simpleName, methodName,
                                   params.stream().map(p -> p.asType().toString()).toList(),
                                   simpleName, methodName, simpleName,
                                   params.isEmpty() ? "" : ", /* 原有参数 */"));
                continue;
            }
            if (global && params.size() > 1) {
                error(method, "@GlobalScope 方法 " + methodName + " 不能有额外参数 —— "
                          + "全局作用域由框架自动套用,没有地方传参。"
                          + "\n  需要参数的请改用 @Scope。");
                continue;
            }
            if (types.erasure(method.getReturnType()).toString()
                    .equals("io.jaravel.eloquent.query.Query")) {
                // ok
            } else if (method.getReturnType().getKind() == TypeKind.VOID) {
                // 也允许:直接改传入的 query
            } else {
                error(method, "@" + ann.getSimpleName() + " 方法 " + methodName
                          + " 的返回类型必须是 Query<" + simpleName + "> 或 void,"
                          + "当前是 " + method.getReturnType() + "。");
                continue;
            }

            String scopeName = global
                    ? (((GlobalScope) member.getAnnotation(ann)).value().isBlank()
                            ? methodName : ((GlobalScope) member.getAnnotation(ann)).value())
                    : methodName;

            // 跳过第一个 Query 参数
            var extraTypes = params.stream().skip(1).map(p -> p.asType().toString()).toList();
            var extraNames = params.stream().skip(1).map(p -> p.getSimpleName().toString()).toList();
            out.add(new ModelInfo.ScopeInfo(methodName, scopeName, extraTypes, extraNames));
        }
        return out;
    }

    // ================= 命名冲突 =================

    /**
     * 列、关联、作用域都会生成同名方法,撞名会让生成代码编译不过 ——
     * 但那时报错指向的是生成文件,用户看不懂。所以在这里提前拦。
     */
    private void checkNameCollisions(TypeElement type, List<ModelInfo.ColumnInfo> columns,
                                     List<ModelInfo.RelationInfo> relations,
                                     List<ModelInfo.ScopeInfo> scopes) {
        var used = new LinkedHashSet<String>();
        for (var c : columns) used.add(c.fieldName());

        for (var r : relations) {
            if (used.contains(r.name())) {
                error(type, """
                        关联 "%s" 与同名字段冲突。
                          关联会生成访问器 %s(),而字段 %s 已经占用了这个名字。
                          修复:给关联换个名字(如 %sRel),或改字段名。"""
                        .formatted(r.name(), r.name(), r.name(), r.name()));
            }
            used.add(r.name());
        }
        for (var s : scopes) {
            if (relations.stream().anyMatch(r -> r.name().equals(s.methodName()))) {
                error(type, "@Scope 方法 " + s.methodName() + " 与同名关联冲突 —— "
                          + "两者都会在生成的 Query 上占用这个方法名。");
            }
        }

        // 保留名:生成代码里一定存在的方法
        Set<String> reserved = Set.of("query", "find", "findOrFail", "all", "create", "meta",
                                      "save", "delete", "refresh", "toMap", "keyValue");
        for (var r : relations) {
            if (reserved.contains(r.name())) {
                error(type, "关联名 \"" + r.name() + "\" 是 Jaravel 的保留方法名"
                          + "(生成的基类上已有 " + r.name() + "())。请换一个名字。");
            }
        }
    }

    // ================= 工具 =================

    /** 从 mirror 取 Class 值的全限定名 */
    private String targetOf(TypeElement owner, Class<?> annotationType,
                            String relationName, String member) {
        String annName = annotationType.getCanonicalName();
        for (AnnotationMirror m : owner.getAnnotationMirrors()) {
            String t = m.getAnnotationType().toString();
            if (t.equals(annName)) {
                String found = readClassMember(m, member, relationName);
                if (found != null) return found;
            } else if (t.equals(annName + ".List")) {
                for (var e : m.getElementValues().entrySet()) {
                    if (!e.getKey().getSimpleName().contentEquals("value")) continue;
                    @SuppressWarnings("unchecked")
                    var nested = (List<? extends javax.lang.model.element.AnnotationValue>) e.getValue().getValue();
                    for (var av : nested) {
                        if (av.getValue() instanceof AnnotationMirror inner) {
                            String found = readClassMember(inner, member, relationName);
                            if (found != null) return found;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String readClassMember(AnnotationMirror m, String member, String relationName) {
        String name = null;
        String value = null;
        for (var e : m.getElementValues().entrySet()) {
            String k = e.getKey().getSimpleName().toString();
            if (k.equals("name")) name = e.getValue().getValue().toString();
            if (k.equals(member)) value = e.getValue().getValue().toString();
        }
        return relationName.equals(name) ? value : null;
    }

    private List<String> morphTypesOf(TypeElement owner, String relationName) {
        for (AnnotationMirror m : owner.getAnnotationMirrors()) {
            String t = m.getAnnotationType().toString();
            if (!t.equals(MorphTo.class.getCanonicalName())
                    && !t.equals(MorphTo.class.getCanonicalName() + ".List")) continue;

            for (AnnotationMirror single : flatten(m)) {
                String name = null;
                List<String> types = List.of();
                for (var e : single.getElementValues().entrySet()) {
                    String k = e.getKey().getSimpleName().toString();
                    if (k.equals("name")) name = e.getValue().getValue().toString();
                    if (k.equals("types")) {
                        @SuppressWarnings("unchecked")
                        var vals = (List<? extends javax.lang.model.element.AnnotationValue>)
                                e.getValue().getValue();
                        types = vals.stream().map(v -> v.getValue().toString()).toList();
                    }
                }
                if (relationName.equals(name)) return types;
            }
        }
        return List.of();
    }

    /** @Repeatable 的容器拆成单个 */
    private List<AnnotationMirror> flatten(AnnotationMirror m) {
        if (!m.getAnnotationType().toString().endsWith(".List")) return List.of(m);
        var out = new ArrayList<AnnotationMirror>();
        for (var e : m.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals("value")) continue;
            @SuppressWarnings("unchecked")
            var nested = (List<? extends javax.lang.model.element.AnnotationValue>) e.getValue().getValue();
            for (var av : nested) {
                if (av.getValue() instanceof AnnotationMirror inner) out.add(inner);
            }
        }
        return out;
    }

    /** {@code Col.of(name, X.class, ...)} 里的 X —— 原始类型要装箱 */
    private String boxedLiteral(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Boolean";
            case BYTE    -> "Byte";
            case SHORT   -> "Short";
            case INT     -> "Integer";
            case LONG    -> "Long";
            case CHAR    -> "Character";
            case FLOAT   -> "Float";
            case DOUBLE  -> "Double";
            // 泛型类型的 Class 字面量只能写擦除后的,这是 Java 的限制
            default      -> types.erasure(type).toString();
        };
    }

    private static List<String> columnNames(List<ModelInfo.ColumnInfo> columns) {
        return columns.stream().map(ModelInfo.ColumnInfo::columnName).toList();
    }

    private static String simple(String qualified) {
        if (qualified == null) return null;
        int i = qualified.lastIndexOf('.');
        return i < 0 ? qualified : qualified.substring(i + 1);
    }

    private void error(Element where, String message) {
        failed = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, where);
    }
}
