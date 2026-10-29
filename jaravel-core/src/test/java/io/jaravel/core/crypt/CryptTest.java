package io.jaravel.core.crypt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 加密与密码哈希 */
class CryptTest {

    @Nested
    @DisplayName("Encrypter(AES-256-GCM)")
    class Encryption {

        private final Encrypter encrypter = Encrypter.fromBase64(Encrypter.generateKey());

        @Test
        @DisplayName("加密后能解回原文")
        void roundTrip() {
            String plain = "身份证 110101199003071234 与 café éü";
            assertThat(encrypter.decrypt(encrypter.encrypt(plain))).isEqualTo(plain);
        }

        @Test
        @DisplayName("同一明文每次产出不同密文 —— 语义安全的要求")
        void sameInputDifferentOutput() {
            String a = encrypter.encrypt("secret");
            String b = encrypter.encrypt("secret");

            assertThat(a).isNotEqualTo(b);
            // 但都能解回同一个明文
            assertThat(encrypter.decrypt(a)).isEqualTo(encrypter.decrypt(b)).isEqualTo("secret");
        }

        @Test
        @DisplayName("篡改密文会被认证标签发现,而不是解出垃圾明文")
        void tamperingIsDetected() {
            String cipher = encrypter.encrypt("转账金额 100");
            // 改掉中间一个字符
            char[] chars = cipher.toCharArray();
            int mid = chars.length / 2;
            chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
            String tampered = new String(chars);

            assertThatThrownBy(() -> encrypter.decrypt(tampered))
                    .isInstanceOf(Encrypter.DecryptionFailedException.class)
                    .hasMessageContaining("完整性校验失败");
        }

        @Test
        @DisplayName("换密钥后解不开,且报错不透露是密钥问题还是篡改")
        void wrongKeyFails() {
            String cipher = encrypter.encrypt("secret");
            var other = Encrypter.fromBase64(Encrypter.generateKey());

            assertThatThrownBy(() -> other.decrypt(cipher))
                    .isInstanceOf(Encrypter.DecryptionFailedException.class);
        }

        @Test
        @DisplayName("tryDecrypt 对解不开的密文返回空 —— 换 APP_KEY 后老 cookie 不该让用户看 500")
        void tryDecryptIsLenient() {
            var other = Encrypter.fromBase64(Encrypter.generateKey());
            assertThat(other.tryDecrypt(encrypter.encrypt("x"))).isEmpty();
            assertThat(other.tryDecrypt("完全不是密文")).isEmpty();
        }

        @Test
        @DisplayName("密钥长度不对时启动就失败,而不是截断凑数")
        void rejectsWrongKeyLength() {
            assertThatThrownBy(() -> new Encrypter(new byte[16]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须是 32 字节")
                    .hasMessageContaining("宁可启动失败");
        }

        @Test
        @DisplayName("APP_KEY 缺失时的报错告诉你怎么生成")
        void missingKeyIsActionable() {
            assertThatThrownBy(() -> Encrypter.fromBase64(null))
                    .hasMessageContaining("APP_KEY 没有设置")
                    .hasMessageContaining("generateKey");

            assertThatThrownBy(() -> Encrypter.fromBase64("not-base64!!!"))
                    .hasMessageContaining("不是合法的 base64");
        }

        @Test
        @DisplayName("密文带版本号,将来换算法时旧数据不会无声失效")
        void ciphertextIsVersioned() {
            byte[] raw = Base64.getUrlDecoder().decode(encrypter.encrypt("x"));
            assertThat(raw[0]).isEqualTo((byte) 1);

            // 伪造一个未来版本
            raw[0] = 99;
            String future = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            assertThatThrownBy(() -> encrypter.decrypt(future))
                    .hasMessageContaining("不认识的密文版本");
        }

        @Test
        @DisplayName("null 原样返回")
        void nullPassesThrough() {
            assertThat(encrypter.encrypt(null)).isNull();
            assertThat(encrypter.decrypt(null)).isNull();
        }

        @Test
        @DisplayName("空串与长文本")
        void edgeSizes() {
            assertThat(encrypter.decrypt(encrypter.encrypt(""))).isEmpty();

            String long_ = "x".repeat(100_000);
            assertThat(encrypter.decrypt(encrypter.encrypt(long_))).isEqualTo(long_);
        }
    }

    @Nested
    @DisplayName("Hasher(PBKDF2-HMAC-SHA512)")
    class Hashing {

        // 迭代次数调低,否则每个断言要跑 200ms+
        private final Hasher hasher = Hasher.forTests();

        @Test
        @DisplayName("正确密码通过,错误密码不通过")
        void makeAndCheck() {
            String hash = hasher.make("correct horse battery staple");

            assertThat(hasher.check("correct horse battery staple", hash)).isTrue();
            assertThat(hasher.check("wrong", hash)).isFalse();
        }

        @Test
        @DisplayName("同一密码每次哈希不同 —— 盐是随机的")
        void saltIsRandom() {
            assertThat(hasher.make("same")).isNotEqualTo(hasher.make("same"));
        }

        @Test
        @DisplayName("哈希里带算法与参数,所以升级参数不会让旧密码失效")
        void hashIsSelfDescribing() {
            String hash = hasher.make("x");

            assertThat(hash).startsWith("$pbkdf2-sha512$i=10000$");
            // 用更高参数的 Hasher 仍能校验旧哈希
            assertThat(new Hasher(20_000).check("x", hash)).isTrue();
        }

        @Test
        @DisplayName("needsRehash 识别出参数已升级")
        void needsRehashOnParameterUpgrade() {
            String old = hasher.make("x");

            assertThat(hasher.needsRehash(old)).isFalse();
            assertThat(new Hasher(20_000).needsRehash(old)).isTrue();
        }

        @Test
        @DisplayName("不认识的哈希格式:校验失败但不抛异常")
        void foreignHashDoesNotThrow() {
            // 从别的系统迁移来的 bcrypt 哈希
            String bcrypt = "$2y$10$abcdefghijklmnopqrstuv";

            assertThat(hasher.check("x", bcrypt)).isFalse();
            // 且应该被标记为「需要重算」,登录成功后可静默升级
            assertThat(hasher.needsRehash(bcrypt)).isTrue();
        }

        @Test
        @DisplayName("中文与 Unicode 密码")
        void unicodePassword() {
            String password = "密码🔒café";
            assertThat(hasher.check(password, hasher.make(password))).isTrue();
        }

        @Test
        @DisplayName("空密码被拒 —— 它该在校验阶段挡住")
        void rejectsEmptyPassword() {
            assertThatThrownBy(() -> hasher.make(""))
                    .hasMessageContaining("不能对空密码做哈希");
            assertThatThrownBy(() -> hasher.make(null))
                    .hasMessageContaining("不能对空密码做哈希");
        }

        @Test
        @DisplayName("超长密码被拒 —— 否则是 CPU 耗尽的攻击面")
        void rejectsOverlongPassword() {
            assertThatThrownBy(() -> hasher.make("x".repeat(5000)))
                    .hasMessageContaining("超过上限")
                    .hasMessageContaining("CPU 耗尽");
        }

        @Test
        @DisplayName("迭代次数过低时拒绝构造")
        void rejectsWeakIterations() {
            assertThatThrownBy(() -> new Hasher(1000))
                    .hasMessageContaining("太低")
                    .hasMessageContaining("测试里显式用低参数");
        }

        @Test
        @DisplayName("check 对 null 返回 false 而不是抛异常")
        void nullSafety() {
            assertThat(hasher.check(null, hasher.make("x"))).isFalse();
            assertThat(hasher.check("x", null)).isFalse();
        }
    }
}
