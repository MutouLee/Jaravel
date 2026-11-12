package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.testing.FeatureTest;

/**
 * 这个示例**能不能起来**。
 *
 * <h2>★ 为什么补这条测试</h2>
 * <p>因为它没有任何测试的那段时间里,它坏了,而且没人知道。
 *
 * <p>具体是这样:auth 模块(v0.3)把 {@code app.key} 做成了
 * **启动即校验**的饿加载 bean —— 那个设计是对的(缺密钥的问题应当在启动时
 * 暴露,而不是等到第一个加密 cookie 出现)。但这个示例写在 v0.2,
 * 配置里没有那一行,于是从那以后它**起不到端口**,报错是「app.key 未设置」。
 *
 * <p>auth 与 queue 两个示例没出这个问题 —— 它们各自带着真实的验收测试。
 * 区别只在这里。
 *
 * <p>所以这条测试要钉住的不是某个功能,而是**最低限度的事实**:
 * 真的 Spring 上下文能装配起来,真的路由能回一个响应。
 * 这两件事一错,这个示例对读者就是误导。
 */
class ApplicationSmokeTest extends FeatureTest {

    @Test
    @DisplayName("★ 容器装配得起来,首页回 200 —— 一个起不来的示例是误导")
    void theApplicationBoots() {
        var response = get("/").assertOk();

        assertThat(response.content())
                .as("首页是文章列表,卡片是类组件 <x-post-card> 渲染的")
                .isNotBlank()
                .contains("<article>")
                .contains("次浏览");
    }

    @Test
    @DisplayName("JSON 接口也通 —— 它与页面走的是不同的响应路径")
    void theApiResponds() {
        get("/api/posts").assertOk();
    }

    @Test
    @DisplayName("★ 分页信封完整:prev/next 即便是 null 也要有那个键")
    void paginationEnvelopeIsComplete() throws Exception {
        var response = get("/api/posts/paged?page=1").assertOk();

        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.content());

        assertThat(json.has("data")).isTrue();
        assertThat(json.get("links").has("prev"))
                .as("""
                        第一页的 prev 是 null —— 而这个应用的 ObjectMapper 配了
                        「不输出 null 字段」(API 响应的常见设置)。
                        第一版的序列化器直接 writeObject(map),于是那个键**整个消失**。
                        前端分页组件靠 links.prev === null 判断能不能往前翻,
                        键不存在时拿到 undefined —— 在 JS 里也是假值,
                        所以多数组件还能用:这正是最糟的那种 bug,
                        它不报错,只在某些组件上表现为按钮状态不对。""")
                .isTrue();
        assertThat(json.get("links").get("prev").isNull()).isTrue();
        assertThat(json.get("links").get("next").asText()).contains("page=2");

        var meta = json.get("meta");
        assertThat(meta.get("current_page").asInt()).isEqualTo(1);
        assertThat(meta.get("per_page").asInt()).isEqualTo(5);
        assertThat(meta.get("path").asText())
                .as("path 要是真的请求 URL —— resolver 装上了才有")
                .contains("/api/posts/paged");
    }

    @Test
    @DisplayName("★ 资源层绝不触发懒加载 —— 列表接口里**没有** comment_count")
    void listEndpointOmitsUnloadedRelations() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/resources?page=1").assertOk().content());

        var first = json.get("data").get(0);
        assertThat(first.has("author"))
                .as("这个接口预加载了 author")
                .isTrue();
        assertThat(first.has("comment_count"))
                .as("""
                        而它没有预加载 comments,所以资源**跳过**那个字段 ——
                        而不是去查一次库。一个列表接口返回 50 条、资源里碰一下
                        没预加载的关联就是 51 条查询,而那个 bug 在开发期的
                        三条测试数据上完全看不出来。

                        字段缺失是一个**可见的**提示:前端拿不到
                        comment_count 就知道这个接口没预加载它。""")
                .isFalse();
        assertThat(first.get("author").has("data"))
                .as("嵌套的资源不带 data 包裹 —— 包裹只在最外层加一次")
                .isFalse();
    }

    @Test
    @DisplayName("★ 单个资源在根位置:包一层 data,并带上 with 的 meta")
    void singleResourceIsWrappedAtRoot() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/1").assertOk().content());

        assertThat(json.has("data")).isTrue();
        assertThat(json.get("meta").get("version").asText()).isEqualTo("1.0");
        assertThat(json.get("data").has("comment_count"))
                .as("这个接口**预加载了** comments —— 与上面那条列表接口正好相反")
                .isTrue();
    }

    @Test
    @DisplayName("★ ?page=2 真的翻页 —— 页码由 starter 装的 resolver 读")
    void pageParameterIsHonoured() throws Exception {
        var first = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/paged?page=1").assertOk().content());
        var second = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/paged?page=2").assertOk().content());

        assertThat(second.get("meta").get("current_page").asInt()).isEqualTo(2);
        assertThat(second.get("links").get("next").isNull())
                .as("第二页是最后一页(共 7 条、每页 5 条)")
                .isTrue();
        assertThat(first.get("data").get(0).get("id").asInt())
                .as("两页的内容不该一样 —— 一样就说明 offset 没生效")
                .isNotEqualTo(second.get("data").get(0).get("id").asInt());
    }

    @Test
    @DisplayName("★ 路径存在但方法不对 → 405 + Allow,而不是 404")
    void wrongMethodGives405WithAllowHeader() {
        // GET /api/posts 存在;POST 不存在
        var response = post("/api/posts", java.util.Map.of());

        assertThat(response.status())
                .as("""
                        404 说的是「这个地址不存在」,405 说的是「这个地址存在,
                        但你不能用 POST 访问它」—— 对调用方是两件完全不同的事:
                        前者去查 URL 拼错了没,后者去查自己用的动词。

                        这条路径此前不存在:未命中就落进 Spring 的默认处理、
                        最终是 404。而 RouteCollection.allowedMethods 这个方法
                        就是为 405 写的,一直只有单元测试在用。""")
                .isEqualTo(405);
        assertThat(response.header("Allow"))
                .as("RFC 9110 要求 405 必须带 Allow —— 它直接告诉调用方该用什么动词")
                .isPresent();
        assertThat(response.header("Allow").orElseThrow()).contains("GET");
    }

    @Test
    @DisplayName("★ 条件请求:第二次带 If-None-Match 得到 304,而且没有正文")
    void conditionalRequestReturns304() {
        var first = get("/api/posts/cached").assertOk();
        String etag = first.header("ETag").orElseThrow();

        assertThat(etag)
                .as("""
                        弱 ETag(W/ 前缀)是诚实的:强 ETag 要求字节级相同,
                        而这里的字节来自 JSON 序列化 —— 同一份数据在不同的
                        ObjectMapper 配置下字节不同。""")
                .startsWith("W/");

        var second = withHeader("If-None-Match", etag).get("/api/posts/cached");

        assertThat(second.status()).isEqualTo(304);
        assertThat(second.content())
                .as("304 **必须**没有正文 —— 带了体的 304 会让一些客户端挂住")
                .isEmpty();
        assertThat(second.header("ETag")).contains(etag);
        assertThat(second.header("Cache-Control"))
                .as("缓存指令要留着 —— 客户端下一次还要用")
                .isPresent();
    }

    @Test
    @DisplayName("★ 每个响应都带 X-Request-Id,而上游传来的会被沿用")
    void everyResponseCarriesARequestId() {
        assertThat(get("/").assertOk().header("X-Request-Id"))
                .as("没有它的话,一张「Server Error.」的截图只能靠时间戳去对日志")
                .isPresent();

        assertThat(withHeader("X-Request-Id", "gateway-abc").get("/").header("X-Request-Id"))
                .as("每一跳都换 id 的话,那个 id 只能定位一跳")
                .contains("gateway-abc");
    }

    @Test
    @DisplayName("★ CORS 预检在 filter 里就回答掉 —— 它不该撞上 405")
    void corsPreflightDoesNotHitMethodNotAllowed() {
        var response = withHeader("Origin", "https://app.example.com")
                .withHeader("Access-Control-Request-Method", "POST")
                .options("/api/posts");

        assertThat(response.status())
                .as("""
                        /api/posts 只注册了 GET。预检走路由匹配的话会得到 405
                        (v0.5 刚实现的那条路径),于是浏览器把整个跨域请求判为失败。
                        这就是 Middleware 的类注释里那条分层的具体后果。""")
                .isEqualTo(204);
        assertThat(response.header("Access-Control-Allow-Origin"))
                .contains("https://app.example.com");
    }

    @Test
    @DisplayName("★ 内容协商:同一个路由按 Accept 返回 JSON / XML / CSV")
    void contentNegotiation() {
        var json = asJson().get("/api/posts/formats").assertOk();
        assertThat(json.header("Content-Type").orElseThrow()).contains("application/json");
        assertThat(json.header("Vary").orElseThrow())
                .as("响应随 Accept 变化,缓存必须知道")
                .contains("Accept");

        var xml = withHeader("Accept", "application/xml")
                .get("/api/posts/formats").assertOk();
        assertThat(xml.header("Content-Type").orElseThrow()).contains("application/xml");
        assertThat(xml.content()).startsWith("<?xml").contains("<response>").contains("<title>");

        var csv = withHeader("Accept", "text/csv")
                .get("/api/posts/formats").assertOk();
        assertThat(csv.header("Content-Type").orElseThrow()).contains("text/csv");
        assertThat(csv.content())
                .as("列由控制器显式给出,顺序稳定")
                .startsWith("id,title,slug\r\n");
    }

    @Test
    @DisplayName("★ 客户端只接受一个没声明的格式 → 406,而不是悄悄退回 JSON")
    void unacceptableFormatIs406() {
        var response = withHeader("Accept", "text/html")
                .get("/api/posts/formats");

        assertThat(response.status()).isEqualTo(406);
        assertThat(response.content()).contains("Not Acceptable");
    }

    @Test
    @DisplayName("★ 游标分页:带着 next_cursor 回来能翻到不同的一页")
    void cursorPaginationWalks() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(get("/api/posts/cursor").assertOk().content());

        assertThat(first.get("next_cursor").asText()).isNotBlank();
        assertThat(first.get("prev_cursor").isNull())
                .as("""
                        第一页没有上一页。而这个键**必须还在**(值是 null)——
                        客户端靠 prev_cursor === null 判断「到头了」,
                        而应用的「不输出 null 字段」策略会让那个键整个消失。""")
                .isTrue();

        var second = mapper.readTree(
                get("/api/posts/cursor?cursor=" + first.get("next_cursor").asText())
                        .assertOk().content());

        assertThat(second.get("data").get(0).get("id").asInt())
                .as("翻页了就该是另一批数据")
                .isNotEqualTo(first.get("data").get(0).get("id").asInt());
        assertThat(second.get("prev_cursor").asText())
                .as("第二页能往回翻")
                .isNotBlank();
        assertThat(first.has("total"))
                .as("""
                        游标分页**不知道**总数(那要数过前面所有行,正是它要避免的)。
                        输出一个编出来的值会让前端渲染出点不动的页码。""")
                .isFalse();
    }
}
