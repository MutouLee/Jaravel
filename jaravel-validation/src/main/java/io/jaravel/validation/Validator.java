package io.jaravel.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 校验器。
 *
 * <pre>{@code
 * Validated v = Validator.make(request.all(), Map.of(
 *         "title", "required|string|max:200",
 *         "tags",  "array|max:5",
 *         "tags.*", "exists:tags,id"))
 *     .validate();
 *
 * String title = v.string("title");
 * }</pre>
 *
 * <p>字符串与 Builder 两种写法可以混用,因为它们落到同一批 {@link Rule} 上。
 */
public final class Validator {

    private final Map<String, Object> data;
    private final Map<String, List<Rule>> rules = new LinkedHashMap<>();
    private final Map<String, String> customMessages = new LinkedHashMap<>();
    private final Map<String, String> attributeNames = new LinkedHashMap<>();
    private final List<Consumer<Validator>> afterHooks = new ArrayList<>();

    private final ErrorBag errors = new ErrorBag();
    private final Set<String> validatedKeys = new LinkedHashSet<>();
    private final Set<String> excludedKeys = new LinkedHashSet<>();

    private String locale = "zh_CN";
    private boolean stopOnFirstFailure;
    private boolean ran;

    private Validator(Map<String, Object> data) {
        // 复制一份:prepareForValidation 会改数据,不该影响调用方的 Map
        this.data = new LinkedHashMap<>(data == null ? Map.of() : data);
    }

    // ================= 构造 =================

    public static Validator make(Map<String, Object> data) {
        return new Validator(data);
    }

    public static Validator make(Map<String, Object> data, Map<String, String> stringRules) {
        var validator = new Validator(data);
        stringRules.forEach(validator::field);
        return validator;
    }

    /** 字符串规则 */
    public Validator field(String attribute, String expression) {
        try {
            rules.computeIfAbsent(attribute, a -> new ArrayList<>())
                 .addAll(RuleParser.parse(expression));
        } catch (IllegalArgumentException e) {
            // 把出错的字段补进消息 —— 只说「未知规则 foo」时用户还得自己找是哪一行
            throw new IllegalArgumentException(
                    "字段 `" + attribute + "` 的规则有问题:" + e.getMessage(), e);
        }
        return this;
    }

    /** Builder 规则 */
    public Validator field(String attribute, Rule... ruleList) {
        rules.computeIfAbsent(attribute, a -> new ArrayList<>()).addAll(List.of(ruleList));
        return this;
    }

    public Validator messages(Map<String, String> overrides) {
        customMessages.putAll(overrides);
        return this;
    }

    public Validator attributes(Map<String, String> names) {
        attributeNames.putAll(names);
        return this;
    }

    public Validator locale(String locale) {
        this.locale = locale;
        return this;
    }

    /** 第一条失败就停 —— 适合 API 的快速失败,不适合表单 */
    public Validator stopOnFirstFailure() {
        this.stopOnFirstFailure = true;
        return this;
    }

    /**
     * 规则跑完后执行的跨字段校验。
     *
     * <p>有它的理由:很多业务校验是「字段 A 和 B 的组合」而不是单字段,
     * 硬塞进规则体系要发明一套引用语法,而一个 lambda 就够了。
     */
    public Validator after(Consumer<Validator> hook) {
        afterHooks.add(hook);
        return this;
    }

    public ErrorBag errors() {
        return errors;
    }

    public Map<String, Object> data() {
        return data;
    }

    /** 供 prepareForValidation 改数据 */
    public Validator merge(String attribute, Object value) {
        Data.set(data, attribute, value);
        return this;
    }

    // ================= 执行 =================

    /** 校验失败抛 {@link ValidationException} */
    public Validated validate() {
        if (fails()) {
            throw new ValidationException(errors, messages().headline());
        }
        return validated();
    }

    public boolean fails() {
        run();
        return errors.isNotEmpty();
    }

    public boolean passes() {
        return !fails();
    }

    private void run() {
        if (ran) return;
        ran = true;

        for (var entry : rules.entrySet()) {
            String pattern = entry.getKey();
            for (String attribute : Data.expand(data, pattern)) {
                validateAttribute(attribute, entry.getValue());
                if (stopOnFirstFailure && errors.isNotEmpty()) return;
            }
        }
        for (var hook : afterHooks) {
            hook.accept(this);
        }
    }

    private void validateAttribute(String attribute, List<Rule> ruleList) {
        boolean present = Data.has(data, attribute);
        Object value = Data.get(data, attribute);

        boolean nullable = ruleList.stream().anyMatch(r -> r.name().equals("nullable"));
        boolean sometimes = ruleList.stream().anyMatch(r -> r.name().equals("sometimes"));

        // sometimes:字段不存在就整体跳过
        if (sometimes && !present) return;

        // nullable:值为 null 时跳过**除隐式规则外**的全部规则。
        // 不能连隐式规则一起跳 —— "nullable|required" 虽然矛盾,
        // 但用户真写了的话应该让 required 发声,而不是静默通过。
        boolean skipNonImplicit = nullable && value == null;

        for (Rule rule : ruleList) {
            if (rule.name().equals("nullable") || rule.name().equals("sometimes")) continue;

            // 字段缺失或为空时,只跑隐式规则
            if (!rule.implicit() && (!present || value == null || skipNonImplicit)) continue;

            boolean ok;
            try {
                ok = rule.passes(new Rule.Context(attribute, value, present, data, this));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "规则 " + rule.name() + " 在校验字段 `" + attribute + "` 时抛出异常:"
                      + e.getMessage()
                      + "\n  规则实现不应抛异常 —— 校验不通过请返回 false。"
                      + "\n  若这是内置规则,请提 issue。", e);
            }

            if (!ok) {
                errors.add(attribute, messages().render(attribute, rule, value, valueTypeOf(value, attribute)));
                if (rule.stopsOnFailure()) return;
                if (stopOnFirstFailure) return;
            }
        }
        validatedKeys.add(attribute);
    }

    /**
     * 选哪个消息变体({@code max.string} / {@code max.numeric} / ...)。
     *
     * <p>以**声明的规则**为准而不是值的运行时类型:表单里的 {@code "5"} 是字符串,
     * 但如果声明了 {@code integer|max:10},用户期望看到的是「不能大于 10」
     * 而不是「不能超过 10 个字符」。
     */
    private String valueTypeOf(Object value, String attribute) {
        if (isDeclaredNumeric(attribute)) return "numeric";
        if (isDeclaredArray(attribute)) return "array";
        if (value instanceof Number) return "numeric";
        if (value instanceof java.util.Collection || value instanceof Map
                || (value != null && value.getClass().isArray())) return "array";
        if (value instanceof CharSequence) return "string";
        return null;
    }

    /** {@link Rules} 的 size 判断要用它,所以是包可见而非私有 */
    boolean isDeclaredNumeric(String attribute) {
        return hasRuleNamed(attribute, "numeric", "integer", "int", "decimal");
    }

    private boolean isDeclaredArray(String attribute) {
        return hasRuleNamed(attribute, "array");
    }

    private boolean hasRuleNamed(String attribute, String... names) {
        var wanted = Set.of(names);
        var list = rules.get(attribute);
        if (list == null) {
            // 数组元素:items.2.qty 的规则声明在 items.*.qty 上
            list = rules.get(Messages.toWildcard(attribute));
        }
        if (list == null) return false;
        return list.stream().anyMatch(r -> wanted.contains(r.name()));
    }

    private Messages messages() {
        return new Messages(locale, customMessages, attributeNames);
    }

    // ================= 结果 =================

    /**
     * 通过校验的数据。
     *
     * <p>**只含声明了规则的字段** —— 这是安全边界:直接把 {@code request.all()}
     * 传给 {@code Model.create()} 等于开放批量赋值,而 {@code validated()}
     * 天然只放行你声明过的键。
     */
    public Validated validated() {
        run();
        var out = new LinkedHashMap<String, Object>();

        for (String pattern : rules.keySet()) {
            for (String attribute : Data.expand(data, pattern)) {
                if (excludedKeys.contains(attribute)) continue;
                if (!Data.has(data, attribute)) continue;
                Data.set(out, attribute, Data.get(data, attribute));
            }
        }
        return new Validated(out);
    }

    /** 校验通过后从 validated 里剔除(对应 {@code exclude_if}) */
    public Validator exclude(String attribute) {
        excludedKeys.add(attribute);
        return this;
    }

    /** 已声明规则的字段名,供诊断使用 */
    public List<String> declaredFields() {
        return List.copyOf(rules.keySet());
    }
}
