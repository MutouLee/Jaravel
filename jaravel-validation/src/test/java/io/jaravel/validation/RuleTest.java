package io.jaravel.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 内置规则的行为 */
class RuleTest {

    private static ErrorBag check(Map<String, Object> data, Map<String, String> rules) {
        var validator = Validator.make(data, rules);
        validator.fails();
        return validator.errors();
    }

    private static boolean passes(Object value, String rule) {
        return Validator.make(map("f", value), Map.of("f", rule)).passes();
    }

    private static Map<String, Object> map(Object... pairs) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    // ================= 存在性 =================

    @Nested
    @DisplayName("存在性")
    class Presence {

        @Test
        @DisplayName("required:null / 空串 / 空数组都算空,但 0 和 false 不算")
        void requiredSemantics() {
            assertThat(passes(null, "required")).isFalse();
            assertThat(passes("", "required")).isFalse();
            assertThat(passes("   ", "required")).isFalse();
            assertThat(passes(List.of(), "required")).isFalse();

            // 这两条最容易搞错:填了 0 不等于没填
            assertThat(passes(0, "required")).isTrue();
            assertThat(passes(false, "required")).isTrue();
        }

        @Test
        @DisplayName("字段完全不存在时 required 仍然报错")
        void requiredOnMissingField() {
            var errors = check(Map.of(), Map.of("title", "required|max:200"));

            assertThat(errors.has("title")).isTrue();
            // 只报 required,不该把 max 也报一遍
            assertThat(errors.get("title")).hasSize(1);
        }

        @Test
        @DisplayName("nullable 让后续规则对 null 放行")
        void nullableSkipsFollowingRules() {
            var data = map("age", null);
            assertThat(Validator.make(data, Map.of("age", "nullable|integer|min:18")).passes())
                    .isTrue();
            // 不加 nullable 时 integer 会对 null 报错?不会 —— 缺值规则本就跳过。
            // nullable 真正的作用是在「存在但为 null」时放行。
            assertThat(Validator.make(map("age", "abc"), Map.of("age", "nullable|integer")).passes())
                    .isFalse();
        }

        @Test
        @DisplayName("sometimes:字段不存在就整体跳过")
        void sometimesSkipsAbsentField() {
            assertThat(Validator.make(Map.of(), Map.of("age", "sometimes|required|integer")).passes())
                    .isTrue();
            assertThat(Validator.make(map("age", ""), Map.of("age", "sometimes|required")).passes())
                    .isFalse();
        }

        @Test
        @DisplayName("filled:不存在可以,存在就不能为空")
        void filled() {
            assertThat(Validator.make(Map.of(), Map.of("x", "filled")).passes()).isTrue();
            assertThat(Validator.make(map("x", ""), Map.of("x", "filled")).passes()).isFalse();
        }

        @Test
        @DisplayName("present:必须存在,可以为空")
        void present() {
            assertThat(Validator.make(Map.of(), Map.of("x", "present")).passes()).isFalse();
            assertThat(Validator.make(map("x", ""), Map.of("x", "present")).passes()).isTrue();
        }

        @Test
        @DisplayName("prohibited:必须不存在或为空")
        void prohibited() {
            assertThat(Validator.make(Map.of(), Map.of("x", "prohibited")).passes()).isTrue();
            assertThat(Validator.make(map("x", "v"), Map.of("x", "prohibited")).passes()).isFalse();
        }
    }

    // ================= 条件必填 =================

    @Nested
    @DisplayName("条件必填")
    class Conditional {

        @Test
        @DisplayName("required_if")
        void requiredIf() {
            var rules = Map.of("reason", "required_if:action,reject");

            assertThat(Validator.make(map("action", "approve"), rules).passes()).isTrue();
            assertThat(Validator.make(map("action", "reject"), rules).passes()).isFalse();
            assertThat(Validator.make(map("action", "reject", "reason", "不合规"), rules).passes())
                    .isTrue();
        }

        @Test
        @DisplayName("required_unless")
        void requiredUnless() {
            var rules = Map.of("reason", "required_unless:action,approve");

            assertThat(Validator.make(map("action", "approve"), rules).passes()).isTrue();
            assertThat(Validator.make(map("action", "reject"), rules).passes()).isFalse();
        }

        @Test
        @DisplayName("required_with / required_with_all")
        void requiredWith() {
            assertThat(Validator.make(map("a", "1"), Map.of("c", "required_with:a,b")).passes())
                    .isFalse();
            assertThat(Validator.make(map("a", "1"), Map.of("c", "required_with_all:a,b")).passes())
                    .isTrue();
            assertThat(Validator.make(map("a", "1", "b", "2"),
                    Map.of("c", "required_with_all:a,b")).passes()).isFalse();
        }

        @Test
        @DisplayName("required_without / required_without_all")
        void requiredWithout() {
            assertThat(Validator.make(Map.of(), Map.of("c", "required_without:a")).passes())
                    .isFalse();
            assertThat(Validator.make(map("a", "1"), Map.of("c", "required_without:a")).passes())
                    .isTrue();
            assertThat(Validator.make(map("a", "1"),
                    Map.of("c", "required_without_all:a,b")).passes()).isTrue();
        }

        @Test
        @DisplayName("条件必填在数组里按同一行解析,而不是跨行")
        void conditionalWithinArrayRow() {
            var data = map("items", List.of(
                    map("type", "physical"),                      // 缺 weight → 应报错
                    map("type", "digital")));                     // 不需要 weight

            var errors = check(data, Map.of("items.*.weight", "required_if:items.*.type,physical"));

            // 只有第 0 行报错 —— 如果同层引用没做索引替换,两行都会报或都不报
            assertThat(errors.fields()).containsExactly("items.0.weight");
        }
    }

    // ================= 类型 =================

    @Nested
    @DisplayName("类型")
    class Types {

        @ParameterizedTest
        @CsvSource({"123,true", "'-5',true", "'+7',true", "12.5,false", "abc,false", "'',false"})
        @DisplayName("integer 接受数字字符串 —— 表单数据全是字符串")
        void integerAcceptsNumericStrings(String value, boolean expected) {
            assertThat(passes(value, "integer")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"1,true", "0,true", "true,true", "false,true", "yes,false", "2,false"})
        @DisplayName("boolean 接受 HTML 表单会产生的几种形态")
        void booleanForms(String value, boolean expected) {
            assertThat(passes(value, "boolean")).isEqualTo(expected);
        }

        @Test
        @DisplayName("array 认集合、Map 与原生数组")
        void arrayForms() {
            assertThat(passes(List.of(1, 2), "array")).isTrue();
            assertThat(passes(Map.of("a", 1), "array")).isTrue();
            assertThat(passes(new int[] {1, 2}, "array")).isTrue();
            assertThat(passes("not an array", "array")).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"a\":1}", "[1,2,3]", "{\"a\":{\"b\":[1]}}",
                                "{\"s\":\"}}}\"}"})
        @DisplayName("json 接受合法结构,含字符串里的括号")
        void jsonAccepts(String value) {
            assertThat(passes(value, "json")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"a\":1", "not json", "{]", "{\"a\":1}}"})
        @DisplayName("json 拒绝括号不配对的")
        void jsonRejects(String value) {
            assertThat(passes(value, "json")).isFalse();
        }

        @Test
        @DisplayName("decimal:2 检查小数位数")
        void decimalPlaces() {
            assertThat(passes("1.23", "decimal:2")).isTrue();
            assertThat(passes("1.2", "decimal:2")).isFalse();
            assertThat(passes("1.234", "decimal:2,4")).isTrue();
        }
    }

    // ================= 大小 =================

    @Nested
    @DisplayName("大小:语义随类型变化")
    class Sizes {

        @Test
        @DisplayName("声明为 string 时 max 按字符数")
        void maxOnString() {
            assertThat(Validator.make(map("x", "12345"), Map.of("x", "string|max:3")).passes())
                    .isFalse();
            assertThat(Validator.make(map("x", "ab"), Map.of("x", "string|max:3")).passes())
                    .isTrue();
        }

        @Test
        @DisplayName("声明为 integer 时 max 按数值 —— 这是最容易出错的一条")
        void maxOnNumericString() {
            // "5" 声明为 integer 后,max:3 应该判「5 > 3」而不是「长度 1 ≤ 3」
            assertThat(Validator.make(map("x", "5"), Map.of("x", "integer|max:3")).passes())
                    .isFalse();
            assertThat(Validator.make(map("x", "2"), Map.of("x", "integer|max:3")).passes())
                    .isTrue();
            // 没声明 integer 时按字符串长度算 —— "12345" 长度 5 > 3
            assertThat(Validator.make(map("x", "12345"), Map.of("x", "max:3")).passes())
                    .isFalse();
        }

        @Test
        @DisplayName("数组按元素个数")
        void maxOnArray() {
            assertThat(Validator.make(map("x", List.of(1, 2, 3, 4)),
                    Map.of("x", "array|max:3")).passes()).isFalse();
        }

        @Test
        @DisplayName("between 的上下界写反时立刻报错,而不是永不通过")
        void betweenRejectsInvertedBounds() {
            assertThatThrownBy(() -> Validator.make(map("x", 5), Map.of("x", "between:10,1")))
                    .hasMessageContaining("下界大于上界");
        }

        @Test
        @DisplayName("digits / digits_between")
        void digits() {
            assertThat(passes("1234", "digits:4")).isTrue();
            assertThat(passes("123", "digits:4")).isFalse();
            assertThat(passes("12345", "digits_between:3,6")).isTrue();
        }

        @Test
        @DisplayName("multiple_of:0 被拒")
        void multipleOfZero() {
            assertThatThrownBy(() -> Validator.make(map("x", 5), Map.of("x", "multiple_of:0")))
                    .hasMessageContaining("无意义");
        }

        @Test
        @DisplayName("gt/gte/lt/lte 引用其它字段")
        void fieldComparisons() {
            var rules = Map.of("end", "numeric|gt:start");
            assertThat(Validator.make(map("start", 1, "end", 2), rules).passes()).isTrue();
            assertThat(Validator.make(map("start", 5, "end", 2), rules).passes()).isFalse();
        }
    }

    // ================= 格式 =================

    @Nested
    @DisplayName("格式")
    class Formats {

        @ParameterizedTest
        @ValueSource(strings = {"a@b.co", "first.last@sub.example.com", "x+tag@example.org"})
        void emailAccepts(String value) {
            assertThat(passes(value, "email")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"a@b", "@b.co", "a@@b.co", "a b@c.co", "a@b..co", ".a@b.co"})
        void emailRejects(String value) {
            assertThat(passes(value, "email")).isFalse();
        }

        @ParameterizedTest
        @CsvSource({"192.168.1.1,true", "255.255.255.255,true", "256.1.1.1,false",
                    "1.1.1,false", "01.1.1.1,false"})
        @DisplayName("ipv4 拒绝前导零 —— 它是 SSRF 绕过的经典手法")
        void ipv4(String value, boolean expected) {
            assertThat(passes(value, "ipv4")).isEqualTo(expected);
        }

        @Test
        @DisplayName("ipv6")
        void ipv6() {
            assertThat(passes("::1", "ipv6")).isTrue();
            assertThat(passes("2001:db8::1", "ipv6")).isTrue();
            assertThat(passes("192.168.1.1", "ipv6")).isFalse();
        }

        @Test
        @DisplayName("url 只认 http(s)/ftp,且必须有主机")
        void url() {
            assertThat(passes("https://example.com/a?b=1", "url")).isTrue();
            assertThat(passes("javascript:alert(1)", "url")).isFalse();
            assertThat(passes("https://", "url")).isFalse();
        }

        @Test
        @DisplayName("uuid / ulid")
        void identifiers() {
            assertThat(passes("3f2504e0-4f89-41d3-9a0c-0305e82c3301", "uuid")).isTrue();
            assertThat(passes("not-a-uuid", "uuid")).isFalse();
            assertThat(passes("01ARZ3NDEKTSV4RRFFQ69G5FAV", "ulid")).isTrue();
        }

        @Test
        @DisplayName("alpha 认 Unicode 字母,中文也算")
        void alphaIsUnicodeAware() {
            assertThat(passes("中文", "alpha")).isTrue();
            assertThat(passes("abc", "alpha")).isTrue();
            assertThat(passes("ab1", "alpha")).isFalse();
        }

        @Test
        @DisplayName("regex 支持 /pattern/flags,且含 | 的正则不被规则分隔符切断")
        void regexWithPipe() {
            // 这是字符串规则写法的真实陷阱:| 既是规则分隔符又是正则的或
            assertThat(Validator.make(map("x", "a"), Map.of("x", "regex:/^(a|b)$/")).passes())
                    .isTrue();
            assertThat(Validator.make(map("x", "c"), Map.of("x", "regex:/^(a|b)$/")).passes())
                    .isFalse();
        }

        @Test
        @DisplayName("regex 大小写标记")
        void regexFlags() {
            assertThat(Validator.make(map("x", "ABC"), Map.of("x", "regex:/^abc$/i")).passes())
                    .isTrue();
        }

        @Test
        @DisplayName("regex 表达式写错时报错指出位置与 | 的陷阱")
        void regexSyntaxError() {
            assertThatThrownBy(() -> Validator.make(map("x", "a"), Map.of("x", "regex:/^[a-/")))
                    .hasMessageContaining("无法编译")
                    .hasMessageContaining("Builder");
        }

        @Test
        @DisplayName("starts_with / ends_with / doesnt_start_with")
        void affixes() {
            assertThat(passes("foobar", "starts_with:foo,baz")).isTrue();
            assertThat(passes("foobar", "ends_with:bar")).isTrue();
            assertThat(passes("foobar", "doesnt_start_with:baz")).isTrue();
            assertThat(passes("foobar", "doesnt_start_with:foo")).isFalse();
        }

        @Test
        @DisplayName("lowercase / uppercase / ascii / hex_color / timezone")
        void misc() {
            assertThat(passes("abc", "lowercase")).isTrue();
            assertThat(passes("Abc", "lowercase")).isFalse();
            assertThat(passes("ABC", "uppercase")).isTrue();
            assertThat(passes("abc", "ascii")).isTrue();
            assertThat(passes("中文", "ascii")).isFalse();
            assertThat(passes("#ff0000", "hex_color")).isTrue();
            assertThat(passes("Asia/Shanghai", "timezone")).isTrue();
            assertThat(passes("Mars/Olympus", "timezone")).isFalse();
        }
    }

    // ================= 日期 =================

    @Nested
    @DisplayName("日期")
    class Dates {

        @Test
        @DisplayName("date 接受 ISO 与常见变体")
        void dateForms() {
            assertThat(passes("2026-10-29", "date")).isTrue();
            assertThat(passes("2026-10-29T10:00:00Z", "date")).isTrue();
            assertThat(passes("not a date", "date")).isFalse();
        }

        @Test
        @DisplayName("date_format 用 java.time 模式,并在写成 PHP 模式时说清差异")
        void dateFormat() {
            assertThat(Validator.make(map("d", "2026-10-29"),
                    Map.of("d", "date_format:yyyy-MM-dd")).passes()).isTrue();
            assertThat(Validator.make(map("d", "29/10/2026"),
                    Map.of("d", "date_format:yyyy-MM-dd")).passes()).isFalse();

            // Y-m-d 在 java.time 里是**合法但含义完全不同**的格式串
            // (Y = 周基年,m = 分钟),静默接受比报错危险,所以必须拦住
            assertThatThrownBy(() -> Validator.make(map("d", "x"),
                    Map.of("d", "date_format:Y-m-d")))
                    .hasMessageContaining("周基年")
                    .hasMessageContaining("yyyy-MM-dd");

            assertThatThrownBy(() -> Validator.make(map("d", "x"),
                    Map.of("d", "date_format:H:i:s")))
                    .hasMessageContaining("PHP 的分钟标记");
        }

        @Test
        @DisplayName("after 支持相对表达式")
        void relativeDates() {
            assertThat(Validator.make(map("d", "2099-01-01"),
                    Map.of("d", "date|after:today")).passes()).isTrue();
            assertThat(Validator.make(map("d", "2000-01-01"),
                    Map.of("d", "date|after:today")).passes()).isFalse();
            assertThat(Validator.make(map("d", "2000-01-01"),
                    Map.of("d", "date|before:+30 days")).passes()).isTrue();
        }

        @Test
        @DisplayName("after 引用其它字段")
        void dateFieldReference() {
            var rules = Map.of("end", "date|after:start");
            assertThat(Validator.make(map("start", "2026-01-01", "end", "2026-02-01"), rules)
                    .passes()).isTrue();
            assertThat(Validator.make(map("start", "2026-03-01", "end", "2026-02-01"), rules)
                    .passes()).isFalse();
        }
    }

    // ================= 其它 =================

    @Nested
    @DisplayName("其它")
    class Others {

        @Test
        @DisplayName("confirmed 找 {field}_confirmation")
        void confirmed() {
            assertThat(Validator.make(map("password", "secret", "password_confirmation", "secret"),
                    Map.of("password", "confirmed")).passes()).isTrue();
            assertThat(Validator.make(map("password", "secret", "password_confirmation", "other"),
                    Map.of("password", "confirmed")).passes()).isFalse();
            assertThat(Validator.make(map("password", "secret"),
                    Map.of("password", "confirmed")).passes()).isFalse();
        }

        @Test
        @DisplayName("same / different")
        void sameDifferent() {
            assertThat(Validator.make(map("a", "1", "b", "1"), Map.of("a", "same:b")).passes())
                    .isTrue();
            assertThat(Validator.make(map("a", "1", "b", "2"), Map.of("a", "different:b")).passes())
                    .isTrue();
        }

        @Test
        @DisplayName("in / not_in")
        void inRule() {
            assertThat(passes("admin", "in:admin,editor")).isTrue();
            assertThat(passes("guest", "in:admin,editor")).isFalse();
            assertThat(passes("guest", "not_in:admin,editor")).isTrue();
        }

        @Test
        @DisplayName("in_array 引用另一个数组字段")
        void inArray() {
            var data = map("allowed", List.of("a", "b"), "choice", "a");
            assertThat(Validator.make(data, Map.of("choice", "in_array:allowed.*")).passes())
                    .isTrue();
            assertThat(Validator.make(map("allowed", List.of("a"), "choice", "z"),
                    Map.of("choice", "in_array:allowed.*")).passes()).isFalse();
        }

        @Test
        @DisplayName("distinct 查数组内重复")
        void distinct() {
            assertThat(passes(List.of(1, 2, 3), "distinct")).isTrue();
            assertThat(passes(List.of(1, 2, 2), "distinct")).isFalse();
        }

        @Test
        @DisplayName("accepted / declined")
        void acceptedDeclined() {
            assertThat(passes("yes", "accepted")).isTrue();
            assertThat(passes("on", "accepted")).isTrue();
            assertThat(passes("1", "accepted")).isTrue();
            assertThat(passes("0", "accepted")).isFalse();
            assertThat(passes("no", "declined")).isTrue();
        }

        @Test
        @DisplayName("enum 规则:Builder 写法")
        void enumRule() {
            assertThat(Validator.make(map("s", "ACTIVE"))
                    .field("s", Rule.enumOf(Status.class)).passes()).isTrue();
            assertThat(Validator.make(map("s", "NOPE"))
                    .field("s", Rule.enumOf(Status.class)).passes()).isFalse();
        }

        @Test
        @DisplayName("password 默认只要求长度 —— NIST 不推荐强制字符类")
        void passwordDefaults() {
            assertThat(Validator.make(map("p", "longenough"))
                    .field("p", Rule.password()).passes()).isTrue();
            assertThat(Validator.make(map("p", "short"))
                    .field("p", Rule.password()).passes()).isFalse();
        }

        @Test
        @DisplayName("password 可按需加强")
        void passwordConfigurable() {
            var rule = Rule.password().min(10).mixedCase().numbers().symbols()
                    .notIn(List.of("password123!"));

            assertThat(Validator.make(map("p", "Abcdef123!")).field("p", rule).passes()).isTrue();
            assertThat(Validator.make(map("p", "abcdef123!")).field("p", rule).passes()).isFalse();
            // 黑名单是大小写不敏感的 —— 「首字母大写」是最常见的绕过方式
            assertThat(Validator.make(map("p", "Password123!")).field("p", rule).passes()).isFalse();
            assertThat(Validator.make(map("p", "Zx9!qwerty")).field("p", rule).passes()).isTrue();
        }
    }

    enum Status { ACTIVE, INACTIVE }

    // ================= 自定义规则 =================

    @Test
    @DisplayName("自定义规则是第一等公民,消息可直接给")
    void customRule() {
        var even = Rule.of("even", v -> v instanceof Number n && n.intValue() % 2 == 0)
                .withMessage(":attribute 必须是偶数");

        var validator = Validator.make(map("n", 3)).field("n", even);
        assertThat(validator.fails()).isTrue();
        assertThat(validator.errors().first("n")).isEqualTo("n 必须是偶数");
    }

    @Test
    @DisplayName("自定义规则可以看到全部数据")
    void customContextRule() {
        var rule = Rule.ofContext("sum_matches",
                c -> {
                    int a = Integer.parseInt(String.valueOf(c.other("a")));
                    int b = Integer.parseInt(String.valueOf(c.other("b")));
                    return Integer.parseInt(c.stringValue()) == a + b;
                }).withMessage(":attribute 应等于 a + b");

        assertThat(Validator.make(map("a", 1, "b", 2, "total", 3))
                .field("total", rule).passes()).isTrue();
        assertThat(Validator.make(map("a", 1, "b", 2, "total", 9))
                .field("total", rule).passes()).isFalse();
    }

    @Test
    @DisplayName("规则抛异常时报错指出是哪条规则、哪个字段")
    void ruleThrowingIsDiagnosable() {
        var bad = Rule.of("boom", v -> { throw new IllegalStateException("内部错误"); });

        assertThatThrownBy(() -> Validator.make(map("x", 1)).field("x", bad).passes())
                .hasMessageContaining("规则 boom")
                .hasMessageContaining("字段 `x`")
                .hasMessageContaining("返回 false");
    }

    // ================= 解析错误 =================

    @Test
    @DisplayName("未知规则给出「是否想写」与自定义规则的写法")
    void unknownRuleIsActionable() {
        assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("x", "requried")))
                .hasMessageContaining("未知的校验规则 `requried`")
                .hasMessageContaining("是否想写 required")
                .hasMessageContaining("字段 `x`");
    }

    @Test
    @DisplayName("参数缺失或类型不对时给出该规则的正确写法")
    void badRuleArgsAreActionable() {
        assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("x", "max")))
                .hasMessageContaining("需要至少 1 个参数")
                .hasMessageContaining("max:200");

        assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("x", "max:abc")))
                .hasMessageContaining("应是数字")
                .hasMessageContaining("max:200");

        assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("x", "unique:users")))
                .hasMessageContaining("表名、列名")
                .hasMessageContaining("unique:users,email");
    }
}
