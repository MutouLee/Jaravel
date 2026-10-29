package io.jaravel.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.http.HttpException;
import io.jaravel.support.Str;

/** FormRequest 与需要查库的两条规则 */
class FormRequestTest {

    private java.sql.Connection jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("create table users (id integer primary key autoincrement, "
                     + "email text, tenant_id integer, deleted_at text)");
            st.execute("create table tags (id integer primary key autoincrement, "
                     + "name text, active integer default 1)");
            st.execute("insert into users (email, tenant_id) values ('taken@example.com', 1)");
            st.execute("insert into users (email, tenant_id) values ('other@example.com', 2)");
            st.execute("insert into tags (name, active) values ('java', 1)");
            st.execute("insert into tags (name, active) values ('php', 1)");
            st.execute("insert into tags (name, active) values ('retired', 0)");
        }
        DatabaseRule.useConnection(new JdbcConnection(nonClosing(jdbc), new SqliteGrammar()));
    }

    @AfterEach
    void tearDown() throws SQLException {
        DatabaseRule.useConnection(null);
        jdbc.close();
    }

    // ================= unique / exists =================

    @Nested
    @DisplayName("unique / exists")
    class Database {

        @Test
        @DisplayName("unique 查重")
        void unique() {
            assertThat(validate("email", "taken@example.com", "unique:users,email")).isFalse();
            assertThat(validate("email", "free@example.com", "unique:users,email")).isTrue();
        }

        @Test
        @DisplayName("unique 的 ignore 让「编辑但没改邮箱」不被自己判重 —— 最常见的误用")
        void uniqueIgnore() {
            // 不排除自己:用户编辑资料时会被自己的邮箱挡住
            assertThat(Validator.make(Map.of("email", "taken@example.com"))
                    .field("email", Rule.unique("users", "email")).passes()).isFalse();

            // 排除自己(id = 1)后通过
            assertThat(Validator.make(Map.of("email", "taken@example.com"))
                    .field("email", Rule.unique("users", "email").ignore(1)).passes()).isTrue();

            // 字符串写法
            assertThat(validate("email", "taken@example.com", "unique:users,email,1")).isTrue();
        }

        @Test
        @DisplayName("unique 带附加条件 —— 多租户下同邮箱可以在不同租户重复")
        void uniqueWithConstraint() {
            var rule = Rule.unique("users", "email").where(q -> q.where("tenant_id", 2));

            // taken@example.com 在 tenant 1,查 tenant 2 时不算重复
            assertThat(Validator.make(Map.of("email", "taken@example.com"))
                    .field("email", rule).passes()).isTrue();
        }

        @Test
        @DisplayName("unique 对 null 放行 —— 必填性交给 required")
        void uniqueIgnoresNull() {
            var data = new LinkedHashMap<String, Object>();
            data.put("email", null);
            assertThat(Validator.make(data, Map.of("email", "nullable|unique:users,email"))
                    .passes()).isTrue();
        }

        @Test
        @DisplayName("exists 单值")
        void existsSingle() {
            assertThat(validate("tag", 1, "exists:tags,id")).isTrue();
            assertThat(validate("tag", 999, "exists:tags,id")).isFalse();
        }

        @Test
        @DisplayName("exists 对数组用一条 IN 查询核对全部,不是每个元素一条")
        void existsArrayUsesSingleQuery() {
            assertThat(Validator.make(Map.of("tags", List.of(1, 2)))
                    .field("tags", Rule.exists("tags", "id")).passes()).isTrue();
            // 其中一个不存在就整体失败
            assertThat(Validator.make(Map.of("tags", List.of(1, 999)))
                    .field("tags", Rule.exists("tags", "id")).passes()).isFalse();
        }

        @Test
        @DisplayName("exists 带附加条件")
        void existsWithCondition() {
            // retired 的 id = 3,但 active = 0
            assertThat(validate("tag", 3, "exists:tags,id")).isTrue();
            assertThat(validate("tag", 3, "exists:tags,id,active,1")).isFalse();
            assertThat(validate("tag", 1, "exists:tags,id,active,1")).isTrue();
        }

        @Test
        @DisplayName("exists 的附加条件不成对时报错")
        void existsOddConditions() {
            assertThatThrownBy(() -> Validator.make(Map.of(), Map.of("t", "exists:tags,id,active")))
                    .hasMessageContaining("必须成对出现");
        }

        @Test
        @DisplayName("没有连接时报错并给出两条出路")
        void missingConnectionIsActionable() {
            DatabaseRule.useConnection(null);

            assertThatThrownBy(() -> validate("email", "x@y.co", "unique:users,email"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DatabaseRule.useConnection")
                    .hasMessageContaining("自定义规则");
        }

        private boolean validate(String field, Object value, String rule) {
            return Validator.make(Map.of(field, value), Map.of(field, rule)).passes();
        }
    }

    // ================= FormRequest =================

    @Nested
    @DisplayName("FormRequest")
    class Form {

        @Test
        @DisplayName("通过校验后取值器可用")
        void happyPath() {
            var req = new StorePostRequest();
            req.bind(request(Map.of("title", "My Post", "body", "正文内容够长了")));

            assertThat(req.title()).isEqualTo("My Post");
            assertThat(req.body()).isEqualTo("正文内容够长了");
            assertThat(req.tagIds()).isEmpty();
        }

        @Test
        @DisplayName("prepareForValidation 在校验前改数据")
        void prepareRunsBeforeValidation() {
            var req = new StorePostRequest();
            req.bind(request(Map.of("title", "Hello World", "body", "正文内容够长了")));

            // slug 不是用户传的,是 prepareForValidation 补的
            assertThat(req.validated().string("slug")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("校验失败抛 422,且错误里带字段")
        void validationFailureIs422() {
            var req = new StorePostRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of("title", ""))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> {
                        var errors = ((ValidationException) e).errors();
                        assertThat(errors.has("title")).isTrue();
                        assertThat(errors.has("body")).isTrue();
                    });
        }

        @Test
        @DisplayName("自定义消息生效")
        void customMessages() {
            var req = new StorePostRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of("body", "正文内容够长了"))))
                    .satisfies(e -> assertThat(((ValidationException) e).errors().first("title"))
                            .isEqualTo("标题不能为空"));
        }

        @Test
        @DisplayName("authorize 返回 false → 403,且不执行校验")
        void unauthorizedIs403() {
            var req = new ForbiddenRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of())))
                    .isInstanceOf(HttpException.class)
                    .satisfies(e -> assertThat(((HttpException) e).status()).isEqualTo(403));
        }

        @Test
        @DisplayName("withValidator 挂的跨字段校验生效")
        void withValidatorHook() {
            var req = new DateRangeRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of(
                    "start", "2026-05-01", "end", "2026-01-01"))))
                    .satisfies(e -> assertThat(((ValidationException) e).errors().first("end"))
                            .isEqualTo("结束日期不能早于开始日期"));

            // 顺序正确时通过
            var ok = new DateRangeRequest();
            ok.bind(request(Map.of("start", "2026-01-01", "end", "2026-05-01")));
            assertThat(ok.validated().has("end")).isTrue();
        }

        @Test
        @DisplayName("未绑定就取值时说清是框架调用还是测试要手动 bind")
        void unboundIsActionable() {
            assertThatThrownBy(() -> new StorePostRequest().validated())
                    .hasMessageContaining("还没有绑定请求")
                    .hasMessageContaining("req.bind(testRequest)");
        }

        @Test
        @DisplayName("覆盖 failedValidation 但不抛异常时拦住 —— 否则未校验数据会流下去")
        void failedValidationMustThrow() {
            var req = new SwallowingRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有抛出异常")
                    .hasMessageContaining("未通过校验的数据");
        }

        @Test
        @DisplayName("纯中文标题的 slug 为空 —— prepareForValidation 里必须考虑这件事")
        void cjkTitleProducesEmptySlug() {
            // Str.slug 会剥掉非 ASCII(与 Laravel 的 Str::slug 行为一致),
            // 所以「标题自动转 slug」对中文站点天然失效。把它变成一条
            // 显式的校验错误,比默默写入空 slug 再在路由上 404 要好。
            var req = new StorePostRequest();

            assertThatThrownBy(() -> req.bind(request(Map.of(
                    "title", "我的文章", "body", "正文内容够长了"))))
                    .satisfies(e -> assertThat(((ValidationException) e).errors().has("slug"))
                            .isTrue());
        }

        @Test
        @DisplayName("validated() 只含声明过的字段,挡住批量赋值")
        void validatedIsNarrow() {
            var req = new StorePostRequest();
            req.bind(request(Map.of("title", "Featured", "body", "正文内容够长了",
                                    "is_featured", "true")));

            assertThat(req.validated().all()).doesNotContainKey("is_featured");
        }
    }

    // ================= 测试用的 FormRequest =================

    static class StorePostRequest extends FormRequest {
        @Override public Map<String, String> rules() {
            return Map.of(
                    "title", "required|string|max:200",
                    "slug",  "required|string",
                    "body",  "required|string|min:5",
                    "tags",  "nullable|array|max:5");
        }

        @Override public Map<String, String> messages() {
            return Map.of("title.required", "标题不能为空");
        }

        @Override protected void prepareForValidation() {
            merge("slug", Str.slug(input("title", "")));
        }

        public String title()      { return validated().string("title"); }
        public String body()       { return validated().string("body"); }
        public List<Long> tagIds() { return validated().longs("tags"); }
    }

    static class ForbiddenRequest extends FormRequest {
        @Override public boolean authorize() { return false; }
        @Override public Map<String, String> rules() {
            // 若 authorize 之后还跑校验,这条会先抛 422 而不是 403
            return Map.of("required_field", "required");
        }
    }

    static class DateRangeRequest extends FormRequest {
        @Override public Map<String, String> rules() {
            return Map.of("start", "required|date", "end", "required|date");
        }

        @Override protected void withValidator(Validator validator) {
            validator.after(v -> {
                if (v.errors().isNotEmpty()) return;
                var start = Rules.parseDate(v.data().get("start"));
                var end = Rules.parseDate(v.data().get("end"));
                if (start != null && end != null && end.isBefore(start)) {
                    v.errors().add("end", "结束日期不能早于开始日期");
                }
            });
        }
    }

    static class SwallowingRequest extends FormRequest {
        @Override public Map<String, String> rules() {
            return Map.of("x", "required");
        }

        @Override protected void failedValidation(ErrorBag errors) {
            // 故意什么都不做 —— 框架应该拦住这种写法
        }
    }

    // ================= 夹具 =================

    private static io.jaravel.http.Request request(Map<String, Object> input) {
        var r = io.jaravel.http.BasicRequest.of("POST", "/posts");
        input.forEach(r::bodyParam);
        return r;
    }

    private static DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String p) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
