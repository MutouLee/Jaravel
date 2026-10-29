package io.jaravel.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 校验器的编排行为:嵌套、通配符、消息、validated() */
class ValidatorTest {

    private static Map<String, Object> map(Object... pairs) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    // ================= 嵌套与通配符 =================

    @Nested
    @DisplayName("嵌套与通配符")
    class Nesting {

        @Test
        @DisplayName("点路径读嵌套字段")
        void dottedPaths() {
            var data = map("user", map("profile", map("city", "")));

            var validator = Validator.make(data, Map.of("user.profile.city", "required"));
            assertThat(validator.fails()).isTrue();
            assertThat(validator.errors().has("user.profile.city")).isTrue();
        }

        @Test
        @DisplayName("通配符展开成带真实索引的错误键 —— 前端才能标到具体那一行")
        void wildcardErrorKeysCarryIndex() {
            var data = map("items", List.of(
                    map("sku", "A1", "qty", 2),
                    map("sku", "A2", "qty", 0),
                    map("sku", "", "qty", 5)));

            var errors = errorsOf(data, Map.of(
                    "items.*.sku", "required|string",
                    "items.*.qty", "required|integer|min:1"));

            assertThat(errors.fields()).containsExactlyInAnyOrder("items.1.qty", "items.2.sku");
            // 不是 items.*.qty —— 那样前端无从知道是哪一行
            assertThat(errors.fields()).noneMatch(f -> f.contains("*"));
        }

        @Test
        @DisplayName("多层通配符")
        void nestedWildcards() {
            var data = map("items", List.of(
                    map("options", List.of(map("id", 1), map("id", "x")))));

            var errors = errorsOf(data, Map.of("items.*.options.*.id", "integer"));
            assertThat(errors.fields()).containsExactly("items.0.options.1.id");
        }

        @Test
        @DisplayName("数组本身的规则与元素规则可以共存")
        void arrayAndElementRules() {
            var data = map("tags", List.of(1, 2, 3, 4, 5, 6));

            var errors = errorsOf(data, Map.of(
                    "tags", "array|max:5",
                    "tags.*", "integer"));

            assertThat(errors.fields()).containsExactly("tags");
            assertThat(errors.first("tags")).contains("最多只能有 5 项");
        }

        @Test
        @DisplayName("中间层缺失时不会炸,也不会产生伪错误键")
        void missingIntermediateLevel() {
            var validator = Validator.make(Map.of(), Map.of("items.*.qty", "required|integer"));
            assertThat(validator.passes()).isTrue();
        }

        @Test
        @DisplayName("空数组通过元素规则")
        void emptyArrayPassesElementRules() {
            var validator = Validator.make(map("tags", List.of()),
                                           Map.of("tags.*", "required|integer"));
            assertThat(validator.passes()).isTrue();
        }
    }

    // ================= 消息 =================

    @Nested
    @DisplayName("消息与本地化")
    class MessagesTest {

        @Test
        @DisplayName("max 按值类型选不同措辞")
        void maxMessageVariesByType() {
            assertThat(errorsOf(map("x", "123456"), Map.of("x", "string|max:3")).first("x"))
                    .contains("不能超过 3 个字符");
            assertThat(errorsOf(map("x", "9"), Map.of("x", "integer|max:3")).first("x"))
                    .contains("不能大于 3");
            assertThat(errorsOf(map("x", List.of(1, 2, 3, 4)), Map.of("x", "array|max:3")).first("x"))
                    .contains("最多只能有 3 项");
        }

        @Test
        @DisplayName("字段级消息覆盖")
        void fieldLevelOverride() {
            var validator = Validator.make(Map.of(), Map.of("title", "required"))
                    .messages(Map.of("title.required", "标题不能为空"));

            validator.fails();
            assertThat(validator.errors().first("title")).isEqualTo("标题不能为空");
        }

        @Test
        @DisplayName("规则级消息覆盖")
        void ruleLevelOverride() {
            var validator = Validator.make(Map.of(), Map.of("a", "required", "b", "required"))
                    .messages(Map.of("required", ":attribute 是必填项"));

            validator.fails();
            assertThat(validator.errors().first("a")).isEqualTo("a 是必填项");
            assertThat(validator.errors().first("b")).isEqualTo("b 是必填项");
        }

        @Test
        @DisplayName("attributes() 替换 :attribute")
        void attributeNames() {
            var validator = Validator.make(Map.of(), Map.of("email", "required"))
                    .attributes(Map.of("email", "邮箱"));

            validator.fails();
            assertThat(validator.errors().first("email")).isEqualTo("邮箱 不能为空。");
        }

        @Test
        @DisplayName("数组字段的消息覆盖可以用通配符键")
        void wildcardMessageKey() {
            var data = map("items", List.of(map("qty", 0)));
            var validator = Validator.make(data, Map.of("items.*.qty", "min:1"))
                    .messages(Map.of("items.*.qty.min", "数量至少 1"));

            validator.fails();
            assertThat(validator.errors().first("items.0.qty")).isEqualTo("数量至少 1");
        }

        @Test
        @DisplayName("切换语言")
        void englishLocale() {
            var validator = Validator.make(Map.of(), Map.of("title", "required")).locale("en");

            validator.fails();
            assertThat(validator.errors().first("title"))
                    .isEqualTo("The title field is required.");
        }

        @Test
        @DisplayName("未知语言回落到中文,而不是输出兜底模板")
        void unknownLocaleFallsBack() {
            var validator = Validator.make(Map.of(), Map.of("title", "required")).locale("kl_GL");

            validator.fails();
            assertThat(validator.errors().first("title")).isEqualTo("title 不能为空。");
        }

        @Test
        @DisplayName(":input 占位符带上用户填的值")
        void inputPlaceholder() {
            var validator = Validator.make(map("title", "重复的标题"),
                                           Map.of("title", "in:a,b"))
                    .messages(Map.of("title.in", "「:input」不是有效选项"));

            validator.fails();
            assertThat(validator.errors().first("title")).isEqualTo("「重复的标题」不是有效选项");
        }

        @Test
        @DisplayName("ErrorBag 的 JSON 与 Laravel 同构 —— 前端代码可直接复用")
        void jsonShapeMatchesLaravel() {
            var errors = errorsOf(map("title", ""), Map.of("title", "required"));
            var json = errors.toJson("给定数据无效。");

            assertThat(json).containsKeys("message", "errors");
            assertThat(json.get("message")).isEqualTo("给定数据无效。");
            @SuppressWarnings("unchecked")
            var map = (Map<String, List<String>>) json.get("errors");
            assertThat(map.get("title")).hasSize(1);
        }
    }

    // ================= validated() =================

    @Nested
    @DisplayName("validated():安全边界")
    class ValidatedData {

        @Test
        @DisplayName("只含声明过规则的字段 —— 这是防批量赋值的第一道闸")
        void onlyDeclaredFields() {
            var data = map("title", "文章", "is_admin", true, "secret", "x");
            var validated = Validator.make(data, Map.of("title", "required")).validate();

            assertThat(validated.all()).containsOnlyKeys("title");
        }

        @Test
        @DisplayName("类型化取值器")
        void typedAccessors() {
            var data = map("title", "文章", "views", "42", "ratio", "1.5",
                           "published", "1", "tags", List.of("1", "2"),
                           "at", "2026-10-29T10:00:00Z");

            var v = Validator.make(data, Map.of(
                    "title", "required|string",
                    "views", "integer",
                    "ratio", "numeric",
                    "published", "boolean",
                    "tags", "array",
                    "at", "date")).validate();

            assertThat(v.string("title")).isEqualTo("文章");
            assertThat(v.integer("views")).isEqualTo(42);
            assertThat(v.doubleValue("ratio")).isEqualTo(1.5);
            assertThat(v.bool("published")).isTrue();
            assertThat(v.longs("tags")).containsExactly(1L, 2L);
            assertThat(v.instant("at")).isNotNull();
        }

        @Test
        @DisplayName("取不存在的字段时报错指出是漏了 required 还是拼错名字")
        void missingFieldIsDiagnosable() {
            var v = Validator.make(map("a", 1), Map.of("a", "integer")).validate();

            assertThatThrownBy(() -> v.string("b"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不在校验结果里")
                    .hasMessageContaining("没写 required")
                    .hasMessageContaining("拼错");
        }

        @Test
        @DisplayName("可选字段用带默认值的重载")
        void optionalWithFallback() {
            var v = Validator.make(Map.of(), Map.of("page", "nullable|integer")).validate();

            assertThat(v.integer("page", 1)).isEqualTo(1);
            assertThat(v.string("q", "")).isEmpty();
        }

        @Test
        @DisplayName("转型失败时提示该在规则里声明类型")
        void conversionFailureSuggestsRule() {
            // 故意不声明 integer,让脏值通过校验再在取值时失败
            var v = Validator.make(map("n", "abc"), Map.of("n", "required")).validate();

            assertThatThrownBy(() -> v.integer("n"))
                    .hasMessageContaining("无法转成 Integer")
                    .hasMessageContaining("规则里应声明 integer");
        }

        @Test
        @DisplayName("only / except 裁剪")
        void onlyAndExcept() {
            var v = Validator.make(map("a", 1, "b", 2, "c", 3),
                    Map.of("a", "integer", "b", "integer", "c", "integer")).validate();

            assertThat(v.only("a", "b")).containsOnlyKeys("a", "b");
            assertThat(v.except("a")).containsOnlyKeys("b", "c");
        }

        @Test
        @DisplayName("maps() 对标量数组报错并指出该用哪个方法")
        void mapsOnScalarArray() {
            var v = Validator.make(map("tags", List.of("a")), Map.of("tags", "array")).validate();

            assertThatThrownBy(() -> v.maps("tags"))
                    .hasMessageContaining("不是对象")
                    .hasMessageContaining("strings()");
        }

        @Test
        @DisplayName("枚举取值器在值非法时指出应加 enum 规则")
        void enumAccessorSuggestsRule() {
            var v = Validator.make(map("s", "NOPE"), Map.of("s", "required")).validate();

            assertThatThrownBy(() -> v.enumValue("s", Status.class))
                    .hasMessageContaining("不是 Status 的枚举常量")
                    .hasMessageContaining("Rule.enumOf");
        }
    }

    enum Status { ACTIVE }

    // ================= 编排 =================

    @Nested
    @DisplayName("编排")
    class Orchestration {

        @Test
        @DisplayName("after 钩子跑在规则之后,可以加跨字段错误")
        void afterHook() {
            var validator = Validator.make(map("start", 5, "end", 1),
                    Map.of("start", "integer", "end", "integer"))
                    .after(v -> {
                        if (v.errors().isEmpty()) {
                            v.errors().add("end", "结束必须晚于开始");
                        }
                    });

            assertThat(validator.fails()).isTrue();
            assertThat(validator.errors().first("end")).isEqualTo("结束必须晚于开始");
        }

        @Test
        @DisplayName("stopOnFirstFailure 只报第一条")
        void stopOnFirstFailure() {
            var validator = Validator.make(Map.of(),
                    Map.of("a", "required", "b", "required"))
                    .stopOnFirstFailure();

            validator.fails();
            assertThat(validator.errors().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("字符串规则与 Builder 规则可以混用")
        void mixedRuleStyles() {
            var validator = Validator.make(map("title", "x".repeat(300), "n", 3))
                    .field("title", "required|string")
                    .field("title", Rule.max(200))
                    .field("n", Rule.of("even", v -> ((Number) v).intValue() % 2 == 0)
                            .withMessage("必须是偶数"));

            validator.fails();
            assertThat(validator.errors().first("title")).contains("不能超过 200 个字符");
            assertThat(validator.errors().first("n")).isEqualTo("必须是偶数");
        }

        @Test
        @DisplayName("validate() 失败抛 ValidationException,状态码 422")
        void throwsValidationException() {
            assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("t", "required")).validate())
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).status()).isEqualTo(422));
        }

        @Test
        @DisplayName("异常消息里带全部错误 —— 日志里看到它就够定位")
        void exceptionMessageIncludesAllErrors() {
            try {
                Validator.make(Map.of(), Map.of("a", "required", "b", "required")).validate();
            } catch (ValidationException e) {
                assertThat(e.getMessage())
                        .contains("a: ")
                        .contains("b: ");
                // 响应体用的标题不含明细
                assertThat(e.headline()).isEqualTo("给定数据无效。");
            }
        }

        @Test
        @DisplayName("规则写错时报错带上是哪个字段")
        void ruleErrorNamesTheField() {
            assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("title", "requried")))
                    .hasMessageContaining("字段 `title`");
        }

        @Test
        @DisplayName("校验器不改调用方的 Map")
        void doesNotMutateCallerData() {
            var original = map("a", 1);
            Validator.make(original, Map.of("a", "integer")).merge("b", 2).validate();

            assertThat(original).containsOnlyKeys("a");
        }
    }

    private static ErrorBag errorsOf(Map<String, Object> data, Map<String, String> rules) {
        var validator = Validator.make(data, rules);
        validator.fails();
        return validator.errors();
    }
}
