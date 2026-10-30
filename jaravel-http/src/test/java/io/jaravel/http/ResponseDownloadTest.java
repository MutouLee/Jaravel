package io.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Response.bytes / download / inline,以及 Content-Disposition 那道安全边界 */
class ResponseDownloadTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static String disposition(Response response) {
        return response.headers().get("Content-Disposition").get(0);
    }

    @Nested
    @DisplayName("正文")
    class Payload {

        @Test
        @DisplayName("★ 字节原样保留 —— 不经过 String")
        void bytesSurviveIntact() {
            var response = Response.bytes(PNG);

            assertThat(response.body())
                    .as("一个「字节 → String → 字节」的往返会把所有"
                            + "非法 UTF-8 字节换成 U+FFFD,于是 PNG 坏掉")
                    .isInstanceOf(Response.Body.Bytes.class);

            var body = (Response.Body.Bytes) response.body();
            assertThat(body.content()).isEqualTo(PNG);
        }

        @Test
        @DisplayName("null 内容变成空数组,不是 NPE")
        void nullContent() {
            assertThat(((Response.Body.Bytes) Response.bytes(null).body()).content())
                    .isEmpty();
        }

        @Test
        @DisplayName("download 默认 octet-stream,inline 用给的类型")
        void contentTypes() {
            assertThat(Response.download(PNG, "a.png").contentType())
                    .isEqualTo("application/octet-stream");
            assertThat(Response.download(PNG, "a.png", "image/png").contentType())
                    .isEqualTo("image/png");
            assertThat(Response.inline(PNG, "a.png", "image/png").contentType())
                    .isEqualTo("image/png");
        }

        @Test
        @DisplayName("attachment 与 inline 的区别只在 disposition 的类型")
        void dispositionType() {
            assertThat(disposition(Response.download(PNG, "a.png")))
                    .startsWith("attachment;");
            assertThat(disposition(Response.inline(PNG, "a.png", "image/png")))
                    .startsWith("inline;");
        }
    }

    @Nested
    @DisplayName("★ Content-Disposition 是一道安全边界")
    class HeaderSafety {

        @Test
        @DisplayName("★ CR / LF 被去掉 —— 否则是响应头注入")
        void newlinesAreStripped() {
            var response = Response.download(PNG,
                    "report.pdf\r\nSet-Cookie: admin=1");

            assertThat(disposition(response))
                    .as("文件名常常来自用户(上传时的原始名、带用户名的导出)。"
                            + "一个带 \\r\\n 的名字能注入任意响应头,包括 Set-Cookie")
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain("Set-Cookie: admin=1\r");
        }

        @Test
        @DisplayName("★ 引号与反斜杠被去掉 —— 否则能提前关掉 filename=\"...\"")
        void quotesAreStripped() {
            var response = Response.download(PNG, "a\".pdf\"; x=\"y");

            String header = disposition(response);
            // filename="..." 里只应该有一对引号,filename* 那份没有引号
            long quotes = header.chars().filter(c -> c == '"').count();

            assertThat(quotes)
                    .as("多出来的引号意味着攻击者能注入 disposition 的其它参数。"
                            + "实际的头:%s", header)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("★ 空字节与控制字符被去掉")
        void controlCharsAreStripped() {
            assertThat(disposition(Response.download(PNG, "a\u0000b\u0007.pdf")))
                    .contains("ab.pdf");
        }

        @Test
        @DisplayName("★ 只取路径的最后一段")
        void onlyTheLastSegment() {
            assertThat(disposition(Response.download(PNG, "../../etc/passwd")))
                    .as("内容已经在手上了,所以这不是任意文件读取 —— "
                            + "但这个名字会出现在用户的下载目录里")
                    .contains("filename=\"passwd\"")
                    .doesNotContain("..");

            assertThat(disposition(Response.download(PNG, "C:\\Windows\\win.ini")))
                    .as("反斜杠也要切 —— 一个 Windows 客户端给的名字长这样")
                    .contains("filename=\"win.ini\"");
        }

        @Test
        @DisplayName("空的 / 全是非法字符的文件名退回 download")
        void emptyNameFallsBack() {
            assertThat(disposition(Response.download(PNG, "")))
                    .contains("filename=\"download\"");
            assertThat(disposition(Response.download(PNG, null)))
                    .contains("filename=\"download\"");
            assertThat(disposition(Response.download(PNG, "\r\n\"")))
                    .as("filename=\"\" 在部分客户端上会让下载失败")
                    .contains("filename=\"download\"");
        }
    }

    @Nested
    @DisplayName("非 ASCII 文件名")
    class Internationalized {

        @Test
        @DisplayName("★ 给两份:ASCII 兜底 + RFC 5987 的 filename*")
        void twoForms() {
            String header = disposition(Response.download(PNG, "发票 2026.pdf"));

            assertThat(header)
                    .as("filename= 只能可靠地带 ASCII —— 中文会乱码或被截断")
                    .contains("filename=\"__ 2026.pdf\"")
                    .as("filename* 老浏览器不认,所以两份都要给")
                    .contains("filename*=UTF-8''");

            assertThat(header)
                    .as("★ 空格要编成 %20 而不是 + —— URLEncoder 是给表单用的,"
                            + "RFC 5987 要求 %20")
                    .doesNotContain("+")
                    .contains("%20");
        }

        @Test
        @DisplayName("非 ASCII 换成 _ 而不是删掉")
        void nonAsciiBecomesUnderscore() {
            assertThat(disposition(Response.download(PNG, "发票.pdf")))
                    .as("全中文的名字删光了会变成 filename=\"\"")
                    .contains("filename=\"__.pdf\"");
        }

        @Test
        @DisplayName("filename* 能被解回原名")
        void roundTrips() {
            String header = disposition(Response.download(PNG, "发票 2026.pdf"));
            String encoded = header.substring(header.indexOf("UTF-8''") + 7);

            assertThat(java.net.URLDecoder.decode(encoded.replace("%20", "+"),
                    StandardCharsets.UTF_8))
                    .isEqualTo("发票 2026.pdf");
        }
    }
}
