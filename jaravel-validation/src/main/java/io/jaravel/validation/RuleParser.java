package io.jaravel.validation;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.support.Arr;

/**
 * {@code "required|max:200|in:a,b"} → {@code List<Rule>}。
 *
 * <p>字符串写法与 Builder 写法在这里汇合:解析结果就是 Builder 会产生的
 * 同一批 {@link Rule} 对象,因此两种写法的行为天然一致。
 */
final class RuleParser {

    private RuleParser() {}

    static List<Rule> parse(String expression) {
        var rules = new ArrayList<Rule>();
        for (String token : splitRules(expression)) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            rules.add(single(trimmed));
        }
        return rules;
    }

    /**
     * 按 {@code |} 切分,但**不切 regex 里的 {@code |}**。
     *
     * <p>{@code "regex:/^(a|b)$/"} 如果按 | 硬切就全毁了。Laravel 的做法是
     * 要求这种情况改用数组写法;这里多做一步:只要 token 以 {@code regex:}
     * 或 {@code not_regex:} 开头,就把它后面的内容整体吃掉。
     */
    private static List<String> splitRules(String expression) {
        var out = new ArrayList<String>();
        int i = 0;
        var current = new StringBuilder();

        while (i < expression.length()) {
            char ch = expression.charAt(i);
            if (ch == '|') {
                String token = current.toString();
                if (isUnterminatedRegex(token)) {
                    current.append(ch);        // 正则内部的 |,留着
                } else {
                    out.add(token);
                    current.setLength(0);
                }
                i++;
                continue;
            }
            current.append(ch);
            i++;
        }
        out.add(current.toString());
        return out;
    }

    private static boolean isUnterminatedRegex(String token) {
        String t = token.trim();
        if (!t.startsWith("regex:") && !t.startsWith("not_regex:")) return false;
        String body = t.substring(t.indexOf(':') + 1);
        // /.../ 形式:斜杠少于两个说明还没闭合
        if (body.startsWith("/")) {
            return body.lastIndexOf('/') == 0;
        }
        // 裸正则:无法判断闭合,一律认为未结束,把整段留给它
        return true;
    }

    private static Rule single(String token) {
        int colon = token.indexOf(':');
        String name = (colon < 0 ? token : token.substring(0, colon)).trim();
        String rawArgs = colon < 0 ? "" : token.substring(colon + 1);
        List<String> args = rawArgs.isEmpty() ? List.of() : splitArgs(name, rawArgs);

        Rule rule = build(name, args, rawArgs);
        if (rule != null) return rule;

        String hint = Arr.closest(name, KNOWN_RULES)
                .map(r -> "\n  是否想写 " + r + " ?").orElse("");
        throw new IllegalArgumentException(
                "未知的校验规则 `" + name + "`。" + hint
              + "\n  内置规则见 docs/07 §2;自定义规则用 Builder 写法:"
              + "\n    .field(\"x\", Rule.of(\"" + name + "\", v -> ...).withMessage(\"...\"))");
    }

    /** regex 的参数不能按逗号切 */
    private static List<String> splitArgs(String ruleName, String rawArgs) {
        if (ruleName.equals("regex") || ruleName.equals("not_regex")
                || ruleName.equals("date_format")) {
            return List.of(rawArgs);
        }
        return List.of(rawArgs.split(",", -1));
    }

    private static Rule build(String name, List<String> args, String rawArgs) {
        return switch (name) {
            // --- 存在性 ---
            case "required"     -> Rules.required();
            case "nullable"     -> Rules.nullable();
            case "sometimes"    -> Rules.sometimes();
            case "filled"       -> Rules.filled();
            case "present"      -> Rules.present();
            case "prohibited"   -> Rules.prohibited();
            case "prohibited_if" -> Rules.prohibitedIf(arg(name, args, 0), arg(name, args, 1));
            case "missing_with" -> Rules.missingWith(args);

            // --- 条件必填 ---
            case "required_if"           -> Rules.requiredIf(arg(name, args, 0), arg(name, args, 1), true);
            case "required_unless"       -> Rules.requiredIf(arg(name, args, 0), arg(name, args, 1), false);
            case "required_with"         -> Rules.requiredWith(args, true, false);
            case "required_with_all"     -> Rules.requiredWith(args, true, true);
            case "required_without"      -> Rules.requiredWith(args, false, false);
            case "required_without_all"  -> Rules.requiredWith(args, false, true);
            case "required_array_keys"   -> Rules.requiredArrayKeys(args);

            // --- 类型 ---
            case "string"   -> Rule.string();
            case "integer", "int" -> Rules.integerRule();
            case "numeric"  -> Rules.numericRule();
            case "boolean"  -> Rules.booleanRule();
            case "array"    -> Rules.arrayRule();
            case "json"     -> Rules.jsonRule();
            case "decimal"  -> args.size() >= 2
                    ? Rules.decimal(intArg(name, args, 0), intArg(name, args, 1))
                    : Rules.decimal(intArg(name, args, 0), intArg(name, args, 0));

            // --- 大小 ---
            case "min"      -> Rule.min(numberArg(name, args, 0));
            case "max"      -> Rule.max(numberArg(name, args, 0));
            case "size"     -> Rule.size(numberArg(name, args, 0));
            case "between"  -> Rule.between(numberArg(name, args, 0), numberArg(name, args, 1));
            case "digits"   -> Rules.digits(intArg(name, args, 0));
            case "digits_between" -> Rules.digitsBetween(intArg(name, args, 0), intArg(name, args, 1));
            case "multiple_of"    -> Rules.multipleOf(numberArg(name, args, 0));
            case "gt", "gte", "lt", "lte" -> Rules.fieldComparison(name, arg(name, args, 0));

            // --- 格式 ---
            case "email"        -> Rules.email(args);
            case "url"          -> Rules.url();
            case "active_url"   -> Rules.activeUrl();
            case "ip"           -> Rules.ip("ip");
            case "ipv4"         -> Rules.ip("ipv4");
            case "ipv6"         -> Rules.ip("ipv6");
            case "mac_address"  -> Rule.macAddress();
            case "uuid"         -> Rules.uuid();
            case "ulid"         -> Rules.ulid();
            case "alpha"        -> Rule.alpha();
            case "alpha_num"    -> Rule.alphaNum();
            case "alpha_dash"   -> Rule.alphaDash();
            case "ascii"        -> Rule.ascii();
            case "hex_color"    -> Rule.hexColor();
            case "lowercase"    -> Rule.lowercase();
            case "uppercase"    -> Rule.uppercase();
            case "timezone"     -> Rules.timezone();
            case "regex"        -> Rules.regex(rawArgs, false);
            case "not_regex"    -> Rules.regex(rawArgs, true);
            case "starts_with"  -> Rules.affix("starts_with", args, true);
            case "ends_with"    -> Rules.affix("ends_with", args, false);
            case "doesnt_start_with" -> negate(Rules.affix("starts_with", args, true),
                                               "doesnt_start_with", args);
            case "doesnt_end_with"   -> negate(Rules.affix("ends_with", args, false),
                                               "doesnt_end_with", args);
            case "contains"     -> Rules.contains(args);

            // --- 日期 ---
            case "date"             -> Rules.date();
            case "date_format"      -> Rules.dateFormat(rawArgs);
            case "after"            -> Rules.dateComparison("after", arg(name, args, 0));
            case "after_or_equal"   -> Rules.dateComparison("after_or_equal", arg(name, args, 0));
            case "before"           -> Rules.dateComparison("before", arg(name, args, 0));
            case "before_or_equal"  -> Rules.dateComparison("before_or_equal", arg(name, args, 0));
            case "date_equals"      -> Rules.dateComparison("date_equals", arg(name, args, 0));

            // --- 其它 ---
            case "confirmed"    -> Rules.confirmed();
            case "same"         -> Rules.comparison("same", arg(name, args, 0), true);
            case "different"    -> Rules.comparison("different", arg(name, args, 0), false);
            case "in"           -> Rules.in(args, false);
            case "not_in"       -> Rules.in(args, true);
            case "in_array"     -> Rules.inArray(arg(name, args, 0));
            case "distinct"     -> Rules.distinct();
            case "accepted"     -> Rules.accepted();
            case "accepted_if"  -> Rules.acceptedIf(arg(name, args, 0), arg(name, args, 1));
            case "declined"     -> Rules.declined();
            case "password"     -> Rule.password().min(args.isEmpty() ? 8 : intArg(name, args, 0));

            // --- 数据库 ---
            case "unique" -> DatabaseRule.parseUnique(args);
            case "exists" -> DatabaseRule.parseExists(args);

            default -> null;
        };
    }

    private static Rule negate(Rule inner, String name, List<String> args) {
        return new Rule() {
            @Override public String name() { return name; }
            @Override public boolean passes(Context c) {
                return c.stringValue() != null && !inner.passes(c);
            }
            @Override public java.util.Map<String, String> replacements() {
                return java.util.Map.of("values", String.join(", ", args));
            }
        };
    }

    // ================= 参数读取 =================

    private static String arg(String rule, List<String> args, int index) {
        if (index < args.size()) return args.get(index).trim();
        throw new IllegalArgumentException(
                "规则 " + rule + " 需要至少 " + (index + 1) + " 个参数,实际给了 " + args.size()
              + "。\n  写法:" + usage(rule));
    }

    private static int intArg(String rule, List<String> args, int index) {
        String raw = arg(rule, args, index);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "规则 " + rule + " 的第 " + (index + 1) + " 个参数应是整数,实际是 `" + raw + "`。"
                  + "\n  写法:" + usage(rule), e);
        }
    }

    private static Number numberArg(String rule, List<String> args, int index) {
        String raw = arg(rule, args, index);
        try {
            return new java.math.BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "规则 " + rule + " 的第 " + (index + 1) + " 个参数应是数字,实际是 `" + raw + "`。"
                  + "\n  写法:" + usage(rule), e);
        }
    }

    private static String usage(String rule) {
        return switch (rule) {
            case "max", "min", "size"      -> rule + ":200";
            case "between", "digits_between" -> rule + ":1,100";
            case "required_if", "required_unless" -> rule + ":other_field,value";
            case "in", "not_in"            -> rule + ":a,b,c";
            case "unique"                  -> "unique:users,email 或 unique:users,email,<要排除的 id>";
            case "exists"                  -> "exists:tags,id";
            case "date_format"             -> "date_format:yyyy-MM-dd(java.time 模式,不是 PHP 的 Y-m-d)";
            case "regex"                   -> "regex:/^[a-z]+$/";
            default                        -> rule + ":<参数>";
        };
    }

    /** 给「是否想写」用的候选集 */
    private static final List<String> KNOWN_RULES = List.of(
            "required", "nullable", "sometimes", "filled", "present", "prohibited",
            "prohibited_if", "missing_with",
            "required_if", "required_unless", "required_with", "required_with_all",
            "required_without", "required_without_all", "required_array_keys",
            "string", "integer", "numeric", "boolean", "array", "json", "decimal",
            "min", "max", "size", "between", "digits", "digits_between", "multiple_of",
            "gt", "gte", "lt", "lte",
            "email", "url", "active_url", "ip", "ipv4", "ipv6", "mac_address",
            "uuid", "ulid", "alpha", "alpha_num", "alpha_dash", "ascii", "hex_color",
            "lowercase", "uppercase", "timezone", "regex", "not_regex",
            "starts_with", "ends_with", "doesnt_start_with", "doesnt_end_with", "contains",
            "date", "date_format", "after", "after_or_equal", "before", "before_or_equal",
            "date_equals",
            "confirmed", "same", "different", "in", "not_in", "in_array", "distinct",
            "accepted", "accepted_if", "declined", "password", "unique", "exists");

    static List<String> knownRules() {
        return KNOWN_RULES;
    }
}
