package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * ★ multipart 上传的**端到端**验收。
 *
 * <p>为什么这个测试必须存在:在它之前,存储层那组安全检查
 * (内容嗅探、随机文件名、可执行类型拦截、路径穿越拦截)
 * **只被单元测试覆盖过** —— 它们接收的是测试手工构造的
 * {@code Upload} 对象,而不是一次真实的 multipart 请求。
 *
 * <p>这中间有一整层没被验证过:Servlet 容器怎么切 part、
 * {@code getSubmittedFileName} 给的是什么、文件怎么进
 * {@code request.all()} 从而被校验规则看到、
 * {@code UploadedFile} 参数怎么绑。
 * 这一类「每个零件都对、接起来不工作」的缺陷是这个项目里
 * 最常见的一种。
 *
 * <p>所以这里走真 HTTP:真 multipart 边界、真 Bearer 令牌、
 * 真磁盘(不是 {@code Storage.fake()} —— fake 掉磁盘会连同
 * 「文件真的落在了 root 里面」一起 fake 掉)。
 */
@SpringBootTest(
        classes = app.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=" + UploadFlowTest.DB_URL,
                "app.env=local",
                "server.error.whitelabel.enabled=false"
        })
class UploadFlowTest {

    static final String DB_URL =
            "jdbc:sqlite:file:uploadflow?mode=memory&cache=shared";

    /** config/filesystems.yml 里 local 磁盘的 root */
    private static final Path LOCAL_ROOT = Path.of("storage/app");
    private static final Path PUBLIC_ROOT = Path.of("storage/app/public");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ================= 测试素材 =================

    /** 一个最小的合法 PNG(魔数 + 一点填充) */
    private static byte[] png(int totalBytes) {
        byte[] out = new byte[Math.max(8, totalBytes)];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, out, 0, magic.length);
        return out;
    }

    private static final byte[] PNG = png(64);
    private static final byte[] PHP_SCRIPT =
            "<?php system($_GET['cmd']); ?>".getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void cleanUpDisk() throws Exception {
        // 真磁盘 → 真垃圾。不清的话下一次跑测试时
        // assertSingleFileIn 之类的断言会看到上一次的残留
        for (Path root : List.of(LOCAL_ROOT.resolve("avatars"),
                LOCAL_ROOT.resolve("photos"),
                PUBLIC_ROOT.resolve("uploads"))) {
            if (!Files.exists(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    // ================= 用例 =================

    @Nested
    @DisplayName("1. 正常的头像上传")
    class HappyPath {

        @Test
        @DisplayName("★ 一次真实的 multipart POST:文件被解析、校验、存下来")
        void uploadsAnAvatar() throws Exception {
            var api = apiClientFor("up1");

            var body = api.upload("/api/avatar", "avatar", "me.png",
                    "image/png", PNG);

            assertThat(body.getStatusCode().is2xxSuccessful())
                    .as("上传应成功,实际 %s / %s",
                            body.getStatusCode(), body.getBody())
                    .isTrue();

            String json = body.getBody();
            String path = jsonString(json, "path");

            assertThat(path)
                    .as("路径应在 avatars/<userId>/ 下")
                    .startsWith("avatars/");
            assertThat(json).contains("\"size\":" + PNG.length);
            assertThat(json)
                    .as("MIME 来自**内容嗅探**")
                    .contains("image/png");

            assertThat(LOCAL_ROOT.resolve(path))
                    .as("文件要真的落在磁盘上 —— 这一条是 fake 磁盘验证不了的")
                    .exists();
            assertThat(Files.readAllBytes(LOCAL_ROOT.resolve(path)))
                    .as("内容要完整,不能被 multipart 解析截断")
                    .isEqualTo(PNG);
        }

        @Test
        @DisplayName("★ 文件名是随机的 —— 客户端给的名字不出现在路径里")
        void fileNameIsRandom() {
            var api = apiClientFor("up2");

            String json = api.upload("/api/avatar", "avatar",
                    "我的头像 v2 (最终版).png", "image/png", PNG).getBody();
            String path = jsonString(json, "path");

            assertThat(path)
                    .as("随机名一次解决三件事:路径穿越、覆盖、文件名里的信息泄露")
                    .doesNotContain("最终版")
                    .doesNotContain(" ")
                    .endsWith(".png");

            assertThat(json)
                    .as("原始名仍然被记下来了 —— 展示用")
                    .contains("最终版");
        }

        @Test
        @DisplayName("★ 扩展名来自内容,不是文件名")
        void extensionComesFromContent() {
            var api = apiClientFor("up3");

            String path = jsonString(api.upload("/api/avatar", "avatar",
                    "no-extension-at-all", "application/octet-stream", PNG)
                    .getBody(), "path");

            assertThat(path)
                    .as("名字里没有扩展名,内容是 PNG —— 存成 .png")
                    .endsWith(".png");
        }

        @Test
        @DisplayName("同一个请求里带普通表单字段也能一起读到")
        void formFieldsAlongsideFiles() {
            var api = apiClientFor("up4");

            var response = api.uploadWith("/api/avatar",
                    Map.of("note", "profile picture"),
                    "avatar", "me.png", "image/png", PNG);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("普通字段不该干扰文件解析:%s", response.getBody())
                    .isTrue();
        }

        @Test
        @DisplayName("★ 签名的临时 URL 真的能换回文件内容 —— 不只是形状对")
        void temporaryUrlWorks() {
            var api = apiClientFor("up5");

            String json = api.upload("/api/avatar", "avatar", "me.png",
                    "image/png", PNG).getBody();
            String signed = jsonString(json, "temporary_url");

            assertThat(signed)
                    .as("私有磁盘上的文件没有公开 URL,只有签名的限时链接")
                    .contains("/storage/temporary")
                    .contains("signature=");

            // ★ 真的去取一次。
            //
            // 之前这条测试只断言 URL 的**形状** —— 而那证明不了
            // 那条路由存在。框架自己注册路由是这一版才有的,
            // 在那之前这个 URL 指向一个 404:
            // 一个「生成了链接但点不开」的功能,
            // 而一个只看字符串的测试完全看不见这件事
            var fetched = fetchSigned(signed);

            assertThat(fetched.getStatusCode().is2xxSuccessful())
                    .as("签名 URL 应该能取到文件,实际 %s", fetched.getStatusCode())
                    .isTrue();
            assertThat(fetched.getBody())
                    .as("★ 二进制要原样回来 —— 一次 byte[]→String→byte[] 的往返"
                            + "会把所有非法 UTF-8 字节换成 U+FFFD,于是 PNG 坏掉")
                    .isEqualTo(PNG);
            assertThat(fetched.getHeaders().getFirst("Content-Type"))
                    .isEqualTo("image/png");
            assertThat(fetched.getHeaders().getFirst("Content-Disposition"))
                    .startsWith("inline;");
            assertThat(fetched.getHeaders().getFirst("X-Content-Type-Options"))
                    .as("即便 Content-Type 说它是图片也不让浏览器嗅探")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("★ 动过的签名 URL → 403,而不是把文件给出去")
        void tamperedTemporaryUrlIsRejected() {
            var api = apiClientFor("up7");

            String signed = jsonString(api.upload("/api/avatar", "avatar",
                    "me.png", "image/png", PNG).getBody(), "temporary_url");

            // 追加一个参数:签名覆盖的是「base + 全部查询参数」,
            // 所以多一个参数签名就不对了。
            //
            // 这一条同时验证了校验用的是**原始查询串** ——
            // 一个只按已知参数重建 URL 的实现会把 tampered 静默丢掉,
            // 于是签名照样通过,而这道边界就没了
            var tampered = fetchSigned(signed + "&tampered=1");
            assertThat(tampered.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // 换一个 path:这才是这道边界真正要防的
            String other = signed.replace("path=avatars", "path=..%2Fetc%2Fpasswd");
            assertThat(fetchSigned(other).getStatusCode())
                    .as("改 path 必须让签名失效 —— 否则这个路由"
                            + "等于一个任意文件读取漏洞")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("没签名就直接访问那条路由 → 403")
        void unsignedAccessIsRejected() {
            var response = rest.exchange(
                    java.net.URI.create("http://localhost:" + port
                            + "/storage/temporary?path=avatars/x.png"),
                    HttpMethod.GET, new HttpEntity<>(new HttpHeaders()),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("同名字段的多个文件")
        void multipleFiles() {
            var api = apiClientFor("up6");

            var response = api.uploadMany("/api/avatar/multi", "photos",
                    List.of("a.png", "b.png", "c.png"), PNG);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("%s", response.getBody()).isTrue();
            assertThat(response.getBody()).contains("\"count\":3");
        }
    }

    @Nested
    @DisplayName("2. 安全检查 —— 走真实 HTTP 路径")
    class Security {

        @Test
        @DisplayName("★ 伪装成 .png 的 PHP 脚本被 image 规则拒掉(422)")
        void disguisedScriptIsRejected() {
            var api = apiClientFor("sec1");

            var response = api.upload("/api/avatar", "avatar", "avatar.png",
                    "image/png", PHP_SCRIPT);

            assertThat(response.getStatusCode())
                    .as("客户端把名字和 Content-Type 都伪装成了 PNG —— "
                            + "拦住它的只能是内容嗅探")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).contains("avatar");

            assertThat(LOCAL_ROOT.resolve("avatars"))
                    .as("★ 被拒的文件不该落盘 —— 「先存下来再检查」"
                            + "会让那一刻磁盘上真的有一个 PHP 脚本")
                    .satisfiesAnyOf(
                            p -> assertThat(p).doesNotExist(),
                            p -> assertThat(Files.list(p)).isEmpty());
        }

        @Test
        @DisplayName("★ 文件名里的 ../ 不会逃出 root")
        void pathTraversalInFileName() throws Exception {
            var api = apiClientFor("sec2");

            String path = jsonString(api.upload("/api/avatar", "avatar",
                    "../../../../etc/passwd.png", "image/png", PNG).getBody(),
                    "path");

            assertThat(path).doesNotContain("..");
            assertThat(LOCAL_ROOT.resolve(path).toRealPath())
                    .as("随机命名让这次尝试连一个「原始名」都没留下")
                    .startsWith(LOCAL_ROOT.toRealPath());
        }

        @Test
        @DisplayName("★ 往对外可访问的磁盘写 .php 被存储层拒掉 —— 即便校验漏了")
        void executableTypesRejectedOnPublicDisk() {
            var api = apiClientFor("sec3");

            // 这条路由故意没有 image 校验 —— 要验证的是第二道闸
            var response = api.upload("/api/avatar/public", "file", "shell.php",
                    "text/plain", PHP_SCRIPT);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("存储层应该拒绝,实际响应:%s", response.getBody())
                    .isFalse();
        }

        @Test
        @DisplayName("超过 uploads.max-file-size 的请求被容器拒掉(413)")
        void oversizedUploadIsRejected() {
            var api = apiClientFor("sec4");

            // config/uploads.yml:max-file-size: 2MB
            var response = api.upload("/api/avatar", "avatar", "huge.png",
                    "image/png", png(3 * 1024 * 1024));

            assertThat(response.getStatusCode())
                    .as("这一道闸在容器**读流的过程中**执行 —— "
                            + "不会先把 3 MB 读进内存再报错。实际:%s",
                            response.getStatusCode())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("★ 1.5 MB 的文件通过 —— 证明 config/uploads.yml 真的生效了")
        void jaravelConfigOverridesSpringDefault() {
            var api = apiClientFor("sec6");

            // Spring Boot 的 MultipartConfigElement 默认是 **1 MB**。
            //
            // 这一条是上一条(3 MB → 413)的对照组:单有 413 证明不了
            // 任何事 —— Spring 的 1 MB 默认值一样会拒掉 3 MB。
            // 要证明读的是 config/uploads.yml(2 MB),
            // 必须有一个夹在两个限制之间的尺寸
            var response = api.upload("/api/avatar", "avatar", "big.png",
                    "image/png", png(1536 * 1024));

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("1.5 MB 在 Spring 的默认值下会 413,"
                            + "在 uploads.max-file-size=2MB 下应该通过。实际:%s / %s",
                            response.getStatusCode(), response.getBody())
                    .isTrue();
        }

        @Test
        @DisplayName("没带文件 → 400,带着能排查的消息")
        void missingFileIs400() {
            var api = apiClientFor("sec5");

            var response = api.uploadWith("/api/avatar",
                    Map.of("note", "忘了选文件"), null, null, null, null);

            assertThat(response.getStatusCode())
                    .as("一个 null 参数会在控制器第一行变成 NPE 500 —— "
                            + "那把客户端错误报成了服务端错误")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .as("消息里要有能动手的线索")
                    .contains("multipart/form-data");
        }

        @Test
        @DisplayName("未认证的上传被挡在 auth 中间件外")
        void unauthenticatedUploadIsRejected() {
            var response = new ApiClient("1|not-a-real-token")
                    .upload("/api/avatar", "avatar", "me.png", "image/png", PNG);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ================= 辅助 =================

    /**
     * 取一个签名 URL。
     *
     * <p>⚠ 要把 URL 里的 **host 换成测试的随机端口** —— 签名是按
     * {@code app.url}(配置里的 localhost:8002)签的,而服务跑在随机端口上。
     *
     * <p>这正好验证了那条路由**不按请求的完整 URL 校验签名**:
     * 按完整 URL 校验的话,这里每一次都会「签名无效」——
     * 而真实部署里反向代理改写 Host 是同一个问题。
     * 理由写在 {@code StorageTemporaryUrlRoute.serve} 的注释里。
     */
    private ResponseEntity<byte[]> fetchSigned(String signedUrl) {
        int pathStart = signedUrl.indexOf('/', signedUrl.indexOf("://") + 3);
        String local = "http://localhost:" + port + signedUrl.substring(pathStart);

        // ★ 必须传 URI 而不是 String。
        //
        // RestTemplate 把 String 当成 **URI 模板**并再编码一次 ——
        // 于是签名 URL 里的 path=avatars%2F1%2Fx.png 变成
        // path=avatars%252F1%252Fx.png,服务端解码出来是
        // "avatars%2F1%2Fx.png" 而不是 "avatars/1/x.png",签名对不上。
        //
        // 第一次写成 String 时得到的是 403 —— 而那个失败看起来
        // 完全像「框架的签名校验坏了」。记在这里是因为
        // 这个坑会在任何用 RestTemplate 测签名 URL 的地方重现
        return rest.exchange(java.net.URI.create(local), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);
    }

    /** 从一段 JSON 里抠一个字符串字段 —— 不引 Jackson,断言更直白 */
    private static String jsonString(String json, String field) {
        if (json == null) return "";
        String key = "\"" + field + "\":\"";
        int from = json.indexOf(key);
        if (from < 0) return "";
        from += key.length();

        var out = new StringBuilder();
        for (int i = from; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') break;
            if (ch == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    /** 注册一个用户、验证邮箱、换一个 API 令牌 */
    private ApiClient apiClientFor(String tag) {
        var browser = new Browser();
        browser.start();

        var registered = browser.post("/register", Map.of(
                "name", "上传用户",
                "email", tag + "@example.com",
                "password", "correct-horse",
                "password_confirmation", "correct-horse"));
        assertThat(registered.getStatusCode())
                .as("注册失败:%s", registered.getBody())
                .isEqualTo(HttpStatus.FOUND);

        // /api-token 挂了 auth 但不要求 verified,所以不用先验证邮箱
        browser.get("/dashboard");
        String body = browser.post("/api-token", Map.of("name", tag)).getBody();

        String token = jsonString(body, "token");
        assertThat(token).as("应签发 API 令牌,实际:%s", body).isNotBlank();
        return new ApiClient(token);
    }

    /** 会话 + CSRF 的「浏览器」—— 只用来换令牌 */
    private final class Browser {
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private String csrf;

        void start() {
            get("/");
            assertThat(csrf).as("首页应下发 CSRF 令牌").isNotBlank();
        }

        String get(String path) {
            var response = exchange(HttpMethod.GET, path, null, null);
            absorbCsrf(response.getBody());
            return response.getBody();
        }

        ResponseEntity<String> post(String path, Map<String, String> form) {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            form.forEach(body::add);
            if (csrf != null) body.add("_token", csrf);

            var response = exchange(HttpMethod.POST, path, body,
                    MediaType.APPLICATION_FORM_URLENCODED);
            absorbCsrf(response.getBody());
            return response;
        }

        private void absorbCsrf(String body) {
            String fresh = jsonString(body, "csrf_token");
            if (!fresh.isBlank()) csrf = fresh;
        }

        private ResponseEntity<String> exchange(HttpMethod method, String path,
                                                Object body, MediaType contentType) {
            var headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (contentType != null) headers.setContentType(contentType);
            if (!cookies.isEmpty()) {
                var jar = new ArrayList<String>();
                cookies.forEach((k, v) -> jar.add(k + "=" + v));
                headers.add(HttpHeaders.COOKIE, String.join("; ", jar));
            }
            if (csrf != null) headers.add("X-CSRF-TOKEN", csrf);

            var response = rest.exchange("http://localhost:" + port + path,
                    method, new HttpEntity<>(body, headers), String.class);

            var setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies != null) {
                for (String raw : setCookies) {
                    String pair = raw.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq > 0) cookies.put(pair.substring(0, eq),
                            pair.substring(eq + 1));
                }
            }
            return response;
        }
    }

    /** 只带 Bearer 令牌的无状态客户端 */
    private final class ApiClient {

        private final String token;

        ApiClient(String token) {
            this.token = token;
        }

        ResponseEntity<String> upload(String path, String field, String fileName,
                                      String contentType, byte[] content) {
            return uploadWith(path, Map.of(), field, fileName, contentType, content);
        }

        ResponseEntity<String> uploadWith(String path, Map<String, String> fields,
                                          String field, String fileName,
                                          String contentType, byte[] content) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            fields.forEach(body::add);
            if (field != null) {
                body.add(field, filePart(fileName, contentType, content));
            }
            return send(path, body);
        }

        ResponseEntity<String> uploadMany(String path, String field,
                                          List<String> names, byte[] content) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            for (String name : names) {
                body.add(field, filePart(name, "image/png", content));
            }
            return send(path, body);
        }

        /**
         * 一个 part。
         *
         * <p>用 {@code HttpEntity<ByteArrayResource>} 而不是裸的
         * {@code byte[]}:后者不会带 {@code filename} 参数,
         * 于是容器把它当成一个**普通表单字段** ——
         * 而「有没有 filename」正是适配器区分文件与字段的依据,
         * 所以那样写的测试会「成功地什么都没测」。
         */
        private HttpEntity<ByteArrayResource> filePart(String fileName,
                                                       String contentType,
                                                       byte[] content) {
            var resource = new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            var headers = new HttpHeaders();
            if (contentType != null) {
                headers.setContentType(MediaType.parseMediaType(contentType));
            }
            return new HttpEntity<>(resource, headers);
        }

        private ResponseEntity<String> send(String path,
                                            MultiValueMap<String, Object> body) {
            var headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            return rest.exchange("http://localhost:" + port + path,
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        }
    }
}
