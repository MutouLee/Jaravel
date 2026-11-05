package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验规则 → OpenAPI schema(docs/12 §5 表格里的「请求体校验约束」那一行)。
 *
 * <pre>
 * "title" : "required|string|max:200"   →  title: { type: string, maxLength: 200 }, required
 * "age"   : "nullable|integer|min:18"   →  age:   { type: [integer, null], minimum: 18 }
 * "role"  : "required|in:admin,user"    →  role:  { enum: [admin, user] }, required
 * </pre>
 *
 * <h2>★ 为什么这一行不需要 APT</h2>
 * <p>{@code FormRequest.rules()} 返回的是 {@code Map<String, String>} ——
 * 字段名到管道分隔的规则串。它在**运行时**就能读,而 OpenAPI 生成器
 * 本来就跑在应用进程里(在线命令)。
 *
 * <p>所以 §5 那张表里最有用的一行(前端最需要知道「POST 要传什么」)
 * 恰好是不用等 APT 的那一行。响应 schema 才真的需要 AST 分析 ——
 * 因为 {@code toArray} 的键是写在代码里的字面量,不是声明式数据。
 *
 * <h2>⚠ 没有 OpenAPI 对应物的规则会被跳过</h2>
 * <p>{@code exists:users,id} / {@code unique} / {@code after:today} 这类
 * **语义**规则在 schema 里没有表达方式。跳过它们不是遗漏:
 * 硬塞成 {@code description} 会让生成的客户端代码里出现一句中文注释,
 * 而真正的约束仍然只有服务端知道。
 *
 * <p>于是「schema 校验通过」不等于「服务端会接受」—— 这一点必须让读文档的人
 * 知道,所以生成器会在输出里说明。
 */
final class RuleSchema {

    private RuleSchema() {
    }

    /** 一个 FormRequest 的 rules() → JSON Schema 的 object */
    static Map<String, Object> of(Map<String, String> rules) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        rules.forEach((field, ruleString) -> {
            if (field.contains("*")) {
                // 通配字段(tags.*: integer)不是请求体里真实存在的键,
                // 把它原样写进 properties 会生成一个名叫 "tags.*" 的假字段
                return;
            }
            List<String> parts = split(ruleString);
            if (parts.stream().anyMatch(rule -> name(rule).equals("required"))) {
                required.add(field);
            }
            properties.put(field, propertyOf(parts));

            // Laravel 的 confirmed 意味着请求里还要有 <field>_confirmation。
            // 那是一个**真实存在的请求字段**,不写进 schema 的话前端照文档
            // 发请求会得到 422,而文档上看不出为什么
            if (parts.stream().anyMatch(rule -> name(rule).equals("confirmed"))) {
                properties.put(field + "_confirmation", propertyOf(parts));
                if (required.contains(field)) required.add(field + "_confirmation");
            }
        });

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            // 排序:Map.of 的迭代顺序是哈希序 —— 同一个 JVM 里稳定,
            // 但跨 JVM 版本可能变,而 openapi:diff 要的是逐字节稳定
            required.sort(String::compareTo);
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> propertyOf(List<String> rules) {
        var property = new LinkedHashMap<String, Object>();
        String type = typeOf(rules);
        boolean nullable = rules.stream().anyMatch(rule -> name(rule).equals("nullable"));

        // OpenAPI 3.1 用 type 数组表达可空(3.0 的 nullable: true 已废弃)——
        // 这个文档声明的是 3.1.0,所以用新写法
        property.put("type", nullable ? List.of(type, "null") : type);

        formatOf(rules).ifPresent(format -> property.put("format", format));

        for (String rule : rules) {
            applyConstraint(property, type, rule);
        }
        return property;
    }

    private static String typeOf(List<String> rules) {
        for (String rule : rules) {
            switch (name(rule)) {
                case "integer" -> { return "integer"; }
                case "numeric", "decimal" -> { return "number"; }
                case "boolean", "accepted", "declined" -> { return "boolean"; }
                case "array" -> { return "array"; }
                case "file", "image" -> { return "string"; }
                default -> { }
            }
        }
        // ★ 没有类型规则时按 string。
        //
        // 不是随便选的:HTTP 表单与查询串里的值**本来就都是字符串**,
        // 类型规则只是让校验器去转换。所以 string 是「没说」时的实况,
        // 而不是一个猜测。
        return "string";
    }

    private static java.util.Optional<String> formatOf(List<String> rules) {
        for (String rule : rules) {
            var format = switch (name(rule)) {
                case "email" -> "email";
                case "url", "active_url" -> "uri";
                case "uuid" -> "uuid";
                case "date" -> "date";
                case "ipv4" -> "ipv4";
                case "ipv6" -> "ipv6";
                case "file", "image" -> "binary";
                case "password" -> "password";
                default -> null;
            };
            if (format != null) return java.util.Optional.of(format);
        }
        return java.util.Optional.empty();
    }

    /**
     * 一条规则 → 一个约束。
     *
     * <h2>★ {@code max:200} 到底是 maxLength 还是 maximum</h2>
     * <p>取决于类型:字符串是长度、数字是大小、数组是元素个数。
     * 这不是实现细节上的讲究 —— 把 {@code max:200} 写成数字的
     * {@code maximum: 200} 会让前端的表单校验允许一个 200 字的标题,
     * 然后在提交时得到 422。
     *
     * <p>而「没有类型规则」时按字符串处理(见 {@link #typeOf}),
     * 与校验器在那种情况下的实际行为一致。
     */
    private static void applyConstraint(Map<String, Object> property, String type, String rule) {
        String name = name(rule);
        List<String> args = args(rule);

        switch (name) {
            case "max" -> numeric(args, 0).ifPresent(value -> property.put(
                    switch (type) {
                        case "integer", "number" -> "maximum";
                        case "array" -> "maxItems";
                        default -> "maxLength";
                    }, value));

            case "min" -> numeric(args, 0).ifPresent(value -> property.put(
                    switch (type) {
                        case "integer", "number" -> "minimum";
                        case "array" -> "minItems";
                        default -> "minLength";
                    }, value));

            case "size" -> numeric(args, 0).ifPresent(value -> {
                if (type.equals("integer") || type.equals("number")) {
                    property.put("minimum", value);
                    property.put("maximum", value);
                } else if (type.equals("array")) {
                    property.put("minItems", value);
                    property.put("maxItems", value);
                } else {
                    property.put("minLength", value);
                    property.put("maxLength", value);
                }
            });

            case "between" -> {
                numeric(args, 0).ifPresent(low -> applyConstraint(property, type, "min:" + low));
                numeric(args, 1).ifPresent(high -> applyConstraint(property, type, "max:" + high));
            }

            case "in" -> {
                if (!args.isEmpty()) property.put("enum", args);
            }

            case "regex" -> {
                if (!args.isEmpty()) property.put("pattern", stripDelimiters(args.get(0)));
            }

            case "digits" -> numeric(args, 0).ifPresent(digits ->
                    property.put("pattern", "^[0-9]{" + digits.intValue() + "}$"));

            case "digits_between" -> {
                if (args.size() >= 2) {
                    property.put("pattern", "^[0-9]{" + args.get(0) + "," + args.get(1) + "}$");
                }
            }

            case "starts_with" -> {
                if (!args.isEmpty()) {
                    property.put("pattern", "^(" + String.join("|", args) + ")");
                }
            }

            case "ends_with" -> {
                if (!args.isEmpty()) {
                    property.put("pattern", "(" + String.join("|", args) + ")$");
                }
            }

            // exists / unique / after / confirmed… —— 没有 schema 表达式,
            // 跳过(理由见类注释)
            default -> { }
        }
    }

    /**
     * {@code regex:/^[a-z]+$/i} 里那对斜杠是 PHP 的习惯写法。
     *
     * <p>OpenAPI 的 {@code pattern} 是裸正则(ECMA 262),带着定界符的话
     * 客户端生成器会把 {@code /} 当成模式的一部分 —— 于是那个校验永远不通过。
     */
    private static String stripDelimiters(String regex) {
        if (regex.length() >= 2 && regex.startsWith("/")) {
            int end = regex.lastIndexOf('/');
            if (end > 0) return regex.substring(1, end);
        }
        return regex;
    }

    private static List<String> split(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (String piece : ruleString.split("\\|")) {
            if (!piece.isBlank()) out.add(piece.trim());
        }
        return out;
    }

    private static String name(String rule) {
        int colon = rule.indexOf(':');
        return (colon < 0 ? rule : rule.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static List<String> args(String rule) {
        int colon = rule.indexOf(':');
        if (colon < 0 || colon == rule.length() - 1) return List.of();
        var out = new ArrayList<String>();
        for (String piece : rule.substring(colon + 1).split(",")) out.add(piece.trim());
        return out;
    }

    private static java.util.Optional<Number> numeric(List<String> args, int index) {
        if (index >= args.size()) return java.util.Optional.empty();
        try {
            String raw = args.get(index);
            // ★ 两个分支必须显式转成 (Number)。
            //
            // 不转的话,`cond ? Double.valueOf(..) : Long.valueOf(..)` 会触发
            // JLS 15.25 的**数值条件表达式**规则:Double 与 Long 都可拆箱成
            // 数值类型,于是 javac 做二进制数值提升 —— **两个分支都被提升成
            // double**。症状:`max:5` 在 schema 里变成 5.0(Double)。
            // 那不只是难看的输出:某些客户端生成器按整数与浮点分别生成类型。
            // (字节码里能看到 doubleValue + l2d,那不是编译缓存过期。)
            // Number 本身不可拆箱,转成它之后走的就是引用类型的三目,
            // 两个分支各保持各的装箱。
            return java.util.Optional.of(raw.contains(".")
                    ? (Number) Double.valueOf(raw) : (Number) Long.valueOf(raw));
        } catch (NumberFormatException e) {
            // max:foo 这种写法在校验器那边也会出问题,但生成文档不该因此失败
            return java.util.Optional.empty();
        }
    }
}
