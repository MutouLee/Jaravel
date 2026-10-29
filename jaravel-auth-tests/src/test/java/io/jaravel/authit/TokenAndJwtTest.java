package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.guard.Jwt;
import io.jaravel.auth.guard.JwtGuard;
import io.jaravel.auth.guard.TokenGuard;
import io.jaravel.cache.Repository;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.authit.models.User;

/** API Token 与 JWT */
class TokenAndJwtTest extends AuthTestBase {

    private io.jaravel.database.Connection connection;

    @BeforeEach
    void setUpTokenTable() throws SQLException {
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
        }
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
    }

    private Authenticatable principal(User user) {
        return auth.provider("users").retrieveById(user.id).orElseThrow();
    }

    // ================= TokenGuard =================

    @Nested
    @DisplayName("API Token")
    class ApiToken {

        @Test
        @DisplayName("签发后能用它认证")
        void issueAndAuthenticate() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            var token = guard.issue(principal(user), "CI 部署");
            assertThat(token.plainText()).contains("|");

            guard.presentToken(token.plainText());
            assertThat(guard.check()).isTrue();
            assertThat(guard.id()).contains(user.id);
        }

        @Test
        @DisplayName("★ 库里只存哈希,明文不可从数据库恢复")
        void onlyHashIsStored() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            var token = guard.issue(principal(user), "test");
            String plain = token.plainText().substring(token.plainText().indexOf('|') + 1);

            var row = connection.table("personal_access_tokens")
                    .where("id", token.id()).get().first().orElseThrow();

            assertThat(String.valueOf(row.get("token_hash")))
                    .as("库里不该出现明文").doesNotContain(plain);
            assertThat(String.valueOf(row.get("token_hash"))).hasSize(64);  // SHA-256 hex
        }

        @Test
        @DisplayName("★ NewToken.toString 不泄露明文 —— 它很容易进日志")
        void toStringHidesSecret() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);
            var token = guard.issue(principal(user), "test");

            String plain = token.plainText();
            assertThat(token.toString())
                    .doesNotContain(plain.substring(plain.indexOf('|') + 1))
                    .contains("隐藏");
        }

        @Test
        @DisplayName("伪造或篡改的 token 无效")
        void forgedTokenRejected() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);
            var token = guard.issue(principal(user), "test");
            long id = token.id();

            for (String forged : new String[] {
                    id + "|wrong-secret",
                    id + "|",
                    String.valueOf(id),
                    "999|anything",
                    "garbage",
                    ""}) {
                guard.presentToken(forged);
                assertThat(guard.check()).as("伪造的 \"%s\" 不该通过", forged).isFalse();
            }
        }

        @Test
        @DisplayName("过期的 token 无效")
        void expiredTokenRejected() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            var token = guard.issue(principal(user), "短命",
                                    Instant.now().minusSeconds(1));
            guard.presentToken(token.plainText());

            assertThat(guard.check()).isFalse();
        }

        @Test
        @DisplayName("撤销单个与全部")
        void revoke() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            var first = guard.issue(principal(user), "a");
            var second = guard.issue(principal(user), "b");

            guard.revoke(first.id());
            guard.presentToken(first.plainText());
            assertThat(guard.check()).isFalse();

            guard.presentToken(second.plainText());
            assertThat(guard.check()).isTrue();

            assertThat(guard.revokeAll(principal(user))).isEqualTo(1);
            guard.presentToken(second.plainText());
            assertThat(guard.check()).isFalse();
        }

        @Test
        @DisplayName("purgeExpired 只删过期的")
        void purgeExpired() {
            var user = createUser("a@example.com", "x");
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            guard.issue(principal(user), "活的", Instant.now().plusSeconds(3600));
            guard.issue(principal(user), "死的", Instant.now().minusSeconds(1));
            guard.issue(principal(user), "永久", null);

            assertThat(guard.purgeExpired()).isEqualTo(1);
            assertThat(connection.table("personal_access_tokens").count()).isEqualTo(2);
        }

        @Test
        @DisplayName("validate(凭据) 明确不支持,并指出该用什么")
        void validateIsUnsupported() {
            var guard = new TokenGuard("api", auth.provider("users"), connection);

            assertThatThrownBy(() -> guard.validate(Map.of("password", "x")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("presentToken")
                    .hasMessageContaining("会话 guard");
        }
    }

    // ================= JWT =================

    @Nested
    @DisplayName("JWT 的安全防护")
    class JwtSecurity {

        private final byte[] secret = "0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("签发后能校验回来")
        void roundTrip() {
            var jwt = Jwt.hs256(secret);
            String token = jwt.issue(Map.of("sub", 42, "role", "admin"), Duration.ofHours(1));

            var claims = jwt.verify(token);
            assertThat(claims).containsEntry("sub", 42).containsEntry("role", "admin");
            assertThat(claims).containsKeys("iat", "exp", "jti");
        }

        @Test
        @DisplayName("★ alg=none 被拒 —— 攻击者删掉签名的经典手法")
        void algNoneRejected() {
            var jwt = Jwt.hs256(secret);
            String token = jwt.issue(Map.of("sub", 1), Duration.ofHours(1));

            String[] parts = token.split("\\.");
            String forgedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String forged = forgedHeader + "." + parts[1] + ".";

            assertThatThrownBy(() -> jwt.verify(forged))
                    .isInstanceOf(Jwt.InvalidTokenException.class)
                    .hasMessageContaining("alg=none");
        }

        @Test
        @DisplayName("★ RS256 → HS256 算法混淆被拒 —— 用公钥当 HMAC 密钥的手法")
        void algorithmConfusionRejected() throws Exception {
            var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            var rsaJwt = Jwt.rs256(keyPair.getPrivate(), keyPair.getPublic());

            String token = rsaJwt.issue(Map.of("sub", 1), Duration.ofHours(1));
            String[] parts = token.split("\\.");

            // 攻击者把 alg 改成 HS256,用公开的公钥当 HMAC 密钥重签
            String forgedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String forged = forgedHeader + "." + parts[1] + ".anything";

            assertThatThrownBy(() -> rsaJwt.verify(forged))
                    .isInstanceOf(Jwt.InvalidTokenException.class)
                    .hasMessageContaining("RS256 → HS256");
        }

        @Test
        @DisplayName("篡改 payload 后签名校验失败")
        void tamperedPayloadRejected() {
            var jwt = Jwt.hs256(secret);
            String token = jwt.issue(Map.of("sub", 1, "role", "user"), Duration.ofHours(1));

            String[] parts = token.split("\\.");
            String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"sub\":1,\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> jwt.verify(parts[0] + "." + forgedPayload + "." + parts[2]))
                    .hasMessageContaining("签名校验失败");
        }

        @Test
        @DisplayName("换密钥后校验失败")
        void wrongSecretRejected() {
            String token = Jwt.hs256(secret).issue(Map.of("sub", 1), Duration.ofHours(1));
            var other = Jwt.hs256("fedcba9876543210fedcba9876543210"
                    .getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> other.verify(token))
                    .hasMessageContaining("签名校验失败");
        }

        @Test
        @DisplayName("★ 过短的 HS256 密钥在构造时就被拒")
        void weakSecretRejected() {
            assertThatThrownBy(() -> Jwt.hs256("short".getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("至少 32 字节")
                    .hasMessageContaining("离线爆破");
        }

        @Test
        @DisplayName("过期与未生效")
        void timeClaims() {
            var jwt = Jwt.hs256(secret).withLeeway(Duration.ZERO);

            String expired = jwt.issue(
                    Map.of("sub", 1, "exp", Instant.now().minusSeconds(10).getEpochSecond()),
                    null);
            assertThatThrownBy(() -> jwt.verify(expired)).hasMessageContaining("已过期");

            String notYet = jwt.issue(
                    Map.of("sub", 1, "nbf", Instant.now().plusSeconds(600).getEpochSecond()),
                    null);
            assertThatThrownBy(() -> jwt.verify(notYet)).hasMessageContaining("还未生效");
        }

        @Test
        @DisplayName("时钟偏差容忍 —— 分布式环境里时钟不会完全同步")
        void leewayToleratesClockSkew() {
            var jwt = Jwt.hs256(secret).withLeeway(Duration.ofSeconds(60));
            String justExpired = jwt.issue(
                    Map.of("sub", 1, "exp", Instant.now().minusSeconds(10).getEpochSecond()),
                    null);

            // 刚过期 10 秒,在 60 秒容忍内
            assertThat(jwt.verify(justExpired)).containsEntry("sub", 1);
        }

        @Test
        @DisplayName("结构损坏的 token 给出清楚的报错")
        void malformedTokens() {
            var jwt = Jwt.hs256(secret);

            assertThatThrownBy(() -> jwt.verify("a.b")).hasMessageContaining("三段");
            assertThatThrownBy(() -> jwt.verify("")).hasMessageContaining("为空");
            assertThatThrownBy(() -> jwt.verify("!!!.!!!.!!!"))
                    .hasMessageContaining("解析失败");
        }

        @Test
        @DisplayName("RS256 往返")
        void rs256RoundTrip() throws Exception {
            var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            var jwt = Jwt.rs256(keyPair.getPrivate(), keyPair.getPublic());

            String token = jwt.issue(Map.of("sub", 7), Duration.ofHours(1));
            assertThat(jwt.verify(token)).containsEntry("sub", 7);
        }

        @Test
        @DisplayName("只有公钥时能校验不能签发")
        void publicKeyOnlyCannotIssue() throws Exception {
            var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            String token = Jwt.rs256(keyPair.getPrivate(), keyPair.getPublic())
                    .issue(Map.of("sub", 1), Duration.ofHours(1));

            var verifyOnly = Jwt.rs256(null, keyPair.getPublic());
            assertThat(verifyOnly.verify(token)).containsEntry("sub", 1);

            assertThatThrownBy(() -> verifyOnly.issue(Map.of("sub", 2), Duration.ofHours(1)))
                    .hasMessageContaining("只能校验不能签发");
        }

        @Test
        @DisplayName("requiredClaims 缺失时报错")
        void requiredClaims() {
            var jwt = Jwt.hs256(secret);
            String token = jwt.issue(Map.of("sub", 1), Duration.ofHours(1));

            assertThatThrownBy(() -> jwt.verify(token, java.util.Set.of("tenant")))
                    .hasMessageContaining("缺少必需的 claim \"tenant\"");
        }
    }

    // ================= JwtGuard =================

    @Nested
    @DisplayName("JwtGuard")
    class JwtGuardBehaviour {

        private final byte[] secret = "0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("签发后能认证,且默认查库")
        void issueAndAuthenticate() {
            var user = createUser("a@example.com", "x");
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1));

            String token = guard.issue(principal(user));
            guard.presentToken(token);

            assertThat(guard.check()).isTrue();
            assertThat(guard.user().flatMap(u -> u.as(User.class))).get()
                    .extracting(u -> u.email).isEqualTo("a@example.com");
        }

        @Test
        @DisplayName("★ 默认查库,所以删掉用户后 token 立刻失效")
        void deletedUserLosesAccess() {
            var user = createUser("a@example.com", "x");
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1));
            String token = guard.issue(principal(user));

            user.delete();

            guard.presentToken(token);
            assertThat(guard.check())
                    .as("默认模式查库,所以能感知用户已删").isFalse();
        }

        @Test
        @DisplayName("无状态模式不查库 —— 代价是删掉用户后仍然有效")
        void statelessModeDoesNotQuery() {
            var user = createUser("a@example.com", "x");
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1))
                    .statelessMode();
            String token = guard.issue(principal(user));

            user.delete();

            guard.presentToken(token);
            // 这正是无状态模式的取舍,测试把它固定下来以免有人以为是 bug
            assertThat(guard.check()).isTrue();
            assertThat(guard.id()).contains(user.id.intValue());
            // 但拿不到模型 —— 从来没查过库
            assertThat(guard.user().flatMap(u -> u.as(User.class))).isEmpty();
        }

        @Test
        @DisplayName("无效 token = 未认证,不是异常")
        void invalidTokenIsJustUnauthenticated() {
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1));

            guard.presentToken("garbage");
            assertThat(guard.check()).isFalse();

            guard.presentToken(null);
            assertThat(guard.check()).isFalse();
        }

        @Test
        @DisplayName("没有 sub 的 token 被拒")
        void tokenWithoutSubjectRejected() {
            var jwt = Jwt.hs256(secret);
            var guard = new JwtGuard("api", auth.provider("users"), jwt, Duration.ofHours(1));

            guard.presentToken(jwt.issue(Map.of("role", "admin"), Duration.ofHours(1)));
            assertThat(guard.check()).isFalse();
        }

        @Test
        @DisplayName("★ 吊销名单让 JWT 的「登出」真的生效")
        void revocationWorks() {
            var user = createUser("a@example.com", "x");
            var revocations = new Repository(new ArrayStore());
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1), revocations);

            String token = guard.issue(principal(user));
            guard.presentToken(token);
            assertThat(guard.check()).isTrue();

            guard.revokeCurrent();

            guard.presentToken(token);
            assertThat(guard.check()).as("吊销后应失效").isFalse();
        }

        @Test
        @DisplayName("★ 没配吊销名单时,revokeCurrent 报错而不是静默无效")
        void revokeWithoutBlacklistFailsLoud() {
            var user = createUser("a@example.com", "x");
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1));

            guard.presentToken(guard.issue(principal(user)));
            guard.check();

            assertThatThrownBy(guard::revokeCurrent)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("没有配置吊销名单")
                    .hasMessageContaining("刷新令牌");
        }

        @Test
        @DisplayName("自定义 claim 可读")
        void customClaims() {
            var user = createUser("a@example.com", "x");
            var guard = new JwtGuard("api", auth.provider("users"),
                                     Jwt.hs256(secret), Duration.ofHours(1));

            guard.presentToken(guard.issue(principal(user), Map.of("tenant", "acme")));
            guard.check();

            assertThat(guard.claims()).get().asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("tenant", "acme");
        }
    }
}
