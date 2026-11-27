package net.dobeen.jaravel.spring.broadcast;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import net.dobeen.jaravel.auth.AuthManager;
import net.dobeen.jaravel.auth.UserProvider;
import net.dobeen.jaravel.auth.guard.SessionGuard;
import net.dobeen.jaravel.session.SessionConfig;
import net.dobeen.jaravel.session.SessionData;
import net.dobeen.jaravel.session.SessionStore;

/**
 * 握手期的用户解析(docs/19 §6.4)。
 *
 * <p>WS 握手是 HTTP Upgrade 请求,**不走 Jaravel 的中间件管道**
 * (那是路由层的),所以这里自己完成两件事:
 * <ol>
 *   <li>校验端点路径里的 appKey 与配置一致 —— 单应用单 key
 *       (docs/19 §5.3 的非目标,校验是最小的诚实);</li>
 *   <li>会话 cookie → 用户:复用 guard 的既有机制,**不重写一套
 *       用户解析**。会话过期但 remember cookie 有效时走
 *       {@link UserProvider#retrieveByToken} 的路径(与
 *       {@link SessionGuard} 的恢复登录同一语义,但不写回会话 ——
 *       连接没有会话写回通道)。</li>
 * </ol>
 *
 * <p>结果(用户、会话数据)放进握手属性,由
 * {@link JaravelWebSocketHandler} 取走并绑进连接作用域。
 *
 * <p>Origin 校验不在这里 —— 用 Spring 的
 * {@code setAllowedOrigins}(默认仅同源),那套校验在握手更早的阶段。
 */
public class UserResolvingInterceptor implements HandshakeInterceptor {

    private final SessionStore sessionStore;
    private final SessionConfig sessionConfig;
    private final AuthManager authManager;
    private final String guardName;
    private final String appKey;

    public UserResolvingInterceptor(SessionStore sessionStore, SessionConfig sessionConfig,
                                    AuthManager authManager, String guardName, String appKey) {
        this.sessionStore = sessionStore;
        this.sessionConfig = sessionConfig;
        this.authManager = authManager;
        this.guardName = guardName;
        this.appKey = appKey;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        // 端点模板是 /app/{appKey},任何 key 都能匹配进来 —— 这里校验
        String path = request.getURI().getPath();
        String keyInPath = path.substring(path.lastIndexOf('/') + 1);
        if (!appKey.equals(keyInPath)) {
            return false;
        }

        attributes.put(JaravelWebSocketHandler.USER_ATTRIBUTE, resolveUser(request));
        attributes.put(JaravelWebSocketHandler.SESSION_ATTRIBUTE, resolveSession(request));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 没有收尾
    }

    /**
     * 会话 cookie → 用户。会话里没有时试 remember cookie。
     * 都没有 → null(guest,只能订阅公开频道)。
     */
    private Object resolveUser(ServerHttpRequest request) {
        // 会话装配被排除的应用:所有连接都是 guest,私有频道一律拒绝
        if (sessionStore == null) return null;

        Map<String, String> cookies = parseCookies(request);

        UserProvider provider = guardProvider();
        if (provider == null) return null;

        String sessionId = cookies.get(sessionConfig.cookieName());
        if (sessionId != null) {
            var session = sessionStore.read(sessionId);
            if (session.isPresent()) {
                Object id = session.get().get(sessionKey());
                if (id != null) {
                    return provider.retrieveById(id).orElse(null);
                }
            }
        }

        // remember cookie:&lt;id&gt;|&lt;token&gt; —— 与 SessionGuard 同格式
        String remember = cookies.get(rememberCookieName());
        if (remember == null) return null;

        int separator = remember.indexOf('|');
        if (separator <= 0 || separator == remember.length() - 1) return null;

        return provider.retrieveByToken(
                remember.substring(0, separator), remember.substring(separator + 1))
                .orElse(null);
    }

    /** 会话数据(可能为 null)—— 绑进连接作用域让 Auth.user() 可用 */
    private SessionData resolveSession(ServerHttpRequest request) {
        if (sessionStore == null) return null;

        String sessionId = parseCookies(request).get(sessionConfig.cookieName());
        if (sessionId == null) return null;

        return sessionStore.read(sessionId)
                .map(data -> new SessionData(sessionId, data))
                .orElse(null);
    }

    private UserProvider guardProvider() {
        try {
            return authManager.guard(guardName).provider();
        } catch (RuntimeException e) {
            // guard 没注册(没配认证的应用):WS 仍可用,私有频道全部拒绝
            return null;
        }
    }

    /** 会话键 —— 与 SessionGuard 的命名约定一致(它公开了这个约定) */
    private String sessionKey() {
        return "login_" + guardName;
    }

    private String rememberCookieName() {
        return "remember_" + guardName;
    }

    private static Map<String, String> parseCookies(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.COOKIE);
        Map<String, String> cookies = new java.util.HashMap<>();
        if (header == null) return cookies;

        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return cookies;
    }
}
