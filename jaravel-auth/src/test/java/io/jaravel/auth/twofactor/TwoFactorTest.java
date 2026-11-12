package io.jaravel.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 二步验证的原语(docs/08 §6.4)。
 *
 * <p>时间全部由参数传入 —— RFC 6238 的测试向量是固定的时间点,
 * 而「在正确的当下」测不出窗口边界与跨步长。
 */
class TwoFactorTest {

    /** RFC 6238 的测试密钥:ASCII "12345678901234567890" */
    private static final String RFC_SECRET =
            Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Nested
    @DisplayName("Base32")
    class Base32Test {

        @Test
        @DisplayName("RFC 4648 的测试向量")
        void rfcVectors() {
            assertThat(Base32.encode("foo".getBytes(StandardCharsets.US_ASCII)))
                    .isEqualTo("MZXW6");
            assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)))
                    .isEqualTo("MZXW6YTBOI");
        }

        @Test
        @DisplayName("解码接受填充、空白与小写 —— 用户手输密钥时都常见")
        void decodeIsLenient() {
            assertThat(new String(Base32.decode("mzxw6==="), StandardCharsets.US_ASCII))
                    .isEqualTo("foo");
            assertThat(new String(Base32.decode("MZ XW\n6"), StandardCharsets.US_ASCII))
                    .isEqualTo("foo");
        }

        @Test
        @DisplayName("往返:随机字节 → Base32 → 原样")
        void roundTrip() {
            byte[] bytes = new byte[20];
            new java.security.SecureRandom().nextBytes(bytes);

            assertThat(Base32.decode(Base32.encode(bytes))).isEqualTo(bytes);
        }

        @Test
        @DisplayName("非法字符要报错,而不是静默丢掉")
        void rejectsInvalidChars() {
            assertThatThrownBy(() -> Base32.decode("MZXW1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法的 Base32 字符");
        }
    }

    @Nested
    @DisplayName("TOTP")
    class TotpTest {

        @Test
        @DisplayName("★ RFC 6238 的六组测试向量(8 位)")
        void rfc6238Vectors() {
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(59), 8, 30))
                    .isEqualTo("94287082");
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1111111109), 8, 30))
                    .isEqualTo("07081804");
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1111111111), 8, 30))
                    .isEqualTo("14050471");
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1234567890), 8, 30))
                    .isEqualTo("89005924");
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(2000000000), 8, 30))
                    .isEqualTo("69279037");
            assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(20000000000L), 8, 30))
                    .isEqualTo("65353130");
        }

        @Test
        @DisplayName("★ RFC 4226 的计数器 0:默认 6 位是 755224")
        void defaultSixDigits() {
            assertThat(Totp.code(RFC_SECRET, Instant.EPOCH)).isEqualTo("755224");
        }

        @Test
        @DisplayName("验证容忍前后各一个窗口,再远就不认")
        void verifyWindow() {
            String code = Totp.code(RFC_SECRET, Instant.ofEpochSecond(59));

            assertThat(Totp.verify(RFC_SECRET, code, Instant.ofEpochSecond(59))).isTrue();
            assertThat(Totp.verify(RFC_SECRET, code, Instant.ofEpochSecond(89)))
                    .as("下一个 30 秒窗口仍在容忍范围内")
                    .isTrue();
            assertThat(Totp.verify(RFC_SECRET, code, Instant.ofEpochSecond(119)))
                    .as("两个窗口之外")
                    .isFalse();
            assertThat(Totp.verify(RFC_SECRET, code, Instant.ofEpochSecond(29)))
                    .as("上一个窗口")
                    .isTrue();
        }

        @Test
        @DisplayName("错误的码、非数字、空密钥一律 false")
        void rejectsGarbage() {
            assertThat(Totp.verify(RFC_SECRET, "000000", Instant.ofEpochSecond(59))).isFalse();
            assertThat(Totp.verify(RFC_SECRET, "abcdef", Instant.ofEpochSecond(59))).isFalse();
            assertThat(Totp.verify(RFC_SECRET, "12345", Instant.ofEpochSecond(59))).isFalse();
            assertThat(Totp.verify(null, "123456", Instant.ofEpochSecond(59))).isFalse();
            assertThat(Totp.verify("not base32!", "123456", Instant.ofEpochSecond(59))).isFalse();
        }

        @Test
        @DisplayName("生成的密钥是 20 字节 Base32,并且当下就能验证通过")
        void generatedSecretWorks() {
            String secret = Totp.generateSecret();

            assertThat(Base32.decode(secret)).hasSize(20);

            Instant now = Instant.now();
            assertThat(Totp.verify(secret, Totp.code(secret, now), now)).isTrue();
        }

        @Test
        @DisplayName("otpauth URI 是二维码库的输入")
        void otpauthUri() {
            String uri = Totp.otpauthUri("My App", "tom@example.com", RFC_SECRET);

            assertThat(uri)
                    .startsWith("otpauth://totp/My%20App:tom%40example.com?")
                    .contains("secret=" + RFC_SECRET)
                    .contains("issuer=My%20App")
                    .contains("algorithm=SHA1")
                    .contains("digits=6")
                    .contains("period=30");
        }

        @Test
        @DisplayName("位数与步长的非法值要报错")
        void validatesParameters() {
            assertThatThrownBy(() -> Totp.code(RFC_SECRET, Instant.EPOCH, 4, 30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("6~8");
            assertThatThrownBy(() -> Totp.code(RFC_SECRET, Instant.EPOCH, 6, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("步长");
        }
    }

    @Nested
    @DisplayName("用户侧流程")
    class UserFlow {

        static final class FakeUser implements TwoFactorAuthenticatable {
            private String secret;
            private boolean enabled;
            private List<String> codes = List.of();

            @Override public String twoFactorSecret() { return secret; }
            @Override public void twoFactorSecret(String value) { secret = value; }
            @Override public boolean twoFactorEnabled() { return enabled; }
            @Override public void twoFactorEnabled(boolean value) { enabled = value; }
            @Override public List<String> twoFactorRecoveryCodes() { return codes; }
            @Override public void twoFactorRecoveryCodes(List<String> value) {
                codes = List.copyOf(value);
            }
            @Override public String twoFactorAccount() { return "tom@example.com"; }
        }

        private static final Instant TIME = Instant.ofEpochSecond(1_700_000_000L);

        @Test
        @DisplayName("★ enable:生成密钥与恢复码,但还没启用")
        void enableReturnsSetupOnce() {
            var user = new FakeUser();

            var setup = user.enableTwoFactor("MyApp", "tom@example.com");

            assertThat(setup.secret()).isNotBlank();
            assertThat(Base32.decode(setup.secret())).hasSize(20);
            assertThat(setup.otpauthUri()).startsWith("otpauth://totp/")
                    .contains("tom%40example.com");
            assertThat(setup.recoveryCodes()).hasSize(10);

            assertThat(user.twoFactorEnabled()).as("要等确认").isFalse();
            assertThat(user.twoFactorSecret()).isEqualTo(setup.secret());
            assertThat(user.twoFactorRecoveryCodes())
                    .as("库里存的是哈希,不是明文")
                    .hasSize(10)
                    .doesNotContainAnyElementsOf(setup.recoveryCodes());
        }

        @Test
        @DisplayName("★ confirm 一次后才启用;错误码不启用")
        void confirmEnables() {
            var user = new FakeUser();
            var setup = user.enableTwoFactor();

            assertThat(user.confirmTwoFactor("000000", TIME)).isFalse();
            assertThat(user.twoFactorEnabled()).isFalse();

            assertThat(user.confirmTwoFactor(Totp.code(setup.secret(), TIME), TIME)).isTrue();
            assertThat(user.twoFactorEnabled()).isTrue();
        }

        @Test
        @DisplayName("启用后 verify 才有效,且受时间窗口约束")
        void verifyOnlyWhenEnabled() {
            var user = new FakeUser();
            var setup = user.enableTwoFactor();

            assertThat(user.verifyTwoFactor(Totp.code(setup.secret(), TIME), TIME))
                    .as("未确认启用")
                    .isFalse();

            user.confirmTwoFactor(Totp.code(setup.secret(), TIME), TIME);

            assertThat(user.verifyTwoFactor(Totp.code(setup.secret(), TIME), TIME)).isTrue();
            assertThat(user.verifyTwoFactor(Totp.code(setup.secret(), TIME.plusSeconds(300)), TIME))
                    .as("十个窗口之外")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 恢复码用一次就删,输入的大小写与连字符不影响")
        void recoveryCodesAreSingleUse() {
            var user = new FakeUser();
            var setup = user.enableTwoFactor();

            String code = setup.recoveryCodes().get(0);
            String sloppy = code.toLowerCase().replace("-", "");

            assertThat(user.useRecoveryCode(sloppy)).isTrue();
            assertThat(user.twoFactorRecoveryCodes()).hasSize(9);
            assertThat(user.useRecoveryCode(code))
                    .as("同一个码不能第二次使用")
                    .isFalse();
            assertThat(user.useRecoveryCode("AAAAA-AAAAA")).isFalse();
            assertThat(user.useRecoveryCode("")).isFalse();
        }

        @Test
        @DisplayName("disable 清空密钥、恢复码与启用状态")
        void disableClearsEverything() {
            var user = new FakeUser();
            user.enableTwoFactor();

            user.disableTwoFactor();

            assertThat(user.twoFactorSecret()).isNull();
            assertThat(user.twoFactorEnabled()).isFalse();
            assertThat(user.twoFactorRecoveryCodes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("恢复码")
    class RecoveryCodesTest {

        @Test
        @DisplayName("十个不重复的 ABCDE-FGHIJ 形式")
        void generatesCodes() {
            List<String> codes = RecoveryCodes.generate();

            assertThat(codes).hasSize(10).doesNotHaveDuplicates();
            assertThat(codes).allSatisfy(code ->
                    assertThat(code).matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        }

        @Test
        @DisplayName("★ 库里存哈希;验证接受用户输入的大小写与连字符差异")
        void hashAndVerify() {
            String code = RecoveryCodes.generate(1).get(0);
            String hash = RecoveryCodes.hash(code);

            assertThat(hash).hasSize(64).doesNotContain(RecoveryCodes.normalize(code));
            assertThat(RecoveryCodes.verify(code, hash)).isTrue();
            assertThat(RecoveryCodes.verify(code.toLowerCase(), hash)).isTrue();
            assertThat(RecoveryCodes.verify(code.replace("-", ""), hash)).isTrue();
            assertThat(RecoveryCodes.verify("AAAAA-AAAAA", hash)).isFalse();
            assertThat(RecoveryCodes.verify(null, hash)).isFalse();
        }

        @Test
        @DisplayName("数量必须是正数")
        void validatesCount() {
            assertThatThrownBy(() -> RecoveryCodes.generate(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("大于 0");
        }
    }
}
