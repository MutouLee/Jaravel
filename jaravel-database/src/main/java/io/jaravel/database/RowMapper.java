package io.jaravel.database;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.support.Arr;
import io.jaravel.support.Cast;
import io.jaravel.support.Str;

/**
 * {@link Row} → record / POJO 的映射。
 *
 * <p>列名按 snake_case ↔ camelCase 自动匹配({@code created_at} → {@code createdAt}),
 * 与 Eloquent 的列名约定一致。
 *
 * <p>v0.1 用反射;v0.2 的 APT 会为 {@code @Model} 生成零反射的映射器。
 * 这里的反射开销只在「用 {@code select(sql, Dto.class)} 取 DTO」这条路径上,
 * 不影响 Eloquent 的主路径。
 */
public final class RowMapper {

    private RowMapper() {}

    private record Plan(Constructor<?> ctor, List<String> columnNames, Class<?>[] paramTypes,
                        boolean isRecord, List<Field> fields) {}

    private static final Map<Class<?>, Plan> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> T map(Row row, Class<T> type) {
        if (type == Row.class) return (T) row;
        if (type == Map.class) return (T) row.toMap();

        Plan plan = CACHE.computeIfAbsent(type, RowMapper::planFor);

        if (plan.isRecord()) {
            Object[] args = new Object[plan.columnNames().size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = valueFor(row, plan.columnNames().get(i), plan.paramTypes()[i], type);
            }
            try {
                return (T) plan.ctor().newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new MappingException("构造 " + type.getSimpleName() + " 失败:" + e.getMessage(), e);
            }
        }

        // POJO:无参构造 + 字段注入(含 ADR-010 的 public 字段模型)
        try {
            Object instance = plan.ctor().newInstance();
            for (Field f : plan.fields()) {
                String column = Str.snake(f.getName());
                if (!row.has(column) && !row.has(f.getName())) continue;
                Object v = valueFor(row, row.has(column) ? column : f.getName(), f.getType(), type);
                f.set(instance, v);
            }
            return (T) instance;
        } catch (ReflectiveOperationException e) {
            throw new MappingException("构造 " + type.getSimpleName() + " 失败:" + e.getMessage(), e);
        }
    }

    private static Object valueFor(Row row, String column, Class<?> target, Class<?> owner) {
        if (!row.has(column)) {
            String hint = Arr.closest(column, row.columns())
                    .map(c -> " 是否想找 " + c + " ?").orElse("");
            throw new MappingException(
                    "映射到 " + owner.getSimpleName() + " 需要列 " + column + ",但结果集中没有。" + hint
                  + "\n  结果集的列:" + row.columns()
                  + "\n  修复:在 select(...) 中包含该列,或给字段加 @Column 指定实际列名。");
        }
        Object raw = row.get(column);
        if (raw == null) return defaultFor(target);
        if (target.isInstance(raw)) return raw;
        // boolean 的跨方言差异集中在 Row.bool 处理
        if (target == boolean.class || target == Boolean.class) return row.bool(column);
        return Cast.to(raw, box(target));
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == char.class) return '\0';
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        return 0;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private static Plan planFor(Class<?> type) {
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            var names = new ArrayList<String>(components.length);
            var types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                names.add(Str.snake(components[i].getName()));
                types[i] = components[i].getType();
            }
            try {
                Constructor<?> ctor = type.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return new Plan(ctor, names, types, true, List.of());
            } catch (NoSuchMethodException e) {
                throw new MappingException("找不到 " + type.getSimpleName() + " 的规范构造函数", e);
            }
        }

        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new MappingException(
                    type.getSimpleName() + " 既不是 record,也没有无参构造函数,无法映射。"
                  + "\n  修复:改用 record,或添加一个无参构造函数。", e);
        }

        var fields = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return new Plan(ctor, List.of(), new Class<?>[0], false, List.copyOf(fields));
    }

    public static class MappingException extends RuntimeException {
        public MappingException(String message) { super(message); }
        public MappingException(String message, Throwable cause) { super(message, cause); }
    }
}
