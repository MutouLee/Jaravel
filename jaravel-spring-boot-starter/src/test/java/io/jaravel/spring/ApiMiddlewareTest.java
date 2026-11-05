package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.MapConfigRepository;

/**
 * CORS 与请求 ID(docs/12 §6.3 / §6.4)。
 *
 * <p>两者都是 Servlet Filter 而不是中间件 —— 那条分层写在
 * {@code Middleware} 的类注释里,理由是它们必须覆盖**没匹配上路由**的请求
 * (CORS 预检用 OPTIONS,而应用只注册了 GET/POST;请求 ID 要覆盖 404)。
 */
class ApiMiddlewareTest {

    private static CorsFilter cors(String yaml) {
        return new CorsFilter(new MapConfigRepository(ConfigLoader.parse(yaml, "test")));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    // ================= CORS =================

    @Nested
    @DisplayName("CORS")
    class Cors {

        private static final String CONFIG = """
                cors:
                  paths: [ "api/*" ]
                  allowed-origins: [ "https://app.example.com" ]
                  allowed-origins-patterns: [ "https://*.trusted.com" ]
                  allowed-methods: [ "GET", "POST" ]
                  allowed-headers: [ "Content-Type", "Authorization" ]
                  exposed-headers: [ "X-RateLimit-Remaining" ]
                  max-age: 3600
                  supports-credentials: true
                """;

        @Test
        @DisplayName("★ 没有 cors.paths 时整个 filter 不启用 —— CORS 是放宽同源策略")
        void disabledByDefault() {
            assertThat(cors("app:\n  name: x\n").enabled())
                    .as("""
                            一个默认带着 Allow-Origin: * 的框架,会让每个不知道
                            这件事的项目把 API 对全世界打开 ——
                            那是安全默认值问题,不是便利性问题。""")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 预检在 filter 里就回答掉(204),不进路由")
        void preflightIsAnsweredEarly() throws Exception {
            var request = request("OPTIONS", "/api/posts");
            request.addHeader("Origin", "https://app.example.com");
            request.addHeader("Access-Control-Request-Method", "POST");
            request.addHeader("Access-Control-Request-Headers", "Content-Type");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            cors(CONFIG).doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(204);
            assertThat(chain.getRequest())
                    .as("""
                            预检不该往下走:应用通常只注册了 GET/POST,
                            OPTIONS 会撞上 405(v0.5 刚实现的那条路径),
                            于是浏览器把整个跨域请求判为失败。
                            而且预检不带 Cookie 也不带 Authorization,
                            走完整管道只会在某个中间件里被拒掉。""")
                    .isNull();
            assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
            assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
        }

        @Test
        @DisplayName("★ 回显具体的 Origin 而不是 *,并带上 Vary")
        void echoesTheOrigin() throws Exception {
            var request = request("GET", "/api/posts");
            request.addHeader("Origin", "https://app.example.com");
            var response = new MockHttpServletResponse();

            cors(CONFIG).doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader("Access-Control-Allow-Origin"))
                    .as("带凭据的请求下浏览器**拒绝** *,所以必须回显具体值")
                    .isEqualTo("https://app.example.com");
            assertThat(response.getHeaders("Vary"))
                    .as("""
                            没有 Vary 的话 CDN 可能把第一个来源拿到的响应
                            (含那个 Allow-Origin)缓存起来发给别的来源 ——
                            那时浏览器会拒绝,表现为「有时跨域失败」。""")
                    .contains("Origin");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(response.getHeader("Access-Control-Expose-Headers"))
                    .contains("X-RateLimit-Remaining");
        }

        @Test
        @DisplayName("不在白名单里的来源:放行请求但不加任何 CORS 头(由浏览器拒绝)")
        void unknownOriginGetsNoHeaders() throws Exception {
            var request = request("GET", "/api/posts");
            request.addHeader("Origin", "https://evil.com");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            cors(CONFIG).doFilter(request, response, chain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(chain.getRequest())
                    .as("""
                            不加头、但照常处理:CORS 是**浏览器**的策略。
                            在服务端直接拒掉会让 curl / 服务间调用也挂掉,
                            而它们根本不受同源策略约束。""")
                    .isNotNull();
        }

        @Test
        @DisplayName("通配来源:*.trusted.com 匹配")
        void originPattern() throws Exception {
            var request = request("GET", "/api/posts");
            request.addHeader("Origin", "https://shop.trusted.com");
            var response = new MockHttpServletResponse();

            cors(CONFIG).doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader("Access-Control-Allow-Origin"))
                    .isEqualTo("https://shop.trusted.com");
        }

        @Test
        @DisplayName("★ 通配转正则必须先转义 —— 否则 evil-trusted.com 也能通过")
        void globEscapesRegexMetaCharacters() {
            var pattern = CorsFilter.globToPattern("https://*.trusted.com");

            assertThat(pattern.matcher("https://shop.trusted.com").matches()).isTrue();
            assertThat(pattern.matcher("https://evil-trusted.com").matches())
                    .as("""
                            直接把字符串塞进 Pattern 的话,那个点会匹配任意字符,
                            于是 https://evil-trusted.com 也算通过 ——
                            一个真实的绕过,而它看起来只是「正则写松了」。""")
                    .isFalse();
        }

        @Test
        @DisplayName("路径不在 cors.paths 里就完全不管")
        void pathNotCovered() throws Exception {
            var request = request("GET", "/admin/dashboard");
            request.addHeader("Origin", "https://app.example.com");
            var response = new MockHttpServletResponse();

            cors(CONFIG).doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }
    }

    // ================= 请求 ID =================

    @Nested
    @DisplayName("请求 ID")
    class RequestId {

        @Test
        @DisplayName("没有上游 id 时生成一个,并写进响应头")
        void generatesWhenAbsent() throws Exception {
            var request = request("GET", "/x");
            var response = new MockHttpServletResponse();

            new RequestIdFilter().doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
            assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isNotNull();
        }

        @Test
        @DisplayName("★ 沿用上游传来的 id —— 每跳一换的话它只能定位一跳")
        void reusesUpstreamId() throws Exception {
            var request = request("GET", "/x");
            request.addHeader(RequestIdFilter.HEADER, "gateway-abc-123");
            var response = new MockHttpServletResponse();

            new RequestIdFilter().doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(RequestIdFilter.HEADER))
                    .as("""
                            排查的问题恰恰是「用户那次下单在哪一环挂了」——
                            每一跳都生成新 id 的话,那个 id 只能定位一跳。""")
                    .isEqualTo("gateway-abc-123");
        }

        @Test
        @DisplayName("★ 带换行的上游 id 一律丢弃 —— 它会进响应头与日志")
        void rejectsHeaderInjection() {
            assertThat(RequestIdFilter.sanitize("abc\r\nX-Admin: true"))
                    .as("""
                            不校验的话,一个带换行的 X-Request-Id 可以往响应里
                            注入额外的头(HTTP 响应拆分),或者往日志里注入伪造的行。""")
                    .isNull();
            assertThat(RequestIdFilter.sanitize("abc\ndef")).isNull();
            assertThat(RequestIdFilter.sanitize("  ")).isNull();
            assertThat(RequestIdFilter.sanitize("a".repeat(200)))
                    .as("过长的值同样丢掉 —— 它会被打进每一条日志")
                    .isNull();
            assertThat(RequestIdFilter.sanitize("01JB3K-ok_id.1")).isEqualTo("01JB3K-ok_id.1");
        }

        @Test
        @DisplayName("★ 请求结束后 MDC 必须清掉 —— 否则下一个请求的日志会串台")
        void mdcIsCleared() throws Exception {
            new RequestIdFilter().doFilter(request("GET", "/x"),
                    new MockHttpServletResponse(), new MockFilterChain());

            assertThat(org.slf4j.MDC.get(RequestIdFilter.MDC_KEY))
                    .as("""
                            线程是复用的(线程池、虚拟线程的载体也一样)。
                            不清的话下一个请求的日志会带上上一个请求的 id ——
                            而那种串台比没有 id 更糟:它把排查引向完全无关的另一次请求。""")
                    .isNull();
        }
    }
}
