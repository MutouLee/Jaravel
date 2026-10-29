package io.jaravel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SupportTest {

    @Nested
    @DisplayName("Str · 命名风格转换")
    class StrNaming {

        @ParameterizedTest
        @CsvSource({
            "HelloWorld,      hello_world",
            "helloWorld,      hello_world",
            "hello-world,     hello_world",
            "hello world,     hello_world",
            "HTTPResponse,    http_response",   // 连续大写的边界
            "userID,          user_id",
            "already_snake,   already_snake",
        })
        void snake(String input, String expected) {
            assertThat(Str.snake(input)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "hello_world,  HelloWorld",
            "hello-world,  HelloWorld",
            "hello world,  HelloWorld",
            "user_id,      UserId",
        })
        void studly(String input, String expected) {
            assertThat(Str.studly(input)).isEqualTo(expected);
        }

        @Test void camel() {
            assertThat(Str.camel("hello_world")).isEqualTo("helloWorld");
            assertThat(Str.camel("user_id")).isEqualTo("userId");
        }

        @Test void kebab() {
            assertThat(Str.kebab("HelloWorld")).isEqualTo("hello-world");
        }

        @ParameterizedTest
        @CsvSource({
            "Hello World!,          hello-world",
            "  Trim  Me  ,         trim-me",
            "Café Münster,          cafe-munster",   // 变音符号
            "a--b__c,               a-b-c",
        })
        void slug(String input, String expected) {
            assertThat(Str.slug(input)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Str · 单复数(表名推导依赖它)")
    class StrInflection {

        @ParameterizedTest
        @CsvSource({
            "post,     posts",      "user,     users",
            "category, categories", "city,     cities",
            "box,      boxes",      "class,    classes",
            "dish,     dishes",     "match,    matches",
            "leaf,     leaves",     "knife,    knives",
            "hero,     heroes",     "photo,    photos",   // 元音前的 o 不加 es
            "person,   people",     "child,    children",
            "sheep,    sheep",      "series,   series",
            "Post,     Posts",                             // 保留首字母大小写
        })
        void plural(String single, String expected) {
            assertThat(Str.plural(single)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "posts,      post",    "categories, category",
            "boxes,      box",     "classes,    class",
            "leaves,     leaf",    "people,     person",
            "children,   child",   "sheep,      sheep",
        })
        void singular(String plural, String expected) {
            assertThat(Str.singular(plural)).isEqualTo(expected);
        }

        @Test
        @DisplayName("plural/singular 对常见词互为逆运算")
        void roundTrip() {
            for (String w : List.of("post", "user", "category", "box", "person", "child")) {
                assertThat(Str.singular(Str.plural(w)))
                        .as("singular(plural(%s))", w)
                        .isEqualTo(w);
            }
        }
    }

    @Nested
    @DisplayName("Str · 其他")
    class StrMisc {

        @Test void fluentChaining() {
            assertThat(Str.of("hello world").title().get()).isEqualTo("Hello World");
            assertThat(Str.of("Hello World").slug().get()).isEqualTo("hello-world");
            assertThat(Str.of("hello").append("!").upper().get()).isEqualTo("HELLO!");
        }

        @Test void wildcardMatch() {
            assertThat(Str.is("api/*", "api/users")).isTrue();
            assertThat(Str.is("api/*", "web/users")).isFalse();
            assertThat(Str.is("*.css", "app.css")).isTrue();
            assertThat(Str.is("exact", "exact")).isTrue();
            // 正则元字符应被转义,不当作模式
            assertThat(Str.is("a.b", "axb")).isFalse();
        }

        @Test void limitAndMask() {
            assertThat(Str.limit("abcdefghij", 5)).isEqualTo("abcde...");
            assertThat(Str.limit("abc", 5)).isEqualTo("abc");
            assertThat(Str.mask("13800138000", '*', 3, 4)).isEqualTo("138****8000");
        }

        @Test void beforeAfter() {
            assertThat(Str.before("a/b/c", "/")).isEqualTo("a");
            assertThat(Str.after("a/b/c", "/")).isEqualTo("b/c");
            assertThat(Str.beforeLast("a/b/c", "/")).isEqualTo("a/b");
            assertThat(Str.afterLast("a/b/c", "/")).isEqualTo("c");
            // 分隔符不存在时返回原串,不抛异常
            assertThat(Str.before("abc", "/")).isEqualTo("abc");
        }

        @Test void startFinishAreIdempotent() {
            assertThat(Str.start("users", "/")).isEqualTo("/users");
            assertThat(Str.start("/users", "/")).isEqualTo("/users");
            assertThat(Str.finish("a", "/")).isEqualTo("a/");
            assertThat(Str.finish("a/", "/")).isEqualTo("a/");
        }
    }

    @Nested
    @DisplayName("Arr · 点号路径")
    class ArrPaths {

        private Map<String, Object> nested() {
            return Map.of("app", Map.of("name", "Jaravel", "debug", true),
                          "db", Map.of("connections", Map.of("mysql", Map.of("port", 3306))),
                          "items", List.of(Map.of("sku", "A1"), Map.of("sku", "B2")));
        }

        @Test void get() {
            var m = nested();
            assertThat(Arr.get(m, "app.name")).isEqualTo("Jaravel");
            assertThat(Arr.get(m, "db.connections.mysql.port")).isEqualTo(3306);
            assertThat(Arr.get(m, "app.missing")).isNull();
            assertThat(Arr.get(m, "nope.nope")).isNull();
            assertThat(Arr.get(m, "app.name.deeper")).isNull();   // 穿透标量不应抛异常
        }

        @Test void getListIndex() {
            assertThat(Arr.get(nested(), "items.0.sku")).isEqualTo("A1");
            assertThat(Arr.get(nested(), "items.1.sku")).isEqualTo("B2");
            assertThat(Arr.get(nested(), "items.9.sku")).isNull();
        }

        @Test void getWithType() {
            assertThat(Arr.get(Map.of("a", Map.of("b", "42")), "a.b", Integer.class)).contains(42);
        }

        @Test void setCreatesIntermediateLevels() {
            Map<String, Object> m = new LinkedHashMap<>();
            Arr.set(m, "a.b.c", "v");
            assertThat(Arr.get(m, "a.b.c")).isEqualTo("v");
        }

        @Test void dotAndUndotRoundTrip() {
            var flat = Arr.dot(nested());
            assertThat(flat).containsEntry("app.name", "Jaravel")
                            .containsEntry("db.connections.mysql.port", 3306);
            assertThat(Arr.get(Arr.undot(flat), "db.connections.mysql.port")).isEqualTo(3306);
        }

        @Test
        @DisplayName("mergeDeep 递归合并嵌套层,而非整体覆盖")
        void mergeDeep() {
            var base = Map.of("db", Map.of("host", "localhost", "port", 3306));
            var override = Map.of("db", Map.of("port", 5432));
            var merged = Arr.mergeDeep(base, override);
            assertThat(Arr.get(merged, "db.host")).isEqualTo("localhost");   // 保留
            assertThat(Arr.get(merged, "db.port")).isEqualTo(5432);          // 覆盖
        }

        @Test
        @DisplayName("closest 为错误信息提供『是否想写 X?』建议")
        void closest() {
            var cols = List.of("name", "email", "created_at");
            assertThat(Arr.closest("nmae", cols)).contains("name");
            assertThat(Arr.closest("emial", cols)).contains("email");
            // 差太远不给建议,避免误导
            assertThat(Arr.closest("zzzzzzzz", cols)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cast · 宽松类型转换")
    class CastTest {

        @Test void primitives() {
            assertThat(Cast.to("42", Integer.class)).isEqualTo(42);
            assertThat(Cast.to("1_000", Integer.class)).isEqualTo(1000);
            assertThat(Cast.to(" 42 ", Long.class)).isEqualTo(42L);
            assertThat(Cast.to("3.14", BigDecimal.class)).isEqualByComparingTo("3.14");
        }

        @ParameterizedTest
        @CsvSource({"true,true", "1,true", "yes,true", "on,true",
                    "false,false", "0,false", "no,false", "off,false"})
        void booleans(String input, boolean expected) {
            assertThat(Cast.to(input, Boolean.class)).isEqualTo(expected);
        }

        @Test void durationShorthand() {
            assertThat(Cast.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
            assertThat(Cast.parseDuration("120m")).isEqualTo(Duration.ofMinutes(120));
            assertThat(Cast.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
            assertThat(Cast.parseDuration("7d")).isEqualTo(Duration.ofDays(7));
            assertThat(Cast.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
            assertThat(Cast.parseDuration("PT2H")).isEqualTo(Duration.ofHours(2));
            assertThat(Cast.parseDuration("90")).isEqualTo(Duration.ofSeconds(90));
        }

        enum Status { ACTIVE, PENDING, SOFT_DELETED }

        @Test void enums() {
            assertThat(Cast.to("ACTIVE", Status.class)).isEqualTo(Status.ACTIVE);
            assertThat(Cast.to("active", Status.class)).isEqualTo(Status.ACTIVE);
            assertThat(Cast.to("soft-deleted", Status.class)).isEqualTo(Status.SOFT_DELETED);
        }

        @Test
        @DisplayName("转换失败抛异常并给出可操作的消息,不静默返回 null")
        void failureMessagesAreActionable() {
            assertThatThrownBy(() -> Cast.to("abc", Integer.class))
                    .isInstanceOf(Cast.CastException.class)
                    .hasMessageContaining("abc")
                    .hasMessageContaining("Integer");

            assertThatThrownBy(() -> Cast.to("maybe", Boolean.class))
                    .isInstanceOf(Cast.CastException.class)
                    .hasMessageContaining("true/false");

            // 枚举错值应列出可选项并给相似度建议
            assertThatThrownBy(() -> Cast.to("ACTIV", Status.class))
                    .isInstanceOf(Cast.CastException.class)
                    .hasMessageContaining("ACTIVE")
                    .hasMessageContaining("是否想写");

            assertThatThrownBy(() -> Cast.parseDuration("5x"))
                    .isInstanceOf(Cast.CastException.class)
                    .hasMessageContaining("ms/s/m/h/d");
        }

        @Test void nullPassesThrough() {
            assertThat(Cast.to(null, Integer.class)).isNull();
        }
    }

    @Nested
    @DisplayName("Coll · 集合")
    class CollTest {

        record P(String name, int age, String city) {}

        private Coll<P> people() {
            return Coll.of(new P("Tom", 30, "BJ"), new P("Amy", 25, "SH"),
                           new P("Bob", 35, "BJ"), new P("Cat", 25, "SZ"));
        }

        @Test void mapFilterChain() {
            assertThat(people().filter(p -> p.age() >= 30).map(P::name).toList())
                    .containsExactly("Tom", "Bob");
            assertThat(people().reject(p -> p.age() >= 30).map(P::name).toList())
                    .containsExactly("Amy", "Cat");
        }

        @Test void groupByPreservesOrder() {
            var byCity = people().groupBy(P::city);
            assertThat(byCity.keySet()).containsExactly("BJ", "SH", "SZ");
            assertThat(byCity.get("BJ").map(P::name).toList()).containsExactly("Tom", "Bob");
        }

        @Test void keyByAndPluck() {
            assertThat(people().keyBy(P::name).keySet()).containsExactly("Tom", "Amy", "Bob", "Cat");
            assertThat(people().pluck(P::age).toList()).containsExactly(30, 25, 35, 25);
        }

        @Test void uniqueByKey() {
            assertThat(people().unique(P::age).map(P::name).toList())
                    .containsExactly("Tom", "Amy", "Bob");   // 25 只保留第一个
        }

        @Test void sorting() {
            assertThat(people().sortBy(P::age).map(P::name).toList())
                    .containsExactly("Amy", "Cat", "Tom", "Bob");
            assertThat(people().sortByDesc(P::age).first().map(P::name)).contains("Bob");
        }

        @Test void chunking() {
            assertThat(people().chunk(3).size()).isEqualTo(2);
            assertThat(people().chunk(3).get(1).size()).isEqualTo(1);
            assertThatThrownBy(() -> people().chunk(0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void aggregates() {
            assertThat(people().sumLong(p -> (long) p.age())).isEqualTo(115);
            assertThat(people().max(P::age)).contains(35);
            assertThat(people().min(P::age)).contains(25);
            assertThat(people().any(p -> p.city().equals("SZ"))).isTrue();
            assertThat(people().all(p -> p.age() > 20)).isTrue();
            assertThat(people().none(p -> p.age() > 40)).isTrue();
            assertThat(people().count(p -> p.city().equals("BJ"))).isEqualTo(2);
        }

        @Test void joinAndTake() {
            assertThat(people().map(P::name).join(", ")).isEqualTo("Tom, Amy, Bob, Cat");
            assertThat(people().take(2).map(P::name).join("|")).isEqualTo("Tom|Amy");
            assertThat(people().skip(3).map(P::name).join("|")).isEqualTo("Cat");
            assertThat(people().skip(99).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("when/unless 让条件逻辑不打断链式")
        void conditional() {
            boolean filterBJ = true;
            var r = people()
                    .when(filterBJ, c -> c.filter(p -> p.city().equals("BJ")))
                    .unless(filterBJ, c -> c.take(1))
                    .map(P::name).toList();
            assertThat(r).containsExactly("Tom", "Bob");
        }

        @Test void escapeHatchToStream() {
            assertThat(people().stream().mapToInt(P::age).sum()).isEqualTo(115);
        }

        @Test void immutability() {
            var original = people();
            original.filter(p -> false);
            assertThat(original.size()).isEqualTo(4);   // 原集合不变
        }

        @Test void emptyBehaviour() {
            assertThat(Coll.empty().isEmpty()).isTrue();
            assertThat(Coll.empty().first()).isEmpty();
            assertThatThrownBy(() -> Coll.empty().firstOrFail())
                    .isInstanceOf(java.util.NoSuchElementException.class);
        }
    }
}
