package io.jaravel.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验消息的查找与本地化。
 *
 * <p>查找顺序(先命中者胜):
 * <ol>
 *   <li>调用方传入的 {@code messages()} 里的 {@code field.rule};</li>
 *   <li>同上的 {@code rule};</li>
 *   <li>语言包的 {@code custom.field.rule};</li>
 *   <li>语言包的 {@code rule.<类型>}(如 {@code max.string});</li>
 *   <li>语言包的 {@code rule};</li>
 *   <li>兜底模板。</li>
 * </ol>
 *
 * <p>第 4 层是必须的:{@code max} 对字符串、数值、数组、文件的说法完全不同
 * (「不能超过 200 个字符」vs「不能大于 200」),共用一句会让提示文不对题。
 */
public final class Messages {

    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    private final String locale;
    private final Map<String, String> overrides;
    private final Map<String, String> attributeNames;

    public Messages(String locale, Map<String, String> overrides,
                    Map<String, String> attributeNames) {
        this.locale = locale == null ? "zh_CN" : locale;
        this.overrides = overrides == null ? Map.of() : overrides;
        this.attributeNames = attributeNames == null ? Map.of() : attributeNames;
    }

    public static Messages defaults() {
        return new Messages("zh_CN", Map.of(), Map.of());
    }

    /** 生成一条最终消息 */
    public String render(String attribute, Rule rule, Object value, String valueType) {
        String template = lookup(attribute, rule, valueType);
        return substitute(template, attribute, rule, value);
    }

    private String lookup(String attribute, Rule rule, String valueType) {
        String ruleName = rule.name();

        // 自定义规则自带消息,优先级最高
        if (rule instanceof Rules.CustomRule custom && custom.message() != null) {
            return custom.message();
        }

        // 1 & 2:调用方传入的覆盖
        String byField = overrides.get(attribute + "." + ruleName);
        if (byField != null) return byField;
        // 数组字段:items.2.qty 也要能被 items.*.qty 的覆盖命中
        String wildcarded = toWildcard(attribute);
        if (!wildcarded.equals(attribute)) {
            String byWildcard = overrides.get(wildcarded + "." + ruleName);
            if (byWildcard != null) return byWildcard;
        }
        String byRule = overrides.get(ruleName);
        if (byRule != null) return byRule;

        Map<String, String> bundle = bundle(locale);

        // 3:语言包的字段级覆盖
        String custom = bundle.get("custom." + attribute + "." + ruleName);
        if (custom != null) return custom;

        // 4:按值类型细分
        if (valueType != null) {
            String typed = bundle.get(ruleName + "." + valueType);
            if (typed != null) return typed;
        }

        // 5 & 6
        String plain = bundle.get(ruleName);
        return plain != null ? plain : ":attribute 不符合 " + ruleName + " 规则。";
    }

    /** {@code items.2.qty} → {@code items.*.qty} */
    static String toWildcard(String attribute) {
        var segments = attribute.split("\\.", -1);
        var out = new ArrayList<String>(segments.length);
        for (String s : segments) {
            out.add(s.chars().allMatch(Character::isDigit) && !s.isEmpty() ? "*" : s);
        }
        return String.join(".", out);
    }

    private String substitute(String template, String attribute, Rule rule, Object value) {
        String result = template
                .replace(":attribute", attributeName(attribute))
                .replace(":input", value == null ? "" : String.valueOf(value));

        for (var entry : rule.replacements().entrySet()) {
            result = result.replace(":" + entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 字段的人类可读名。没配就用叶子段并把下划线换成空格。 */
    public String attributeName(String attribute) {
        String explicit = attributeNames.get(attribute);
        if (explicit != null) return explicit;

        String wildcarded = toWildcard(attribute);
        explicit = attributeNames.get(wildcarded);
        if (explicit != null) return explicit;

        Map<String, String> bundle = bundle(locale);
        explicit = bundle.get("attributes." + attribute);
        if (explicit != null) return explicit;
        explicit = bundle.get("attributes." + wildcarded);
        if (explicit != null) return explicit;

        return Data.leaf(attribute).replace('_', ' ');
    }

    public String headline() {
        return bundle(locale).getOrDefault("__headline", "给定数据无效。");
    }

    // ================= 语言包 =================

    /**
     * 加载 {@code resources/lang/<locale>/validation.properties}。
     *
     * <p>用 properties 而不是 YAML:validation 模块不该为了读一个配置文件
     * 引入 snakeyaml。点号层级({@code max.string})在 properties 里本来就自然。
     * 内置包以 {@code validation-<locale>.properties} 放在 jar 里,
     * 用户的同名文件覆盖它。
     */
    static Map<String, String> bundle(String locale) {
        return BUNDLES.computeIfAbsent(locale, Messages::loadBundle);
    }

    private static Map<String, String> loadBundle(String locale) {
        var merged = new LinkedHashMap<String, String>();
        // 先装内置的,再让用户的覆盖
        load("io/jaravel/validation/validation-" + locale + ".properties", merged);
        load("lang/" + locale + "/validation.properties", merged);

        if (merged.isEmpty() && !locale.equals("zh_CN")) {
            // 未知语言回落到中文,而不是让所有消息变成兜底模板
            return bundle("zh_CN");
        }
        return Map.copyOf(merged);
    }

    private static void load(String resource, Map<String, String> into) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = Messages.class.getClassLoader();

        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return;
            var properties = new java.util.Properties();
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            properties.forEach((k, v) -> into.put(String.valueOf(k), String.valueOf(v)));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取语言包 " + resource + " 失败:" + e.getMessage(), e);
        }
    }

    /** 测试用:清掉缓存,让改过的语言包生效 */
    public static void clearCache() {
        BUNDLES.clear();
    }

    public List<String> availableLocales() {
        return List.of("zh_CN", "en");
    }
}
