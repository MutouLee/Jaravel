package io.jaravel.eloquent.apt;

import java.util.List;
import java.util.Optional;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

import io.jaravel.eloquent.meta.RelationDef;

/**
 * 分析完成的模型描述 —— 生成器的唯一输入。
 *
 * <p>刻意把「分析」和「生成」隔开:生成器不碰 {@code javax.lang.model},
 * 因此可以单独测试(给个 ModelInfo,断言输出字符串),不必起一次 javac。
 */
record ModelInfo(
        TypeElement element,
        String packageName,
        String simpleName,
        String qualifiedName,
        String table,
        String connection,
        boolean timestamps,
        boolean identityMap,
        boolean generateHelpers,
        boolean accessors,
        String softDeleteColumn,          // null = 不启用
        List<ColumnInfo> columns,
        ColumnInfo key,
        boolean incrementing,
        List<String> guarded,
        List<String> fillable,
        List<RelationInfo> relations,
        List<ScopeInfo> scopes,
        List<ScopeInfo> globalScopes
) {

    String baseClass()      { return Naming.baseClass(simpleName); }
    String metamodelClass() { return Naming.metamodelClass(simpleName); }
    String queryClass()     { return Naming.queryClass(simpleName); }

    boolean softDeletes() { return softDeleteColumn != null; }

    Optional<ColumnInfo> column(String name) {
        return columns.stream().filter(c -> c.columnName().equals(name)).findFirst();
    }

    List<String> columnNames() {
        return columns.stream().map(ColumnInfo::columnName).toList();
    }

    /**
     * 一个持久化字段。
     *
     * @param fieldName  Java 字段名
     * @param columnName 数据库列名
     * @param typeName   字段类型的源码写法(含泛型),直接贴进生成代码
     * @param boxedType  用于 {@code Col.of(..., X.class, ...)} 的类型字面量
     * @param castType   {@code @Cast.Use} 指定的转换器全限定名,null = 无
     * @param castArgs   转换器构造参数
     * @param hidden     是否在 {@code toMap()} 中隐藏
     */
    record ColumnInfo(
            VariableElement element,
            String fieldName,
            String columnName,
            String typeName,
            String boxedType,
            String castType,
            List<String> castArgs,
            boolean hidden,
            int index
    ) {}

    /**
     * 一个关联。
     *
     * @param targetType 目标模型全限定名
     * @param morphTypes {@code MorphTo} 的候选类型
     */
    record RelationInfo(
            String name,
            RelationDef.Kind kind,
            String targetType,
            String targetSimpleName,
            String foreignKey,
            String localKey,
            String throughType,
            String secondKey,
            String pivotTable,
            String foreignPivotKey,
            String relatedPivotKey,
            List<String> withPivot,
            boolean pivotTimestamps,
            String morphPrefix,
            List<String> morphTypes
    ) {
        boolean isMany() { return kind.isMany(); }
    }

    /** {@code @Scope} / {@code @GlobalScope} 标注的静态方法 */
    record ScopeInfo(String methodName, String scopeName, List<String> paramTypes,
                     List<String> paramNames) {}
}
