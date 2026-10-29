package io.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jaravel.http.Response;
import io.jaravel.session.SessionData;

/**
 * {@link TestResponse} 自己的测试。
 *
 * <p>断言库必须有测试:一个**永远通过**的断言比没有断言更糟 ——
 * 它让人以为某件事被验证过了。所以每条断言都要有「该通过时通过、
 * 该失败时失败」两个方向的用例。
 */
class TestResponseTest {

    private TestResponse responseOf(int status, String body) {
        return responseOf(status, body, null, null);
    }

    private TestResponse responseOf(int status, String body, SessionData session,
                                    Response jaravelResponse) {
        var raw = new MockHttpServletResponse();
        raw.setStatus(status);
        return new TestResponse(raw, body, session, jaravelResponse);
    }

    private TestResponse withHeaders(int status, String body, Map<String, String> headers) {
        var raw = new MockHttpServletResponse();
        raw.setStatus(status);
        headers.forEach(raw::addHeader);
        return new TestResponse(raw, body, null, null);
    }

    // ================= 状态 =================

    @Nested
    @DisplayName("状态断言")
    class Status {

        @Test
        @DisplayName("匹配时通过,不匹配时失败")
        void bothDirections() {
            responseOf(200, "ok").assertOk();

            assertThatThrownBy(() -> responseOf(404, "nope").assertOk())
                    .hasMessageContaining("期望状态 200,实际 404");
        }

        @Test
        @DisplayName("★ 失败消息里带响应体")
        void failureIncludesBody() {
            assertThatThrownBy(() -> responseOf(500, "数据库连接失败").assertOk())
                    .as("HTTP 测试失败时第一个问题永远是「那它到底返回了什么」")
                    .hasMessageContaining("数据库连接失败");
        }

        @Test
        @DisplayName("assertSuccessful 接受整个 2xx")
        void successfulRange() {
            responseOf(200, "").assertSuccessful();
            responseOf(204, "").assertSuccessful();
            assertThatThrownBy(() -> responseOf(302, "").assertSuccessful())
                    .hasMessageContaining("期望 2xx");
        }
    }

    // ================= 重定向 =================

    @Nested
    @DisplayName("重定向")
    class Redirect {

        @Test
        @DisplayName("★ 只比对路径 —— Location 可能是绝对 URL")
        void comparesPathOnly() {
            // Redirect.to(intendedUrl()) 存的是 fullUrl,
            // 而测试关心的几乎总是「跳到哪个页面」
            withHeaders(302, "", Map.of("Location", "http://localhost:8080/dashboard"))
                    .assertRedirect("/dashboard");

            withHeaders(302, "", Map.of("Location", "/dashboard?from=login"))
                    .assertRedirect("/dashboard");
        }

        @Test
        @DisplayName("路径不符时失败,消息里带实际的 Location")
        void mismatchFails() {
            assertThatThrownBy(() ->
                    withHeaders(302, "", Map.of("Location", "/login"))
                            .assertRedirect("/dashboard"))
                    .hasMessageContaining("/login");
        }

        @Test
        @DisplayName("不是 3xx 时失败")
        void nonRedirectFails() {
            assertThatThrownBy(() -> responseOf(200, "ok").assertRedirect("/x"))
                    .hasMessageContaining("期望 3xx");
        }

        @Test
        @DisplayName("3xx 但没有 Location 时失败")
        void missingLocationFails() {
            assertThatThrownBy(() -> responseOf(302, "").assertRedirect("/x"))
                    .hasMessageContaining("没有 Location");
        }
    }

    // ================= 内容 =================

    @Nested
    @DisplayName("内容")
    class Content {

        @Test
        @DisplayName("assertSee / assertDontSee")
        void seeAndDontSee() {
            var response = responseOf(200, "第一篇文章");
            response.assertSee("第一篇").assertDontSee("草稿");

            assertThatThrownBy(() -> response.assertSee("草稿"))
                    .hasMessageContaining("找不到");
            assertThatThrownBy(() -> response.assertDontSee("第一篇"))
                    .hasMessageContaining("不该出现");
        }

        @Test
        @DisplayName("★ assertSeeText 剥掉标签 —— 用户看到的是连起来的文字")
        void seeTextStripsTags() {
            var response = responseOf(200, "<b>第一</b>篇");

            // 直接 contains 会失败,然后让人以为是数据不对
            assertThatThrownBy(() -> response.assertSee("第一篇"))
                    .hasMessageContaining("找不到");
            response.assertSeeText("第一篇");
        }

        @Test
        @DisplayName("★ assertSeeInOrder 用于断言排序")
        void seeInOrder() {
            var response = responseOf(200, "<li>甲</li><li>乙</li><li>丙</li>");

            response.assertSeeInOrder(List.of("甲", "乙", "丙"));

            assertThatThrownBy(() -> response.assertSeeInOrder(List.of("丙", "甲")))
                    .hasMessageContaining("按顺序");
        }

        @Test
        @DisplayName("头断言")
        void headers() {
            var response = withHeaders(200, "", Map.of("X-Total", "100"));

            response.assertHeader("X-Total", "100").assertHeaderMissing("X-Page");

            assertThatThrownBy(() -> response.assertHeader("X-Total", "1"))
                    .hasMessageContaining("实际 \"100\"");
        }
    }

    // ================= Cookie =================

    @Nested
    @DisplayName("Cookie")
    class Cookies {

        private TestResponse withCookies(String... setCookies) {
            var raw = new MockHttpServletResponse();
            raw.setStatus(200);
            for (String c : setCookies) raw.addHeader("Set-Cookie", c);
            return new TestResponse(raw, "", null, null);
        }

        @Test
        @DisplayName("按名字与按值断言")
        void nameAndValue() {
            var response = withCookies("remember_web=abc|def; Path=/; HttpOnly");

            response.assertCookie("remember_web")
                    .assertCookie("remember_web", "abc|def")
                    .assertCookieMissing("other");

            assertThatThrownBy(() -> response.assertCookie("other"))
                    .hasMessageContaining("实际下发");
        }

        @Test
        @DisplayName("★ assertCookieExpired —— 删 cookie 是「同名空值 Max-Age=0」")
        void expired() {
            var deleted = withCookies("remember_web=; Path=/; Max-Age=0");

            // assertCookieMissing 在登出场景里会误判通过,因为
            // 服务端确实下发了一个同名 cookie
            assertThatThrownBy(() -> deleted.assertCookieMissing("remember_web"))
                    .hasMessageContaining("不该下发");
            deleted.assertCookieExpired("remember_web");

            var alive = withCookies("remember_web=abc; Max-Age=2592000");
            assertThatThrownBy(() -> alive.assertCookieExpired("remember_web"))
                    .hasMessageContaining("不是过期的");
        }

        @Test
        @DisplayName("cookieHeader 给出完整属性串 —— 用于断言 HttpOnly / SameSite")
        void fullHeader() {
            var response = withCookies("s=1; Path=/; HttpOnly; SameSite=Lax");

            assertThat(response.cookieHeader("s").orElseThrow())
                    .contains("HttpOnly").contains("SameSite=Lax");
        }
    }

    // ================= JSON =================

    @Nested
    @DisplayName("JSON")
    class Json {

        private final String body = """
                {"data":{"id":7,"title":"标题","author":{"name":"张三"},
                 "tags":["a","b","c"]},"meta":{"total":1}}""";

        @Test
        @DisplayName("点号路径,数组下标也用点号")
        void jsonPath() {
            var response = responseOf(200, body);

            response.assertJsonPath("data.id", 7)
                    .assertJsonPath("data.author.name", "张三")
                    .assertJsonPath("data.tags.0", "a");

            assertThatThrownBy(() -> response.assertJsonPath("data.id", 8))
                    .hasMessageContaining("期望 \"8\",实际 \"7\"");
        }

        @Test
        @DisplayName("路径不存在时失败")
        void missingPath() {
            var response = responseOf(200, body);

            response.assertJsonMissing("data.internal_note");

            assertThatThrownBy(() -> response.assertJsonPath("data.nope", 1))
                    .hasMessageContaining("不存在");
            assertThatThrownBy(() -> response.assertJsonMissing("data.id"))
                    .hasMessageContaining("不该存在");
        }

        @Test
        @DisplayName("数组长度与结构")
        void countAndStructure() {
            var response = responseOf(200, body);

            response.assertJsonCount(3, "data.tags")
                    .assertJsonStructure("data", List.of("id", "title", "author", "tags"));

            assertThatThrownBy(() -> response.assertJsonCount(2, "data.tags"))
                    .hasMessageContaining("期望 2 个元素,实际 3");
            assertThatThrownBy(() ->
                    response.assertJsonStructure("data", List.of("id", "body")))
                    .hasMessageContaining("缺少键");
        }

        @Test
        @DisplayName("★ 校验错误断言同时检查 422")
        void validationErrors() {
            var response = responseOf(422,
                    "{\"message\":\"校验失败\",\"errors\":{\"title\":[\"必填\"]}}");

            response.assertJsonValidationErrors("title")
                    .assertJsonMissingValidationErrors("body");

            assertThatThrownBy(() -> response.assertJsonValidationErrors("body"))
                    .hasMessageContaining("实际有错误的字段");

            // 状态不对时先在状态上失败 —— 那通常才是真正的问题
            assertThatThrownBy(() -> responseOf(200, "{}")
                    .assertJsonValidationErrors("title"))
                    .hasMessageContaining("期望状态 422");
        }

        @Test
        @DisplayName("不是 JSON 时给出明确错误")
        void notJson() {
            assertThatThrownBy(() -> responseOf(200, "<html>").assertJsonPath("a", 1))
                    .hasMessageContaining("不是合法 JSON");
        }
    }

    // ================= 视图 =================

    @Nested
    @DisplayName("视图")
    class Views {

        private TestResponse viewResponse() {
            var view = io.jaravel.http.View.of("posts.index")
                    .with("posts", List.of("甲", "乙"))
                    .with("title", "列表");
            return responseOf(200, "<html>", null, view.toResponse());
        }

        @Test
        @DisplayName("视图名与视图数据")
        void nameAndData() {
            var response = viewResponse();

            response.assertViewIs("posts.index")
                    .assertViewHas("posts")
                    .assertViewHas("posts", (List<?> p) -> p.size() == 2)
                    .assertViewMissing("user");

            assertThatThrownBy(() -> response.assertViewIs("posts.show"))
                    .hasMessageContaining("实际 posts.index");
            assertThatThrownBy(() -> response.assertViewHas("user"))
                    .hasMessageContaining("实际键");
        }

        @Test
        @DisplayName("★ 不是视图时说清实际是什么")
        void notAView() {
            var jsonResponse = responseOf(200, "{}", null, Response.json(Map.of()));

            assertThatThrownBy(() -> jsonResponse.assertViewIs("x"))
                    .hasMessageContaining("实际是 JSON");
        }

        @Test
        @DisplayName("谓词不满足时带上实际值")
        void predicateFailureShowsValue() {
            assertThatThrownBy(() ->
                    viewResponse().assertViewHas("posts", (List<?> p) -> p.size() == 3))
                    .hasMessageContaining("[甲, 乙]");
        }
    }

    // ================= 会话 =================

    @Nested
    @DisplayName("会话")
    class Session {

        private TestResponse withSession(Map<String, Object> data) {
            var session = new SessionData("test-session-id");
            session.replaceAll(new java.util.LinkedHashMap<>(data));
            return responseOf(200, "", session, null);
        }

        @Test
        @DisplayName("按键与按值")
        void hasAndMissing() {
            var response = withSession(Map.of("status", "已保存"));

            response.assertSessionHas("status")
                    .assertSessionHas("status", "已保存")
                    .assertSessionMissing("errors");

            assertThatThrownBy(() -> response.assertSessionHas("nope"))
                    .hasMessageContaining("实际键");
            assertThatThrownBy(() -> response.assertSessionHas("status", "其它"))
                    .hasMessageContaining("实际 \"已保存\"");
        }

        @Test
        @DisplayName("★ 校验错误读的是闪存的 errors")
        void errors() {
            var response = withSession(Map.of("errors",
                    Map.of("title", List.of("必填"))));

            response.assertSessionHasErrors("title");

            assertThatThrownBy(() -> response.assertSessionHasErrors("body"))
                    .hasMessageContaining("实际:[title]");
            assertThatThrownBy(response::assertSessionHasNoErrors)
                    .hasMessageContaining("不该有校验错误");

            withSession(Map.of()).assertSessionHasNoErrors();
        }

        @Test
        @DisplayName("★ 没有会话时提示中间件没挂")
        void noSession() {
            assertThatThrownBy(() -> responseOf(200, "").assertSessionHas("x"))
                    .hasMessageContaining("StartSession");
        }
    }
}
