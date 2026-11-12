package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.http.BasicRequest;
import io.jaravel.http.RequestScope;
import io.jaravel.http.Response;

/**
 * 内容协商(docs/12 §6.2)。
 *
 * <p>直接驱动 {@link ResponseWriter}:Accept 从请求作用域读、格式在写出时
 * 才决定,这两件事在真 HTTP 上与在这里是同一段代码。
 */
class ContentNegotiationTest {

    private final ResponseWriter writer =
            new ResponseWriter(null, new ObjectMapper(), false);

    private record Written(MockHttpServletResponse response, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private Written write(Response response, String accept) throws Exception {
        var out = new MockHttpServletResponse();
        var request = BasicRequest.of("GET", "/x");
        if (accept != null) request.header("Accept", accept);

        byte[] body = RequestScope.runIn(request, () -> writer.serialize(response, out));
        return new Written(out, body);
    }

    private static Map<String, Object> person() {
        var person = new LinkedHashMap<String, Object>();
        person.put("id", 1);
        person.put("name", "A < B & C");
        person.put("tags", List.of("x", "y"));
        return person;
    }

    // ================= 选择 =================

    @Nested
    @DisplayName("Accept 选择")
    class Selection {

        @Test
        @DisplayName("一个格式都没声明 → 默认 JSON")
        void defaultsToJson() throws Exception {
            var written = write(Response.negotiated(person()), null);

            assertThat(written.response().getContentType()).startsWith("application/json");
            assertThat(written.text()).contains("\"id\":1").contains("\"name\":\"A < B & C\"");
        }

        @Test
        @DisplayName("Accept: application/xml → XML")
        void selectsXml() throws Exception {
            var written = write(Response.negotiated(person()).json().xml(), "application/xml");

            assertThat(written.response().getContentType()).startsWith("application/xml");
            assertThat(written.text()).isEqualTo(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<response><id>1</id><name>A &lt; B &amp; C</name>"
                            + "<tags><item>x</item><item>y</item></tags></response>");
            assertThat(written.response().getHeader("Vary"))
                    .as("同一 URL 随 Accept 变化,缓存必须知道")
                    .contains("Accept");
        }

        @Test
        @DisplayName("Accept: text/csv → CSV")
        void selectsCsv() throws Exception {
            var rows = List.of(
                    Map.of("id", 1, "name", "A,B"),
                    Map.of("id", 2, "name", "say \"hi\""));

            var written = write(Response.negotiated(rows)
                    .json().xml().csv(c -> c.headers("id", "name")), "text/csv");

            assertThat(written.response().getContentType()).startsWith("text/csv");
            assertThat(written.text()).isEqualTo(
                    "id,name\r\n1,\"A,B\"\r\n2,\"say \"\"hi\"\"\"\r\n");
        }

        @Test
        @DisplayName("q 值高的赢")
        void respectsQuality() throws Exception {
            var written = write(Response.negotiated(person()).json().xml(),
                    "application/json;q=0.4, application/xml;q=0.9");

            assertThat(written.response().getContentType()).startsWith("application/xml");
        }

        @Test
        @DisplayName("★ application/json;q=0 会排除 JSON,即使 */* 还允许")
        void explicitZeroExcludes() throws Exception {
            var written = write(Response.negotiated(person()).json().xml(),
                    "application/json;q=0, */*;q=0.8");

            assertThat(written.response().getContentType())
                    .as("只按 Accept 顺序取第一条会把它又选回 JSON")
                    .startsWith("application/xml");
        }

        @Test
        @DisplayName("*/* → JSON(偏好顺序)")
        void wildcardPrefersJson() throws Exception {
            var written = write(Response.negotiated(person()).json().xml().csv(c -> c.headers("id")),
                    "*/*");

            assertThat(written.response().getContentType()).startsWith("application/json");
        }

        @Test
        @DisplayName("application/vnd.api+json 这类结构化后缀也算 JSON")
        void structuredSuffix() throws Exception {
            var written = write(Response.negotiated(person()).json(), "application/vnd.api+json");

            assertThat(written.response().getContentType()).startsWith("application/json");
        }

        @Test
        @DisplayName("★ 都不接受 → 406,而不是悄悄退回 JSON")
        void notAcceptable() throws Exception {
            var written = write(Response.negotiated(person()).json().xml(), "image/png");

            assertThat(written.response().getStatus()).isEqualTo(406);
            assertThat(written.text())
                    .contains("Not Acceptable")
                    .contains("application/json")
                    .contains("application/xml");
            assertThat(written.response().getHeader("Vary")).contains("Accept");
        }
    }

    // ================= 写出 =================

    @Nested
    @DisplayName("XML / CSV 写出")
    class Rendering {

        @Test
        @DisplayName("非法 XML 元素名用 <entry name=\"...\">,不能生成解析不了的 XML")
        void invalidElementNames() throws Exception {
            var data = new LinkedHashMap<String, Object>();
            data.put("a b", "x");

            var written = write(Response.negotiated(data).xml(), "application/xml");

            assertThat(written.text()).contains("<entry name=\"a b\">x</entry>");
        }

        @Test
        @DisplayName("CSV 的嵌套对象写成它的 JSON 文本")
        void csvNestedValues() throws Exception {
            var data = List.of(Map.of("id", 1, "meta", Map.of("v", 2)));

            var written = write(Response.negotiated(data)
                    .csv(c -> c.headers("id", "meta")), "text/csv");

            assertThat(written.text()).isEqualTo("id,meta\r\n1,\"{\"\"v\"\":2}\"\r\n");
        }

        @Test
        @DisplayName("CSV 需要对象数组(或单个对象)")
        void csvRejectsWrongShape() {
            assertThatThrownBy(() -> write(Response.negotiated("x").csv(c -> c.headers("id")),
                    "text/csv"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CSV 响应需要对象数组");
        }
    }
}
