package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.EmailVerification;
import io.jaravel.auth.LoginThrottle;
import io.jaravel.auth.hash.Hash;
import io.jaravel.auth.passwords.PasswordBroker;
import io.jaravel.authit.models.User;
import io.jaravel.cache.RateLimiter;
import io.jaravel.cache.Repository;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.core.crypt.UrlSigner;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;

/** 密码重置、邮箱验证、登录节流、签名 URL */
class PasswordAndVerificationTest extends AuthTestBase {

    private io.jaravel.database.Connection connection;
    private PasswordBroker broker;

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUpPasswordTables() throws SQLException {
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table password_reset_tokens (
                        email text primary key,
                        token text not null,
                        created_at text not null
                    )
                    """);
        }
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        broker = new PasswordBroker(auth.provider("users"), connection);
    }

    private Authenticatable principal(User user) {
        return auth.provider("users").retrieveById(user.id).orElseThrow();
    }

    // ================= 密码重置 =================

    @Nested
    @DisplayName("密码重置")
    class PasswordReset {

        @Test
        @DisplayName("发链接 → 重置 → 新密码生效")
        void happyPath() {
            var user = createUser("a@example.com", "old-password");
            var captured = new ArrayList<String>();

            var status = broker.sendResetLink(Map.of("email", "a@example.com"),
                    (u, token) -> captured.add(token));

            assertThat(status).isEqualTo(PasswordBroker.Status.SENT);
            assertThat(captured).hasSize(1);

            var reset = broker.reset(Map.of(
                    "email", "a@example.com",
                    "token", captured.get(0),
                    "password", "new-password"),
                    (u, plain) -> {
                        User model = (User) u.unwrap();
                        model.password = hasher.make(plain);
                        model.save();
                    });

            assertThat(reset).isEqualTo(PasswordBroker.Status.RESET);

            User reloaded = User.findOrFail(user.id);
            assertThat(hasher.check("new-password", reloaded.password)).isTrue();
            assertThat(hasher.check("old-password", reloaded.password)).isFalse();
        }

        @Test
        @DisplayName("★ token 哈希存库 —— 明文不可从数据库恢复")
        void tokenIsHashed() {
            createUser("a@example.com", "x");
            var captured = new ArrayList<String>();
            broker.sendResetLink(Map.of("email", "a@example.com"),
                    (u, token) -> captured.add(token));

            var row = connection.table("password_reset_tokens").get().first().orElseThrow();

            assertThat(String.valueOf(row.get("token")))
                    .as("库里不该出现明文").isNotEqualTo(captured.get(0));
            assertThat(String.valueOf(row.get("token"))).hasSize(64);   // SHA-256 hex
        }

        @Test
        @DisplayName("★ token 单次有效 —— 用过就失效")
        void tokenIsSingleUse() {
            var user = createUser("a@example.com", "old");
            var captured = new ArrayList<String>();
            broker.sendResetLink(Map.of("email", "a@example.com"),
                    (u, token) -> captured.add(token));

            var credentials = Map.<String, Object>of(
                    "email", "a@example.com",
                    "token", captured.get(0),
                    "password", "first-new");

            assertThat(broker.reset(credentials, (u, p) -> {}))
                    .isEqualTo(PasswordBroker.Status.RESET);

            // 同一个 token 再用一次 —— 邮件被转发/归档后任何看到它的人
            // 都能反复改密码,所以必须失效
            assertThat(broker.reset(credentials, (u, p) -> {
                throw new AssertionError("不该再执行重置");
            })).isEqualTo(PasswordBroker.Status.INVALID_TOKEN);
        }

        @Test
        @DisplayName("★ token 过期后失效")
        void tokenExpires() {
            createUser("a@example.com", "x");
            var shortLived = new PasswordBroker(auth.provider("users"), connection,
                    "password_reset_tokens", Duration.ofMillis(1), Duration.ZERO);

            var captured = new ArrayList<String>();
            shortLived.sendResetLink(Map.of("email", "a@example.com"),
                    (u, token) -> captured.add(token));

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(shortLived.reset(Map.of(
                    "email", "a@example.com",
                    "token", captured.get(0),
                    "password", "new"), (u, p) -> {
                throw new AssertionError("过期的 token 不该通过");
            })).isEqualTo(PasswordBroker.Status.EXPIRED_TOKEN);
        }

        @Test
        @DisplayName("★ 节流:窗口内不重复发送")
        void throttled() {
            createUser("a@example.com", "x");

            assertThat(broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {}))
                    .isEqualTo(PasswordBroker.Status.SENT);
            assertThat(broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {
                throw new AssertionError("节流窗口内不该再发");
            })).isEqualTo(PasswordBroker.Status.THROTTLED);
        }

        @Test
        @DisplayName("★ 不泄露邮箱是否注册过 —— 不存在也返回 SENT")
        void doesNotLeakAccountExistence() {
            // 没有创建任何用户
            var called = new boolean[1];

            var status = broker.sendResetLink(Map.of("email", "nobody@example.com"),
                    (u, t) -> called[0] = true);

            assertThat(status)
                    .as("与「已发送」返回同一个状态,否则这个接口就是最方便的邮箱枚举工具")
                    .isEqualTo(PasswordBroker.Status.SENT);
            assertThat(called[0]).as("但实际没有发送").isFalse();
        }

        @Test
        @DisplayName("伪造的 token 无效")
        void forgedToken() {
            createUser("a@example.com", "x");
            broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {});

            for (String forged : new String[] {"guessed", "", "0".repeat(43)}) {
                assertThat(broker.reset(Map.of(
                        "email", "a@example.com",
                        "token", forged,
                        "password", "new"), (u, p) -> {
                    throw new AssertionError("伪造 token 不该通过");
                })).isIn(PasswordBroker.Status.INVALID_TOKEN);
            }
        }

        @Test
        @DisplayName("重置不存在的账号 → INVALID_USER")
        void unknownUser() {
            assertThat(broker.reset(Map.of(
                    "email", "nobody@example.com",
                    "token", "x",
                    "password", "new"), (u, p) -> {}))
                    .isEqualTo(PasswordBroker.Status.INVALID_USER);
        }

        @Test
        @DisplayName("重新申请会替换旧 token —— 旧链接失效")
        void reissueInvalidatesOld() {
            createUser("a@example.com", "x");
            var noThrottle = new PasswordBroker(auth.provider("users"), connection,
                    "password_reset_tokens", Duration.ofMinutes(60), Duration.ZERO);

            var tokens = new ArrayList<String>();
            noThrottle.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> tokens.add(t));
            noThrottle.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> tokens.add(t));

            assertThat(tokens).hasSize(2);
            assertThat(noThrottle.reset(Map.of(
                    "email", "a@example.com", "token", tokens.get(0), "password", "n"),
                    (u, p) -> {})).isEqualTo(PasswordBroker.Status.INVALID_TOKEN);
            assertThat(noThrottle.reset(Map.of(
                    "email", "a@example.com", "token", tokens.get(1), "password", "n"),
                    (u, p) -> {})).isEqualTo(PasswordBroker.Status.RESET);
        }

        @Test
        @DisplayName("空密码被拒,并指出格式校验该在哪做")
        void emptyPasswordRejected() {
            createUser("a@example.com", "x");
            var captured = new ArrayList<String>();
            broker.sendResetLink(Map.of("email", "a@example.com"),
                    (u, t) -> captured.add(t));

            assertThatThrownBy(() -> broker.reset(Map.of(
                    "email", "a@example.com", "token", captured.get(0), "password", ""),
                    (u, p) -> {}))
                    .hasMessageContaining("必须有非空的 password")
                    .hasMessageContaining("进入这里之前完成");
        }

        @Test
        @DisplayName("purgeExpired 只删过期的")
        void purgeExpired() {
            createUser("a@example.com", "x");
            broker.sendResetLink(Map.of("email", "a@example.com"), (u, t) -> {});

            assertThat(broker.purgeExpired()).isZero();

            // created_at 存的是毫秒精度的 ISO 文本,所以要让「现在」
            // 确实晚于它一点,否则同一毫秒内的比较不成立
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var instant = new PasswordBroker(auth.provider("users"), connection,
                    "password_reset_tokens", Duration.ofMillis(1), Duration.ZERO);
            assertThat(instant.purgeExpired()).isEqualTo(1);
        }
    }

    // ================= 签名 URL =================

    @Nested
    @DisplayName("签名 URL")
    class SignedUrls {

        private final UrlSigner signer = new UrlSigner(KEY);

        @Test
        @DisplayName("签名后能校验")
        void roundTrip() {
            String signed = signer.sign("https://app.com/verify/42", Duration.ofHours(1));

            assertThat(signed).contains("signature=").contains("expires=");
            signer.verify(signed);      // 不抛即通过
            assertThat(signer.isValid(signed)).isTrue();
        }

        @Test
        @DisplayName("★ 改任何一个字符都会被发现")
        void tamperingDetected() {
            String signed = signer.sign("https://app.com/verify/42", Duration.ofHours(1));

            // 改路径里的 id —— 这正是「验证别人的邮箱」的攻击形态
            String tampered = signed.replace("/verify/42", "/verify/43");
            assertThat(signer.isValid(tampered)).isFalse();
        }

        @Test
        @DisplayName("★ expires 也在签名范围内 —— 改它无法续期")
        void expiresIsSigned() {
            String signed = signer.sign("https://app.com/x", Duration.ofSeconds(1));

            // 攻击者把过期时间改到很久以后
            String extended = signed.replaceAll("expires=\\d+",
                    "expires=" + (Instant.now().getEpochSecond() + 999_999));

            assertThat(signer.isValid(extended))
                    .as("这是签名 URL 最容易漏掉的一点").isFalse();
        }

        @Test
        @DisplayName("★ 参数顺序不影响校验 —— 邮件客户端与 CDN 会重排")
        void parameterOrderDoesNotMatter() {
            String signed = signer.sign("https://app.com/x?b=2&a=1", Duration.ofHours(1));

            // 手工把参数重排
            var base = signed.substring(0, signed.indexOf('?'));
            var query = signed.substring(signed.indexOf('?') + 1);
            var parts = new ArrayList<>(java.util.List.of(query.split("&")));
            java.util.Collections.reverse(parts);
            String reordered = base + "?" + String.join("&", parts);

            assertThat(signer.isValid(reordered))
                    .as("顺序敏感会让链接「有时有效有时无效」,极难复现")
                    .isTrue();
        }

        @Test
        @DisplayName("过期的链接失效,但能区分「签名对但过期」")
        void expiredLink() {
            // 负 TTL 直接给出一个过去的 expires。
            // 不用 ofMillis(1) + sleep —— expires 是**秒级**时间戳
            // (与 Laravel 一致),毫秒级的 TTL 会被取整到当前秒
            String signed = signer.sign("https://app.com/x", Duration.ofSeconds(-5));

            assertThat(signer.isValid(signed)).isFalse();
            // 签名本身是对的 —— 可以据此给用户「链接过期了,重新发一个」
            assertThat(signer.hasValidSignature(signed)).isTrue();

            assertThatThrownBy(() -> signer.verify(signed))
                    .hasMessageContaining("已过期");
        }

        @Test
        @DisplayName("缺少签名参数时报错说清是什么问题")
        void missingSignature() {
            assertThatThrownBy(() -> signer.verify("https://app.com/x"))
                    .hasMessageContaining("缺少 signature")
                    .hasMessageContaining("换行截断");
        }

        @Test
        @DisplayName("换密钥后已发出的链接全部失效")
        void keyRotationInvalidatesLinks() {
            String signed = signer.sign("https://app.com/x", Duration.ofHours(1));
            var other = new UrlSigner(
                    "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

            assertThat(other.isValid(signed)).isFalse();
        }

        @Test
        @DisplayName("过短的密钥在构造时被拒")
        void weakKeyRejected() {
            assertThatThrownBy(() -> new UrlSigner("short".getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("至少 32 字节")
                    .hasMessageContaining("伪造任意验证链接");
        }
    }

    // ================= 邮箱验证 =================

    @Nested
    @DisplayName("邮箱验证")
    class Verification {

        private final EmailVerification verification =
                new EmailVerification(new UrlSigner(KEY), KEY);

        @Test
        @DisplayName("生成链接 → 校验 → 标记已验证")
        void happyPath() {
            var user = createUser("a@example.com", "x");
            assertThat(EmailVerification.isVerified(principal(user))).isFalse();

            String url = verification.verificationUrl("https://app.com", principal(user));
            String hash = hashSegmentOf(url);

            var result = verification.verify(url, principal(user), hash, u -> {
                User model = (User) u.unwrap();
                model.emailVerifiedAt = Instant.now();
                model.save();
            });

            assertThat(result).isEqualTo(EmailVerification.Result.VERIFIED);
            assertThat(EmailVerification.isVerified(principal(User.findOrFail(user.id))))
                    .isTrue();
        }

        @Test
        @DisplayName("★ 申请验证后改了邮箱 → 旧链接失效(账号接管路径)")
        void emailChangeInvalidatesLink() {
            var user = createUser("a@example.com", "x");
            String url = verification.verificationUrl("https://app.com", principal(user));
            String hash = hashSegmentOf(url);

            // 用户把邮箱改成别人的地址
            user.email = "victim@example.com";
            user.save();

            var result = verification.verify(url, principal(user), hash, u -> {
                throw new AssertionError("""
                        不该把新邮箱标记为已验证 —— 那个地址从未收到验证邮件,
                        配合「验证邮箱后可改密码」之类的流程就是账号接管。""");
            });

            assertThat(result).isEqualTo(EmailVerification.Result.EMAIL_CHANGED);
        }

        @Test
        @DisplayName("篡改链接后失效")
        void tamperedLink() {
            var user = createUser("a@example.com", "x");
            String url = verification.verificationUrl("https://app.com", principal(user));

            String tampered = url.replace("/verify/" + user.id, "/verify/999");
            assertThat(verification.verify(tampered, principal(user), hashSegmentOf(url),
                    u -> { throw new AssertionError("不该通过"); }))
                    .isEqualTo(EmailVerification.Result.INVALID_LINK);
        }

        @Test
        @DisplayName("重复点击链接不算错误")
        void alreadyVerifiedIsNotAnError() {
            var user = createUser("a@example.com", "x");
            user.emailVerifiedAt = Instant.now();
            user.save();

            String url = verification.verificationUrl("https://app.com", principal(user));

            assertThat(verification.verify(url, principal(user), hashSegmentOf(url), u -> {
                throw new AssertionError("已验证不该再次标记");
            })).isEqualTo(EmailVerification.Result.ALREADY_VERIFIED);
        }

        @Test
        @DisplayName("没有 @MustVerifyEmail 的模型一律视为已验证")
        void modelsWithoutAnnotationAreVerified() {
            var admin = createAdmin("admin@example.com", "x");
            var principal = auth.provider("admins").retrieveById(admin.id).orElseThrow();

            assertThat(EmailVerification.isVerified(principal))
                    .as("否则给它挂 verified 中间件会永久 302,看起来像死循环")
                    .isTrue();
        }

        @Test
        @DisplayName("邮箱 hash 大小写不敏感")
        void emailHashIsCaseInsensitive() {
            assertThat(verification.emailHash("A@Example.COM"))
                    .isEqualTo(verification.emailHash("a@example.com"));
        }

        /** 从 URL 里取出 hash 段 */
        private String hashSegmentOf(String url) {
            String path = url.substring(0, url.indexOf('?'));
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    // ================= 登录节流 =================

    @Nested
    @DisplayName("登录节流")
    class Throttling {

        private LoginThrottle throttle() {
            return new LoginThrottle(new RateLimiter(new Repository(new ArrayStore())),
                                     3, 5, Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("★ 失败达到阈值后锁定,带 Retry-After")
        void locksAfterFailures() {
            var t = throttle();

            for (int i = 0; i < 3; i++) {
                t.guardAttempt("a@example.com", "1.2.3.4");   // 不该抛
                t.recordFailure("a@example.com", "1.2.3.4");
            }

            assertThatThrownBy(() -> t.guardAttempt("a@example.com", "1.2.3.4"))
                    .isInstanceOf(io.jaravel.http.HttpException.class)
                    .satisfies(e -> {
                        var http = (io.jaravel.http.HttpException) e;
                        assertThat(http.status()).isEqualTo(429);
                        assertThat(http.headers()).containsKey("Retry-After");
                    })
                    .hasMessageContaining("该账号");
        }

        @Test
        @DisplayName("★ 成功登录清零 —— 否则正常用户多登几次就被锁")
        void successClearsCounter() {
            var t = throttle();

            t.recordFailure("a@example.com", "1.2.3.4");
            t.recordFailure("a@example.com", "1.2.3.4");
            assertThat(t.remainingAttempts("a@example.com")).isEqualTo(1);

            t.clear("a@example.com", "1.2.3.4");
            assertThat(t.remainingAttempts("a@example.com")).isEqualTo(3);
            t.guardAttempt("a@example.com", "1.2.3.4");    // 不该抛
        }

        @Test
        @DisplayName("★ 账号与 IP 分别计数 —— 分别挡撞库与喷洒")
        void accountAndIpCountedSeparately() {
            var t = throttle();

            // 五个不同账号,同一个 IP —— 喷洒形态
            for (int i = 0; i < 5; i++) {
                t.recordFailure("user" + i + "@example.com", "9.9.9.9");
            }

            // 第六个账号在这个 IP 上被 IP 阈值挡住
            assertThatThrownBy(() -> t.guardAttempt("fresh@example.com", "9.9.9.9"))
                    .hasMessageContaining("该 IP");

            // 但换个 IP 就能继续 —— 证明两个维度是独立的
            t.guardAttempt("fresh@example.com", "8.8.8.8");
        }

        @Test
        @DisplayName("★ 账号键大小写不敏感 —— 否则改大小写就能重置计数器")
        void accountKeyIsCaseInsensitive() {
            var t = throttle();

            t.recordFailure("A@Example.com", "1.2.3.4");
            t.recordFailure("a@example.COM", "1.2.3.4");
            t.recordFailure("A@EXAMPLE.COM", "1.2.3.4");

            assertThatThrownBy(() -> t.guardAttempt("a@example.com", "1.2.3.4"))
                    .hasMessageContaining("该账号");
        }

        @Test
        @DisplayName("锁定是临时的 —— 窗口过后自动解除")
        void lockoutIsTemporary() throws InterruptedException {
            var t = new LoginThrottle(new RateLimiter(new Repository(new ArrayStore())),
                                      2, 10, Duration.ofMillis(100));

            t.recordFailure("a@example.com", "1.2.3.4");
            t.recordFailure("a@example.com", "1.2.3.4");
            assertThat(t.isLocked("a@example.com", "1.2.3.4")).isTrue();

            Thread.sleep(160);
            assertThat(t.isLocked("a@example.com", "1.2.3.4"))
                    .as("永久锁定会变成一个任何人都能发起的拒绝服务")
                    .isFalse();
        }

        @Test
        @DisplayName("lockoutSeconds 取两个维度的较大值")
        void lockoutSecondsTakesMax() {
            var t = throttle();
            t.recordFailure("a@example.com", "1.2.3.4");

            assertThat(t.lockoutSeconds("a@example.com", "1.2.3.4")).isBetween(1L, 60L);
        }
    }
}
