package io.jaravel.validation;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 一条校验规则。
 *
 * <p>字符串写法与 Builder 写法**共享同一套实现** —— {@code "max:200"} 解析出来的
 * 就是 {@code Rule.max(200)}。两套实现会不可避免地行为分叉,而「文档说等价、
 * 实际不等价」比没有第二种写法更糟。
 */
public interface Rule {

    /** 规则名,用于查消息模板({@code validation.max.string}) */
    String name();

    /** 校验。{@code true} 通过。 */
    boolean passes(Context context);

    /** 消息模板里的占位符,如 {@code :max → 200} */
    default Map<String, String> replacements() {
        return Map.of();
    }

    /**
     * 是否是「隐式规则」—— 即字段缺失时仍要执行。
     *
     * <p>绝大多数规则在字段缺失时直接跳过({@code max:200} 对不存在的字段无话可说),
     * 只有 {@code required} 一族必须在缺失时发声。不区分的话,
     * {@code "required|max:200"} 对缺失字段会什么都不报。
     */
    default boolean implicit() {
        return false;
    }

    /** 失败后是否停止该字段的后续规则。{@code required} 失败后再报 {@code email} 是噪音。 */
    default boolean stopsOnFailure() {
        return false;
    }

    /**
     * 校验上下文。
     *
     * @param attribute 当前字段的完整路径({@code items.2.qty})
     * @param value     当前值,可能为 null
     * @param present   字段是否存在(与「值为 null」不同)
     * @param data      完整数据,供跨字段规则使用
     */
    record Context(String attribute, Object value, boolean present,
                   Map<String, Object> data, Validator validator) {

        /** 取另一个字段的值,自动把 {@code *} 解析成当前所在的索引 */
        public Object other(String reference) {
            return Data.get(data, Data.resolveSibling(attribute, reference));
        }

        public boolean otherPresent(String reference) {
            return Data.has(data, Data.resolveSibling(attribute, reference));
        }

        public boolean isEmptyValue() {
            return Rules.isEmpty(value);
        }

        public String stringValue() {
            return value == null ? null : String.valueOf(value);
        }
    }

    // ================= 工厂方法(Builder 写法的入口) =================

    static Rule required()      { return Rules.required(); }
    static Rule nullable()      { return Rules.nullable(); }
    static Rule sometimes()     { return Rules.sometimes(); }
    static Rule filled()        { return Rules.filled(); }
    static Rule present()       { return Rules.present(); }
    static Rule prohibited()    { return Rules.prohibited(); }

    static Rule string()        { return Rules.typeRule("string", v -> v instanceof CharSequence); }
    static Rule integer()       { return Rules.integerRule(); }
    static Rule numeric()       { return Rules.numericRule(); }
    static Rule bool()          { return Rules.booleanRule(); }
    static Rule array()         { return Rules.arrayRule(); }

    static Rule min(Number n)   { return Rules.sizeRule("min", n, (size, limit) -> size.compareTo(limit) >= 0); }
    static Rule max(Number n)   { return Rules.sizeRule("max", n, (size, limit) -> size.compareTo(limit) <= 0); }
    static Rule size(Number n)  { return Rules.sizeRule("size", n, (size, limit) -> size.compareTo(limit) == 0); }

    static Rule between(Number low, Number high) { return Rules.between(low, high); }

    static Rule email()         { return Rules.email(List.of()); }
    static Rule url()           { return Rules.url(); }
    static Rule uuid()          { return Rules.uuid(); }
    static Rule ulid()          { return Rules.ulid(); }
    static Rule ip()            { return Rules.ip("ip"); }
    static Rule ipv4()          { return Rules.ip("ipv4"); }
    static Rule ipv6()          { return Rules.ip("ipv6"); }
    static Rule alpha()         { return Rules.pattern("alpha", "^[\\p{L}]+$"); }
    static Rule alphaNum()      { return Rules.pattern("alpha_num", "^[\\p{L}\\p{N}]+$"); }
    static Rule alphaDash()     { return Rules.pattern("alpha_dash", "^[\\p{L}\\p{N}_-]+$"); }
    static Rule regex(String p) { return Rules.regex(p, false); }
    static Rule notRegex(String p) { return Rules.regex(p, true); }

    static Rule in(List<?> allowed)       { return Rules.in(allowed, false); }
    static Rule notIn(List<?> forbidden)  { return Rules.in(forbidden, true); }

    @SafeVarargs
    static <E extends Enum<E>> Rule in(E... values) {
        return Rules.in(java.util.Arrays.stream(values).map(Enum::name).toList(), false);
    }

    static <E extends Enum<E>> Rule enumOf(Class<E> type) { return Rules.enumRule(type); }

    static Rule confirmed()               { return Rules.confirmed(); }
    static Rule same(String other)        { return Rules.comparison("same", other, true); }
    static Rule different(String other)   { return Rules.comparison("different", other, false); }
    static Rule accepted()                { return Rules.accepted(); }
    static Rule declined()                { return Rules.declined(); }
    static Rule distinct()                { return Rules.distinct(); }

    static Rule date()                    { return Rules.date(); }
    static Rule dateFormat(String f)      { return Rules.dateFormat(f); }
    static Rule after(String ref)         { return Rules.dateComparison("after", ref); }
    static Rule afterOrEqual(String ref)  { return Rules.dateComparison("after_or_equal", ref); }
    static Rule before(String ref)        { return Rules.dateComparison("before", ref); }
    static Rule beforeOrEqual(String ref) { return Rules.dateComparison("before_or_equal", ref); }

    static Rule requiredIf(String other, String value)      { return Rules.requiredIf(other, value, true); }
    static Rule requiredUnless(String other, String value)  { return Rules.requiredIf(other, value, false); }
    static Rule requiredWith(String... others)              { return Rules.requiredWith(List.of(others), true, false); }
    static Rule requiredWithAll(String... others)           { return Rules.requiredWith(List.of(others), true, true); }
    static Rule requiredWithout(String... others)           { return Rules.requiredWith(List.of(others), false, false); }
    static Rule requiredWithoutAll(String... others)        { return Rules.requiredWith(List.of(others), false, true); }

    static Rule startsWith(String... prefixes) { return Rules.affix("starts_with", List.of(prefixes), true); }
    static Rule endsWith(String... suffixes)   { return Rules.affix("ends_with", List.of(suffixes), false); }

    static Rule digits(int n)                  { return Rules.digits(n); }
    static Rule digitsBetween(int a, int b)    { return Rules.digitsBetween(a, b); }
    static Rule multipleOf(Number n)           { return Rules.multipleOf(n); }
    static Rule decimal(int min, int max)      { return Rules.decimal(min, max); }

    static Rule gt(String field)  { return Rules.fieldComparison("gt", field); }
    static Rule gte(String field) { return Rules.fieldComparison("gte", field); }
    static Rule lt(String field)  { return Rules.fieldComparison("lt", field); }
    static Rule lte(String field) { return Rules.fieldComparison("lte", field); }

    static Rule lowercase()  { return Rules.caseRule("lowercase", true); }
    static Rule uppercase()  { return Rules.caseRule("uppercase", false); }
    static Rule ascii()      { return Rules.pattern("ascii", "^\\p{ASCII}*$"); }
    static Rule hexColor()   { return Rules.pattern("hex_color", "^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"); }
    static Rule timezone()   { return Rules.timezone(); }
    static Rule json()       { return Rules.jsonRule(); }
    static Rule macAddress() { return Rules.pattern("mac_address",
            "^(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$"); }

    /** 密码强度 */
    static Rules.PasswordRule password() { return new Rules.PasswordRule(); }

    static DatabaseRule.Unique unique(String table, String column) {
        return new DatabaseRule.Unique(table, column);
    }

    static DatabaseRule.Exists exists(String table, String column) {
        return new DatabaseRule.Exists(table, column);
    }

    /**
     * 自定义规则。
     *
     * <p>**第一等公民而不是逃生口** —— 60 条内置规则永远覆盖不了业务校验,
     * 而「自定义规则很别扭」会逼用户把校验逻辑塞进控制器。
     *
     * <pre>{@code
     * Rule.of("even", v -> v instanceof Number n && n.intValue() % 2 == 0)
     *     .withMessage(":attribute 必须是偶数")
     * }</pre>
     */
    static Rules.CustomRule of(String name, Predicate<Object> test) {
        return new Rules.CustomRule(name, test);
    }

    /**
     * 能看到全部数据的自定义规则。
     *
     * <p>换个名字而不是重载 {@code of}:{@code Predicate<Object>} 与
     * {@code Predicate<Context>} 在 lambda 推断下是歧义的(擦除后签名相同),
     * 重载会让 {@code Rule.of("x", v -> ...)} 编译不过。
     */
    static Rules.CustomRule ofContext(String name, java.util.function.Predicate<Context> test) {
        return Rules.contextRule(name, test);
    }
}
