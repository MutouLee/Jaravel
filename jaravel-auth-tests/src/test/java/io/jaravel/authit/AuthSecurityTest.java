package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.EmailVerification;
import io.jaravel.auth.LoginThrottle;
import io.jaravel.auth.StatefulGuard;
import io.jaravel.auth.guard.Jwt;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.auth.guard.TokenGuard;
import io.jaravel.auth.passwords.PasswordBroker;
import io.jaravel.authit.models.User;
import io.jaravel.cache.RateLimiter;
import io.jaravel.cache.Repository;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.core.crypt.Keys;
import io.jaravel.core.crypt.UrlSigner;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.http.BasicRequest;
import io.jaravel.http.Cookie;
import io.jaravel.http.HttpException;
import io.jaravel.http.Response;
import io.jaravel.session.SessionConfig;
import io.jaravel.session.SessionData;
import io.jaravel.session.StartSession;
import io.jaravel.session.store.MemoryStore;

/**
 * ★ docs/08 §7.2 的安全清单,逐条断言。
 *
 * <p>这 12 条是 v0.3 的验收项之一。它们在别的测试里大多已被覆盖 ——
 * 本文件的价值不是「再测一遍」,而是**可审计**:清单的每一条在这里有
 * 一个同名的嵌套类,审计者(或半年后的我们)能一眼确认哪条有测、
 * 测的是什么。清单项被删掉或改写时,对应的类名会跟着变,
 * 不会出现「文档说有测,代码里早没了」。
 *
 * <p>编号与 docs/08 §7.2 的列表顺序严格一致。
 */
class AuthSecurityTest extends AuthTestBase {

    private io.jaravel.database.Connection connection;
    private Repository cache;

    private static final String APP_KEY =
            "base64:" + Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUpSecurityFixtures() throws SQLException {
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table personal_access_tokens (
                        id integer primary key autoincrement,
                        name text,
                        user_id integer not null,
                        token_hash text not null,
                        created_at text,
                        expires_at text,
                        last_used_at text
                    )
                    """);
            st.execute("""
                    create table password_reset_tokens (
                        email text primary key,
                        token text not null,
                        created_at text not null
                    )
                    """);
        }
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        cache = new io.jaravel.cache.CacheManager()
                .register("array", new ArrayStore())
                .defaultStore("array")
                .store();
    }

    private Authenticatable principal(User user) {
        return auth.provider("users").retrieveById(user.id).orElseThrow();
    }

    // ================= ① =================

    @Nested
    @DisplayName("① 登录成功后 Session.regenerate()(防会话固定)")
    class C01_SessionRegeneration {

        @Test
        @DisplayName("attempt 成功后会话 id 变了")
        void attemptRegeneratesId() {
            createUser("a@example.com", "secret");
            var session = newSession();
            String before = session.id();

            inRequest(session, () ->
                    assertThat(((StatefulGuard) auth.guard("web"))
                            .attempt(credentials("a@example.com", "secret"))).isTrue());

            assertThat(session.id())
                    .as("""
                        会话固定攻击:攻击者先让受害者带上一个它已知的 session id,
                        受害者登录后那个 id 就成了已认证会话 —— 攻击者直接复用。
                        换 id 是唯一的防法。""")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("login(user) 也换 id,不只是 attempt")
        void loginAlsoRegenerates() {
            var user = createUser("a@example.com", "secret");
            var session = newSession();
            String before = session.id();

            // 社交登录 / 注册后自动登录走的是 login 而不是 attempt,
            // 漏掉它等于只防住了一半的入口
            inRequest(session, () ->
                    ((StatefulGuard) auth.guard("web")).login(principal(user)));

            assertThat(session.id()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("登录失败不换 id —— 换了会把游客的购物车之类的数据丢掉")
        void failedAttemptKeepsId() {
            createUser("a@example.com", "secret");
            var session = newSession();
            String before = session.id();

            inRequest(session, () ->
                    assertThat(((StatefulGuard) auth.guard("web"))
                            .attempt(credentials("a@example.com", "wrong"))).isFalse());

            assertThat(session.id()).isEqualTo(before);
        }
    }

    // ================= ② =================

    @Nested
    @DisplayName("② remember token 随机 60 字节,哈希存库,登出时失效")
    class C02_RememberToken {

        private String loginWithRemember(User user, SessionData session) {
            return inRequest(session, () -> {
                var guard = (SessionGuard) auth.guard("web");
                guard.login(principal(user), true);
                return guard.takePendingCookie().orElseThrow().value();
            });
        }

        @Test
        @DisplayName("令牌足够长且是随机的")
        void tokenIsLongAndRandom() {
            var one = createUser("a@example.com", "x");
            var two = createUser("b@example.com", "x");

            String first = loginWithRemember(one, newSession());
            String second = loginWithRemember(two, newSession());

            String firstToken = first.substring(first.indexOf('|') + 1);
            // docs/08 §7.2 要求 60 字节;base64url 编码后 80 个字符。
            // 断言编码后的长度而不是「≥60」—— 后者会让一个 45 字节的
            // 令牌也通过(base64 后是 60 个字符),而那正是修复前的状态
            assertThat(firstToken).hasSize(80);
            assertThat(java.util.Base64.getUrlDecoder().decode(firstToken))
                    .hasSize(io.jaravel.auth.RememberToken.TOKEN_BYTES);
            assertThat(firstToken)
                    .as("两个用户的令牌不该有任何关系")
                    .isNotEqualTo(second.substring(second.indexOf('|') + 1));
        }

        @Test
        @DisplayName("★ 库里存的不是明文")
        void storedHashed() {
            var user = createUser("a@example.com", "x");
            String cookie = loginWithRemember(user, newSession());
            String plain = cookie.substring(cookie.indexOf('|') + 1);

            var row = connection.table("users").where("id", user.id)
                    .get().first().orElseThrow();

            assertThat(String.valueOf(row.get("remember_token")))
                    .as("库被读走时 remember_token 不能直接拿来登录")
                    .isNotEqualTo(plain);
        }

        @Test
        @DisplayName("★ 登出后旧 cookie 不能再用")
        void logoutInvalidates() {
            var user = createUser("a@example.com", "x");
            var session = newSession();
            String cookie = loginWithRemember(user, session);

            // 必须在**同一个会话**里登出 —— 换个会话调 logout 时
            // 那个会话里没有登录中的用户,也就无从得知该清谁的令牌
            inRequest(session, () -> ((SessionGuard) auth.guard("web")).logout());

            inRequest(newSession(), () -> assertThat(
                    ((SessionGuard) auth.guard("web")).recallFromCookie(cookie))
                    .as("登出必须让长期登录凭据一起失效,"
                      + "否则「我在网吧登出了」是假的")
                    .isEmpty());
        }

        @Test
        @DisplayName("★ 伪造或半截的 cookie 一律拒绝")
        void forgedCookieRejected() {
            var user = createUser("a@example.com", "x");
            loginWithRemember(user, newSession());

            inRequest(newSession(), () -> {
                var guard = (SessionGuard) auth.guard("web");
                // 只按 id 取用户然后信任 cookie,等于谁都能伪造登录
                assertThat(guard.recallFromCookie(user.id + "|guessed")).isEmpty();
                assertThat(guard.recallFromCookie(user.id + "|")).isEmpty();
                assertThat(guard.recallFromCookie(String.valueOf(user.id))).isEmpty();
                assertThat(guard.recallFromCookie("")).isEmpty();
            });
        }
    }

    // ================= ③ =================

    @Nested
    @DisplayName("③ API token 明文只在创建时返回,库中存 SHA-256")
    class C03_ApiTokenStorage {

        @Test
        @DisplayName("★ 库里是 64 位 hex,不含明文")
        void onlyHashStored() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            var token = guard.issue(principal(user), "CI");
            String secret = token.plainText().substring(token.plainText().indexOf('|') + 1);

            var row = connection.table("personal_access_tokens")
                    .where("id", token.id()).get().first().orElseThrow();

            assertThat(String.valueOf(row.get("token_hash")))
                    .hasSize(64)
                    .doesNotContain(secret);
        }

        @Test
        @DisplayName("★ 明文无法二次取回 —— toString 也不泄露")
        void plaintextNotRecoverable() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);
            var token = guard.issue(principal(user), "CI");
            String secret = token.plainText().substring(token.plainText().indexOf('|') + 1);

            // 「令牌只显示一次」这个 UX 的前提是明文真的没被留下。
            // toString 尤其危险:它会出现在日志、异常、调试器里
            assertThat(token.toString()).doesNotContain(secret);
        }
    }

    // ================= ④ =================

    @Nested
    @DisplayName("④ token 比对用 MessageDigest.isEqual(constant-time)")
    class C04_ConstantTimeComparison {

        /**
         * 这一条是「实现方式」而不是「可观测行为」,所以**按字节码断言**。
         *
         * <p>行为测试做不到:定时攻击的差异在纳秒级,在 JIT + GC 的 JVM 上
         * 用 JUnit 根本测不出稳定的时间差。写一个「跑 1000 次比较平均耗时」
         * 的测试只会得到一个随机失败的 flaky 测试,比不测更糟。
         *
         * <p>所以这里直接检查这些类是否真的引用了
         * {@code MessageDigest.isEqual} —— 它是一个二元事实,不会 flaky。
         */
        @Test
        @DisplayName("★ 凭据比较的几个类都引用了 MessageDigest.isEqual")
        void usesConstantTimeCompare() throws Exception {
            var classes = List.of(
                    "io.jaravel.auth.provider.AbstractUserProvider",
                    "io.jaravel.auth.guard.TokenGuard",
                    "io.jaravel.auth.guard.Jwt",
                    "io.jaravel.auth.passwords.PasswordBroker",
                    "io.jaravel.auth.EmailVerification",
                    "io.jaravel.core.crypt.UrlSigner");

            var missing = new ArrayList<String>();
            for (String name : classes) {
                if (!referencesConstantTimeEquals(name)) missing.add(name);
            }

            assertThat(missing)
                    .as("""
                        这些类都要拿用户提供的值与一个秘密比较。
                        用 String.equals 会逐字符早退,于是「前几个字节对不对」
                        可以被耗时测出来,秘密可以被逐字节爆破出来。""")
                    .isEmpty();
        }

        /** 在常量池里找 MessageDigest.isEqual 的符号引用 */
        private boolean referencesConstantTimeEquals(String className) throws Exception {
            String resource = className.replace('.', '/') + ".class";
            try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(in).as("找不到 " + resource).isNotNull();
                byte[] bytecode = in.readAllBytes();

                // 常量池里的字符串以 modified UTF-8 原样出现,直接扫字节序列
                return contains(bytecode, "java/security/MessageDigest")
                    && contains(bytecode, "isEqual");
            }
        }

        private boolean contains(byte[] haystack, String needle) {
            byte[] bytes = needle.getBytes(StandardCharsets.UTF_8);
            outer:
            for (int i = 0; i <= haystack.length - bytes.length; i++) {
                for (int j = 0; j < bytes.length; j++) {
                    if (haystack[i + j] != bytes[j]) continue outer;
                }
                return true;
            }
            return false;
        }
    }

    // ================= ⑤ =================

    @Nested
    @DisplayName("⑤ 用户不存在时仍执行 dummy 哈希(防时序枚举)")
    class C05_TimingAttackOnMissingUser {

        @Test
        @DisplayName("★ 不存在的用户与密码错误走同一条耗时路径")
        void dummyHashIsBurned() {
            createUser("exists@example.com", "secret");

            long missing = timeOf(() -> auth.guard("web")
                    .validate(credentials("nobody@example.com", "secret")));
            long wrongPassword = timeOf(() -> auth.guard("web")
                    .validate(credentials("exists@example.com", "wrong")));

            // 不断言具体倍数 —— 单次计时在 JVM 上噪声极大。
            // 断言的是「同一个数量级」:不烧 dummy 哈希的话,
            // 「用户不存在」会快上几十倍(完全跳过哈希),
            // 那个差异大到可以直接当成账号枚举接口用
            double ratio = (double) Math.max(missing, wrongPassword)
                         / Math.max(1, Math.min(missing, wrongPassword));

            assertThat(ratio)
                    .as("用户不存在 %d ns,密码错误 %d ns —— 差异不该暴露账号是否存在",
                        missing, wrongPassword)
                    .isLessThan(20.0);
        }

        private long timeOf(Runnable body) {
            // 先热几次,把 JIT 与首次 PBKDF2 初始化的开销排除掉
            for (int i = 0; i < 3; i++) body.run();

            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long start = System.nanoTime();
                body.run();
                best = Math.min(best, System.nanoTime() - start);
            }
            return best;
        }

        @Test
        @DisplayName("无论用户是否存在,返回值都只是 false")
        void sameReturnValue() {
            createUser("exists@example.com", "secret");

            assertThat(auth.guard("web")
                    .validate(credentials("nobody@example.com", "secret"))).isFalse();
            assertThat(auth.guard("web")
                    .validate(credentials("exists@example.com", "wrong"))).isFalse();
        }
    }

    // ================= ⑥ =================

    @Nested
    @DisplayName("⑥ 登录失败节流默认开启")
    class C06_LoginThrottling {

        @Test
        @DisplayName("★ 默认构造就带阈值,不需要显式开启")
        void onByDefault() {
            var throttle = new LoginThrottle(new RateLimiter(cache));

            // 「安全特性需要显式打开」等于「大多数项目没有这个特性」
            assertThat(throttle.maxPerAccount()).isPositive();
            assertThat(throttle.maxPerIp()).isPositive();
            assertThat(throttle.window()).isPositive();
        }

        @Test
        @DisplayName("超过阈值后抛 429,带 Retry-After")
        void locksOutWith429() {
            var throttle = new LoginThrottle(new RateLimiter(cache), 3, 100,
                                             Duration.ofMinutes(1));

            for (int i = 0; i < 3; i++) {
                throttle.guardAttempt("a@example.com", "1.2.3.4");
                throttle.recordFailure("a@example.com", "1.2.3.4");
            }

            assertThatThrownBy(() ->
                    throttle.guardAttempt("a@example.com", "1.2.3.4"))
                    .isInstanceOf(HttpException.class)
                    .satisfies(e -> {
                        var http = (HttpException) e;
                        assertThat(http.status()).isEqualTo(429);
                        assertThat(http.headers()).containsKey("Retry-After");
                    });
        }

        @Test
        @DisplayName("★ 按 IP 也计数 —— 否则换账号就能继续喷洒")
        void countsPerIpToo() {
            var throttle = new LoginThrottle(new RateLimiter(cache), 100, 3,
                                             Duration.ofMinutes(1));

            // 每次都换一个账号,只有 IP 相同
            for (int i = 0; i < 3; i++) {
                throttle.recordFailure("user" + i + "@example.com", "1.2.3.4");
            }

            assertThatThrownBy(() -> throttle.guardAttempt("fresh@example.com", "1.2.3.4"))
                    .isInstanceOf(HttpException.class);
        }

        @Test
        @DisplayName("★ 账号大小写不同不能重置计数")
        void caseInsensitiveKey() {
            var throttle = new LoginThrottle(new RateLimiter(cache), 2, 100,
                                             Duration.ofMinutes(1));

            throttle.recordFailure("a@example.com", "1.2.3.4");
            throttle.recordFailure("A@EXAMPLE.COM", "1.2.3.4");

            assertThatThrownBy(() -> throttle.guardAttempt("a@Example.com", "1.2.3.4"))
                    .as("改个大小写就能绕过节流是个常见实现缺陷")
                    .isInstanceOf(HttpException.class);
        }

        @Test
        @DisplayName("成功登录后清零 —— 节流只针对攻击,不针对正常用户")
        void clearedOnSuccess() {
            var throttle = new LoginThrottle(new RateLimiter(cache), 3, 100,
                                             Duration.ofMinutes(1));
            throttle.recordFailure("a@example.com", "1.2.3.4");
            throttle.recordFailure("a@example.com", "1.2.3.4");

            throttle.clear("a@example.com", "1.2.3.4");

            assertThat(throttle.remainingAttempts("a@example.com")).isEqualTo(3);
        }
    }

    // ================= ⑦ =================

    @Nested
    @DisplayName("⑦ 密码重置 token 单次有效 + 过期 + 节流")
    class C07_PasswordResetToken {

        private PasswordBroker broker() {
            return new PasswordBroker(auth.provider("users"), connection);
        }

        private String sendAndCapture(PasswordBroker broker, String email) {
            var captured = new ArrayList<String>();
            broker.sendResetLink(Map.of("email", email), (u, token) -> captured.add(token));
            return captured.get(0);
        }

        @Test
        @DisplayName("★ 单次有效")
        void singleUse() {
            createUser("a@example.com", "old");
            var broker = broker();
            var credentials = Map.<String, Object>of(
                    "email", "a@example.com",
                    "token", sendAndCapture(broker, "a@example.com"),
                    "password", "new");

            assertThat(broker.reset(credentials, (u, p) -> {}))
                    .isEqualTo(PasswordBroker.Status.RESET);
            assertThat(broker.reset(credentials, (u, p) -> {
                throw new AssertionError("转发/归档的重置邮件不该能反复改密码");
            })).isEqualTo(PasswordBroker.Status.INVALID_TOKEN);
        }

        @Test
        @DisplayName("★ 过期后失效")
        void expires() {
            createUser("a@example.com", "old");
            var shortLived = new PasswordBroker(auth.provider("users"), connection,
                    "password_reset_tokens", Duration.ofMillis(1), Duration.ZERO);
            String token = sendAndCapture(shortLived, "a@example.com");

            sleep(20);

            assertThat(shortLived.reset(Map.of(
                    "email", "a@example.com", "token", token, "password", "new"),
                    (u, p) -> {
                        throw new AssertionError("过期的 token 不该通过");
                    }))
                    .isEqualTo(PasswordBroker.Status.EXPIRED_TOKEN);
        }

        @Test
        @DisplayName("★ 节流:窗口内不重复发送")
        void throttled() {
            createUser("a@example.com", "x");
            var broker = broker();

            assertThat(broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {}))
                    .isEqualTo(PasswordBroker.Status.SENT);
            assertThat(broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {
                throw new AssertionError("节流窗口内不该再发");
            })).isEqualTo(PasswordBroker.Status.THROTTLED);
        }

        @Test
        @DisplayName("★ token 哈希存库")
        void hashedInDatabase() {
            createUser("a@example.com", "x");
            var broker = broker();
            String plain = sendAndCapture(broker, "a@example.com");

            var row = connection.table("password_reset_tokens").get().first().orElseThrow();
            assertThat(String.valueOf(row.get("token"))).isNotEqualTo(plain);
        }

        @Test
        @DisplayName("★ 不泄露邮箱是否注册过")
        void doesNotLeakAccountExistence() {
            var called = new boolean[1];
            // 没有创建任何用户
            assertThat(broker().sendResetLink(Map.of("email", "nobody@example.com"),
                    (u, t) -> called[0] = true))
                    .as("与「已发送」返回同一个状态,否则这就是最方便的邮箱枚举工具")
                    .isEqualTo(PasswordBroker.Status.SENT);
            assertThat(called[0]).isFalse();
        }
    }

    // ================= ⑧ =================

    @Nested
    @DisplayName("⑧ JWT 拒绝 alg: none,拒绝算法混淆")
    class C08_JwtAlgorithmAttacks {

        private final Jwt hs256 = Jwt.hs256(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        private String forge(String headerJson, String payloadJson, String signature) {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                 + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                 + "." + signature;
        }

        @Test
        @DisplayName("★ alg: none 一律拒绝")
        void rejectsAlgNone() {
            // 经典攻击:把 alg 改成 none,签名留空 —— 实现若「按 header 里
            // 声明的算法去验」,none 就意味着不验,任何 payload 都通过
            for (String alg : new String[] {"none", "None", "NONE", "nOnE"}) {
                String forged = forge("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}",
                        "{\"sub\":\"1\",\"exp\":9999999999}", "");

                assertThatThrownBy(() -> hs256.verify(forged))
                        .as("alg=%s 必须被拒", alg)
                        .isInstanceOf(Jwt.InvalidTokenException.class);
            }
        }

        @Test
        @DisplayName("★ 空签名段也拒绝(不只是 alg 为 none 时)")
        void rejectsEmptySignature() {
            String forged = forge("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                    "{\"sub\":\"1\",\"exp\":9999999999}", "");

            assertThatThrownBy(() -> hs256.verify(forged))
                    .isInstanceOf(Jwt.InvalidTokenException.class);
        }

        @Test
        @DisplayName("★ 算法混淆:RS256 公钥当 HS256 密钥")
        void rejectsAlgorithmConfusion() throws Exception {
            var keys = KeyPairGenerator.getInstance("RSA");
            keys.initialize(2048);
            var pair = keys.generateKeyPair();
            var rs256 = Jwt.rs256(pair.getPrivate(), pair.getPublic());

            // 攻击:服务端配的是 RS256。公钥是公开的,攻击者拿公钥的字节
            // 当 HMAC 密钥签一个 alg: HS256 的 token。实现若按 header 的
            // alg 选算法,就会用公钥去验 HMAC —— 而公钥攻击者也有
            byte[] publicKeyBytes = pair.getPublic().getEncoded();
            var attackersHmac = Jwt.hs256(publicKeyBytes);
            String forged = attackersHmac.issue(Map.of("sub", "1"), Duration.ofHours(1));

            assertThatThrownBy(() -> rs256.verify(forged))
                    .as("必须用**配置的**算法验,而不是 token 自称的那个")
                    .isInstanceOf(Jwt.InvalidTokenException.class);
        }

        @Test
        @DisplayName("★ 反向:HS256 的服务端不接受 RS256 的 token")
        void rejectsUnexpectedAlgorithm() throws Exception {
            var keys = KeyPairGenerator.getInstance("RSA");
            keys.initialize(2048);
            var pair = keys.generateKeyPair();
            String rsToken = Jwt.rs256(pair.getPrivate(), pair.getPublic())
                    .issue(Map.of("sub", "1"), Duration.ofHours(1));

            assertThatThrownBy(() -> hs256.verify(rsToken))
                    .isInstanceOf(Jwt.InvalidTokenException.class);
        }

        @Test
        @DisplayName("签名被改一个字符就失效")
        void rejectsTamperedSignature() {
            String token = hs256.issue(Map.of("sub", "1"), Duration.ofHours(1));
            int lastDot = token.lastIndexOf('.');
            char first = token.charAt(lastDot + 1);
            String tampered = token.substring(0, lastDot + 1)
                    + (first == 'A' ? 'B' : 'A') + token.substring(lastDot + 2);

            assertThatThrownBy(() -> hs256.verify(tampered))
                    .isInstanceOf(Jwt.InvalidTokenException.class);
        }
    }

    // ================= ⑨ =================

    @Nested
    @DisplayName("⑨ 签名 URL:HMAC-SHA256 + 过期 + constant-time 比对")
    class C09_SignedUrls {

        private final UrlSigner signer = UrlSigner.fromBase64(APP_KEY);

        @Test
        @DisplayName("有效链接通过")
        void validPasses() {
            assertThat(signer.isValid(
                    signer.sign("https://app.com/x", Duration.ofHours(1)))).isTrue();
        }

        @Test
        @DisplayName("★ 改任何一个字符都失效")
        void tamperingBreaksIt() {
            String signed = signer.sign("https://app.com/verify/42", Duration.ofHours(1));

            assertThat(signer.isValid(signed.replace("/42", "/43")))
                    .as("改 id 就能验证别人的邮箱 —— 这是签名要防的第一件事")
                    .isFalse();
        }

        @Test
        @DisplayName("★ expires 在签名覆盖范围内 —— 改它无法续期")
        void expiresIsSigned() {
            String signed = signer.sign("https://app.com/x", Duration.ofSeconds(-5));
            assertThat(signer.isValid(signed)).isFalse();

            // 把过期时间往后挪
            String extended = signed.replaceAll("expires=\\d+", "expires=9999999999");

            assertThat(signer.isValid(extended))
                    .as("这是签名 URL 最容易漏掉的一点:只签路径不签 expires,"
                      + "于是任何人都能把链接改成永久有效")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 缺签名、空签名都拒绝")
        void missingSignatureRejected() {
            assertThat(signer.isValid("https://app.com/x")).isFalse();
            assertThat(signer.isValid("https://app.com/x?signature=")).isFalse();
            assertThat(signer.isValid("https://app.com/x?signature=AAAA")).isFalse();
        }

        @Test
        @DisplayName("参数顺序变了仍然有效 —— 否则链接会「有时能用」")
        void parameterOrderDoesNotMatter() {
            String signed = signer.sign("https://app.com/x?b=2&a=1", Duration.ofHours(1));

            // 浏览器、邮件客户端、CDN 都可能重排查询参数
            String reordered = reorderQuery(signed);
            assertThat(signer.isValid(reordered)).isTrue();
        }

        private String reorderQuery(String url) {
            int q = url.indexOf('?');
            var params = new ArrayList<>(List.of(url.substring(q + 1).split("&")));
            java.util.Collections.reverse(params);
            return url.substring(0, q + 1) + String.join("&", params);
        }

        @Test
        @DisplayName("换 app.key 后旧链接全失效")
        void keyRotationInvalidates() {
            String signed = signer.sign("https://app.com/x", Duration.ofHours(1));
            var other = UrlSigner.fromBase64("base64:" + Base64.getEncoder()
                    .encodeToString("fedcba9876543210fedcba9876543210"
                            .getBytes(StandardCharsets.UTF_8)));

            assertThat(other.isValid(signed)).isFalse();
        }

        @Test
        @DisplayName("★ 短密钥直接拒绝构造 —— 签名的安全性全靠密钥强度")
        void shortKeyRejected() {
            assertThatThrownBy(() -> new UrlSigner("tooshort".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32");
        }

        @Test
        @DisplayName("邮箱验证链接:改了邮箱后旧链接失效")
        void emailChangeInvalidatesVerificationLink() {
            var user = createUser("a@example.com", "x");
            var verification = new EmailVerification(signer,
                    Keys.derive(APP_KEY, "jaravel:email-verification"));

            String url = verification.verificationUrl("https://app.com", principal(user));
            String hash = hashSegmentOf(url);

            // 用户改了邮箱
            user.email = "b@example.com";
            user.save();

            assertThat(verification.verify(url, principal(user), hash, u -> {
                throw new AssertionError("不该把从未收到验证邮件的地址标记为已验证");
            })).isEqualTo(EmailVerification.Result.EMAIL_CHANGED);
        }

        private String hashSegmentOf(String url) {
            String path = url.substring(0, url.indexOf('?'));
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    // ================= ⑩ =================

    @Nested
    @DisplayName("⑩ logout() 清空 session 并重新生成 CSRF token")
    class C10_Logout {

        @Test
        @DisplayName("★ 登出后会话里没有残留数据")
        void sessionIsFlushed() {
            var user = createUser("a@example.com", "x");
            var session = newSession();

            inRequest(session, () -> {
                var guard = (StatefulGuard) auth.guard("web");
                guard.login(principal(user));
                io.jaravel.session.Session.put("cart", "sensitive");
                guard.logout();
            });

            assertThat(session.has("cart"))
                    .as("共用电脑上登出后,下一个人不该看到上一个人的会话数据")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 登出后 CSRF token 换了")
        void csrfTokenRegenerated() {
            var user = createUser("a@example.com", "x");
            var session = newSession();

            String before = inRequest(session, () -> {
                ((StatefulGuard) auth.guard("web")).login(principal(user));
                return session.token();
            });

            inRequest(session, () -> ((StatefulGuard) auth.guard("web")).logout());

            assertThat(session.token())
                    .as("沿用旧 CSRF token 会让登出前拿到 token 的页面"
                      + "仍能对新会话发起写操作")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("登出后 check() 为 false")
        void noLongerAuthenticated() {
            var user = createUser("a@example.com", "x");
            var session = newSession();

            inRequest(session, () -> {
                var guard = (StatefulGuard) auth.guard("web");
                guard.login(principal(user));
                guard.logout();
                assertThat(guard.check()).isFalse();
            });

            // 下一个「请求」里也仍是未登录
            inRequest(session, () -> assertThat(auth.guard("web").check()).isFalse());
        }
    }

    // ================= ⑪ =================

    @Nested
    @DisplayName("⑪ cookie 默认 HttpOnly + Secure(非 local)+ SameSite=Lax")
    class C11_CookieFlags {

        private Cookie sessionCookieFrom(SessionConfig config) {
            var middleware = new StartSession(new MemoryStore(), config);
            var request = BasicRequest.of("GET", "/");

            Response response = middleware.handle(request, r -> Response.text("ok"));

            return response.cookies().stream()
                    .filter(c -> c.name().equals(config.cookieName()))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("StartSession 没有写出会话 cookie"));
        }

        @Test
        @DisplayName("★ 默认三项都对")
        void defaultsAreSafe() {
            var cookie = sessionCookieFrom(SessionConfig.defaults());

            assertThat(cookie.httpOnly())
                    .as("非 HttpOnly 的会话 cookie 可被 XSS 直接读走")
                    .isTrue();
            assertThat(cookie.secure())
                    .as("非 Secure 会让会话 cookie 走明文 http 发出去")
                    .isTrue();
            assertThat(cookie.sameSite())
                    .as("SameSite 是 CSRF 的第二道防线")
                    .isEqualTo(Cookie.SameSite.LAX);
            assertThat(cookie.sameSite().headerValue()).isEqualTo("Lax");
        }

        @Test
        @DisplayName("★ 默认安全,只有显式声明 local 才放松")
        void secureIsOptOutNotOptIn() {
            // 默认值就是安全的那个 —— 这个顺序很重要:
            // 「默认不安全,要自己开」在实践中等于「大多数部署不安全」
            assertThat(SessionConfig.defaults().secure()).isTrue();
            assertThat(SessionConfig.defaults().forLocalHttp().secure()).isFalse();
            assertThat(SessionConfig.defaults().forLocalHttp().httpOnly())
                    .as("放松 secure 不该顺带把 HttpOnly 也关了").isTrue();
        }

        @Test
        @DisplayName("会话 cookie 的值是会话 id,不含用户信息")
        void cookieCarriesOnlyId() {
            var cookie = sessionCookieFrom(SessionConfig.defaults());

            assertThat(cookie.value()).doesNotContain("@").doesNotContain("user");
        }
    }

    // ================= ⑫ =================

    @Nested
    @DisplayName("⑫ @Hidden 的 password 绝不出现在任何 JSON 序列化中")
    class C12_PasswordNeverSerialized {

        /**
         * findAndRegisterModules 是必要的:模型的 created_at 是 Instant,
         * 裸 ObjectMapper 处理不了它 —— 而那会让这组测试因为
         * **时间字段**而失败,看起来像密码泄露测试写错了。
         * 框架的 ResponseWriter 同样注册了时间模块。
         */
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules();

        @Test
        @DisplayName("★ 直接序列化模型")
        void notInDirectSerialization() throws Exception {
            var user = createUser("a@example.com", "secret");

            assertThat(mapper.writeValueAsString(user))
                    .doesNotContain("password")
                    .doesNotContain(user.password);
        }

        @Test
        @DisplayName("★ 嵌在集合里序列化")
        void notInsideCollections() throws Exception {
            createUser("a@example.com", "secret");
            createUser("b@example.com", "secret");

            var all = User.all();
            assertThat(mapper.writeValueAsString(all)).doesNotContain("password");
            assertThat(mapper.writeValueAsString(Map.of("users", all)))
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("★ 包在 Response 里序列化")
        void notInResponseBody() throws Exception {
            var user = createUser("a@example.com", "secret");

            // 最常见的泄露方式:控制器直接 return user
            assertThat(mapper.writeValueAsString(Map.of("data", user)))
                    .doesNotContain("password")
                    .doesNotContain(user.password);
        }

        @Test
        @DisplayName("★ toMap() 也不含 —— 它是序列化的唯一出口")
        void notInToMap() {
            var user = createUser("a@example.com", "secret");

            assertThat(user.toMap()).doesNotContainKey("password");
            assertThat(user.toMap()).containsKey("email");
        }

        @Test
        @DisplayName("remember_token 同样不出现 —— 它也是一个可用凭据")
        void rememberTokenAlsoHidden() throws Exception {
            var user = createUser("a@example.com", "secret");
            inRequest(newSession(), () ->
                    ((StatefulGuard) auth.guard("web")).login(principal(user), true));

            var reloaded = User.findOrFail(user.id);
            assertThat(mapper.writeValueAsString(reloaded))
                    .doesNotContain("remember_token")
                    .doesNotContain("rememberToken");
        }

        @Test
        @DisplayName("但代码里仍能读到它 —— @Hidden 只影响序列化")
        void stillReadableInJava() {
            var user = createUser("a@example.com", "secret");

            // 否则密码校验就没法工作了。这一条是为了说明
            // @Hidden 的边界:它不是「字段不可见」,而是「不出现在 JSON 里」
            assertThat(user.password).isNotNull();
            assertThat(hasher.check("secret", user.password)).isTrue();
        }
    }

    // ================= 工具 =================

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
