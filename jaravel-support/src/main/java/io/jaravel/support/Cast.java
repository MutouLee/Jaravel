package io.jaravel.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 字符串 / Object → 目标类型的宽松转换。
 *
 * <p>贯穿配置读取、请求参数绑定、数据库取值三处。集中在一处实现,
 * 保证「`?page=2` 转 int」与「`config('x') 转 int`」行为一致。
 *
 * <p>转换失败抛 {@link CastException} 并给出清晰消息 —— 不静默返回 null,
 * 那会把问题推到更远的地方才暴露。
 */
public final class Cast {

    private Cast() {}

    public static class CastException extends RuntimeException {
        public CastException(String message) { super(message); }
    }

    @SuppressWarnings("unchecked")
    public static <T> T to(Object value, Class<T> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;

        String s = value instanceof String str ? str : String.valueOf(value);

        try {
            if (type == String.class)                       return (T) s;
            if (type == Integer.class || type == int.class) return (T) Integer.valueOf(parseIntLenient(s));
            if (type == Long.class    || type == long.class) return (T) Long.valueOf(parseLongLenient(s));
            if (type == Double.class  || type == double.class) return (T) Double.valueOf(s.trim());
            if (type == Float.class   || type == float.class) return (T) Float.valueOf(s.trim());
            if (type == Short.class   || type == short.class) return (T) Short.valueOf(s.trim());
            if (type == Byte.class    || type == byte.class)  return (T) Byte.valueOf(s.trim());
            if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(parseBool(s));
            if (type == BigDecimal.class)                   return (T) new BigDecimal(s.trim());
            if (type == Instant.class)                      return (T) parseInstant(s);
            if (type == LocalDate.class)                    return (T) LocalDate.parse(s.trim());
            if (type == LocalDateTime.class)                return (T) LocalDateTime.parse(s.trim());
            if (type == java.time.Duration.class)           return (T) parseDuration(s);
            if (type == java.net.URI.class)                 return (T) java.net.URI.create(s.trim());
            if (type == java.time.ZoneId.class)             return (T) java.time.ZoneId.of(s.trim());
            if (type == Locale.class)                       return (T) Locale.forLanguageTag(s.trim().replace('_', '-'));
            if (type.isEnum())                              return (T) parseEnum(s, type);
        } catch (CastException e) {
            throw e;
        } catch (Exception e) {
            throw new CastException("无法把 \"" + s + "\" 转换为 " + type.getSimpleName()
                    + "(" + e.getClass().getSimpleName() + ")");
        }

        throw new CastException("不支持的目标类型:" + type.getName()
                + "。已支持:String / 数值 / boolean / BigDecimal / Instant / LocalDate / "
                + "LocalDateTime / Duration / URI / ZoneId / Locale / enum");
    }

    /** 容忍 "1_000" 与前后空格 */
    private static int parseIntLenient(String s) {
        return Integer.parseInt(s.trim().replace("_", ""));
    }

    private static long parseLongLenient(String s) {
        return Long.parseLong(s.trim().replace("_", ""));
    }

    /** 比 Boolean.parseBoolean 宽松:接受 1/0、yes/no、on/off */
    private static boolean parseBool(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "on", "y").contains(v))  return true;
        if (List.of("false", "0", "no", "off", "n", "").contains(v)) return false;
        throw new CastException("无法把 \"" + s + "\" 转换为 boolean"
                + "(接受 true/false、1/0、yes/no、on/off)");
    }

    private static Instant parseInstant(String s) {
        String v = s.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // 纯数字 = epoch。SQLite 的 setTimestamp、以及手写 unix 时间戳列都会这样,
            // 遇到一次就会让「存进去再读出来」直接炸,所以必须容忍。
            if (!v.isEmpty() && v.chars().allMatch(Character::isDigit)) {
                long n = Long.parseLong(v);
                // 10 位是秒(到 2286 年),13 位是毫秒
                return v.length() <= 10 ? Instant.ofEpochSecond(n) : Instant.ofEpochMilli(n);
            }
            // 容忍 "2026-10-28 14:30:00" 与 "2026-10-28"
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            return LocalDateTime.parse(v.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
    }

    /**
     * 支持配置里常见的 {@code 120m} / {@code 2h} / {@code 30s} / {@code 7d},
     * 也支持标准 ISO-8601 的 {@code PT2H}。
     */
    public static java.time.Duration parseDuration(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("p")) return java.time.Duration.parse(v);
        if (v.endsWith("ms")) return java.time.Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        char unit = v.charAt(v.length() - 1);
        if (Character.isDigit(unit)) return java.time.Duration.ofSeconds(Long.parseLong(v));
        long n = Long.parseLong(v.substring(0, v.length() - 1));
        return switch (unit) {
            case 's' -> java.time.Duration.ofSeconds(n);
            case 'm' -> java.time.Duration.ofMinutes(n);
            case 'h' -> java.time.Duration.ofHours(n);
            case 'd' -> java.time.Duration.ofDays(n);
            default -> throw new CastException("无法识别的时间单位 '" + unit + "' in \"" + s
                    + "\"(支持 ms/s/m/h/d 或 ISO-8601 如 PT2H)");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseEnum(String s, Class<?> type) {
        String v = s.trim();
        Object[] constants = type.getEnumConstants();
        // 先精确匹配,再忽略大小写与连字符
        for (Object c : constants) if (((Enum) c).name().equals(v)) return c;
        String normalized = v.replace('-', '_').toUpperCase(Locale.ROOT);
        for (Object c : constants) if (((Enum) c).name().equals(normalized)) return c;

        List<String> names = java.util.Arrays.stream(constants).map(c -> ((Enum) c).name()).toList();
        String hint = Arr.closest(v, names).map(n -> " 是否想写 " + n + "?").orElse("");
        throw new CastException("\"" + s + "\" 不是 " + type.getSimpleName() + " 的有效值。"
                + "可选:" + names + "。" + hint);
    }
}
