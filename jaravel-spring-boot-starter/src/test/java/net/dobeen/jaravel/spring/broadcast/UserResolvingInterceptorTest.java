package net.dobeen.jaravel.spring.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;

import net.dobeen.jaravel.auth.AuthManager;
import net.dobeen.jaravel.auth.UserProvider;
import net.dobeen.jaravel.auth.guard.SessionGuard;
import net.dobeen.jaravel.session.SessionConfig;
import net.dobeen.jaravel.session.store.MemoryStore;

/**
 * 握手期的用户解析(docs/19 §6.4)。
 */
class UserResolvingInterceptorTest {

    record User(Long id, String name) implements net.dobeen.jaravel.auth.Authenticatable {
        @Override public Object getAuthIdentifier() {
            return id;
        }

        @Override public java.util.Optional<String> getAuthPassword() {
            return java.util.Optional.empty();
        }
    }

    private UserProvider provider;
    private SessionConfig sessionConfig;
    private MemoryStore sessionStore;
    private UserResolvingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionConfig = SessionConfig.defaults().withCookieName("jaravel_session");
        sessionStore = new MemoryStore();

        provider = new UserProvider() {
            @Override public String name() {
                return "users";
            }

            @Override public java.util.Optional<net.dobeen.jaravel.auth.Authenticatable> retrieveById(Object id) {
                return java.util.Optional.of(new User((Long) id, "张三"));
            }

            @Override public java.util.Optional<net.dobeen.jaravel.auth.Authenticatable> retrieveByToken(Object id, String token) {
                // cookie 里的 id 是字符串 —— 真实 provider 负责转类型,替身照做
                return "valid-token".equals(token)
                        ? java.util.Optional.of(new User(Long.parseLong(String.valueOf(id)), "张三"))
                        : java.util.Optional.empty();
            }

            @Override public void updateRememberToken(net.dobeen.jaravel.auth.Authenticatable user, String token) {
            }

            @Override public java.util.Optional<net.dobeen.jaravel.auth.Authenticatable> retrieveByCredentials(Map<String, Object> credentials) {
                return java.util.Optional.empty();
            }

            @Override public boolean validateCredentials(net.dobeen.jaravel.auth.Authenticatable user, Map<String, Object> credentials) {
                return false;
            }
        };

        var authManager = new AuthManager()
                .registerProvider(provider)
                .registerGuard("web", "users", p -> new SessionGuard("web", p));

        interceptor = new UserResolvingInterceptor(
                sessionStore, sessionConfig, authManager, "web", "jaravel");
    }

    /** 一个带 Cookie 头与路径的握手请求替身 */
    private ServerHttpRequest request(String path, String cookie) {
        return new ServerHttpRequest() {
            @Override public java.net.InetSocketAddress getRemoteAddress() {
                return null;
            }

            @Override public java.net.InetSocketAddress getLocalAddress() {
                return null;
            }

            @Override public java.io.InputStream getBody() {
                return java.io.InputStream.nullInputStream();
            }

            @Override public org.springframework.http.HttpMethod getMethod() {
                return org.springframework.http.HttpMethod.GET;
            }

            @Override public URI getURI() {
                return URI.create("ws://test" + path);
            }

            @Override public org.springframework.http.server.ServerHttpAsyncRequestControl getAsyncRequestControl(
                    org.springframework.http.server.ServerHttpResponse response) {
                return null;
            }

            @Override public java.security.Principal getPrincipal() {
                return null;
            }

            @Override public Map<String, Object> getAttributes() {
                return Map.of();
            }

            @Override public HttpHeaders getHeaders() {
                var headers = new HttpHeaders();
                if (cookie != null) headers.add(HttpHeaders.COOKIE, cookie);
                return headers;
            }
        };
    }

    private Map<String, Object> attributes(String path, String cookie) {
        Map<String, Object> attributes = new java.util.HashMap<>();
        interceptor.beforeHandshake(request(path, cookie), null, null, attributes);
        return attributes;
    }

    @Nested
    @DisplayName("appKey 校验")
    class AppKey {

        @Test
        @DisplayName("路径里的 appKey 与配置不符 → 拒绝握手")
        void wrongAppKeyRejected() {
            Map<String, Object> attributes = new java.util.HashMap<>();
            boolean ok = interceptor.beforeHandshake(
                    request("/app/other-key", null), null, null, attributes);

            assertThat(ok).isFalse();
        }

        @Test
        @DisplayName("appKey 一致 → 放行")
        void rightAppKeyAccepted() {
            assertThat(interceptor.beforeHandshake(
                    request("/app/jaravel", null), null, null, new java.util.HashMap<>()))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("用户解析")
    class UserResolution {

        @Test
        @DisplayName("会话 cookie → 会话里的 login id → 用户")
        void resolvesFromSession() {
            sessionStore.write("sess-1", Map.of("login_web", 42L),
                    java.time.Duration.ofMinutes(30));

            var attributes = attributes("/app/jaravel", "jaravel_session=sess-1");

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE))
                    .isInstanceOf(User.class)
                    .extracting(u -> ((User) u).id()).isEqualTo(42L);
            assertThat(attributes.get(JaravelWebSocketHandler.SESSION_ATTRIBUTE))
                    .isInstanceOf(net.dobeen.jaravel.session.SessionData.class);
        }

        @Test
        @DisplayName("会话过期但 remember cookie 有效 → 走 retrieveByToken")
        void resolvesFromRememberCookie() {
            var attributes = attributes("/app/jaravel", "remember_web=42|valid-token");

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE))
                    .isInstanceOf(User.class)
                    .extracting(u -> ((User) u).id()).isEqualTo(42L);
        }

        @Test
        @DisplayName("remember cookie 格式坏 → guest")
        void malformedRememberCookieIsGuest() {
            var attributes = attributes("/app/jaravel", "remember_web=garbage");

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE)).isNull();
        }

        @Test
        @DisplayName("什么 cookie 都没有 → guest")
        void noCookieIsGuest() {
            var attributes = attributes("/app/jaravel", null);

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE)).isNull();
        }

        @Test
        @DisplayName("会话里没有 login 键 → guest")
        void sessionWithoutLoginIsGuest() {
            sessionStore.write("sess-2", Map.of("cart", "x"),
                    java.time.Duration.ofMinutes(30));

            var attributes = attributes("/app/jaravel", "jaravel_session=sess-2");

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE)).isNull();
        }

        @Test
        @DisplayName("多个 cookie 并存时按名字取")
        void picksRightCookieAmongSeveral() {
            sessionStore.write("sess-3", Map.of("login_web", 42L),
                    java.time.Duration.ofMinutes(30));

            var attributes = attributes("/app/jaravel",
                    "other=1; jaravel_session=sess-3; theme=dark");

            assertThat(attributes.get(JaravelWebSocketHandler.USER_ATTRIBUTE))
                    .isInstanceOf(User.class);
        }
    }
}
