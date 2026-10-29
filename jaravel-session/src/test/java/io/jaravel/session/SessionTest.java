package io.jaravel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.crypt.Encrypter;
import io.jaravel.session.store.CookieStore;
import io.jaravel.session.store.MemoryStore;

/** 会话数据语义与驱动 */
class SessionTest {

    @Nested
    @DisplayName("基本读写")
    class Basics {

        @Test
        @DisplayName("put / get / forget")
        void readWrite() {
            var s = new SessionData("id");

            s.put("name", "alice");
            assertThat(s.get("name", String.class)).contains("alice");
            assertThat(s.has("name")).isTrue();

            s.forget("name");
            assertThat(s.has("name")).isFalse();
        }

        @Test
        @DisplayName("has 与 exists 的区别:显式存了 null")
        void hasVersusExists() {
            var s = new SessionData("id");
            s.put("explicit-null", null);

            assertThat(s.has("explicit-null")).isFalse();
            assertThat(s.exists("explicit-null")).isTrue();
        }

        @Test
        @DisplayName("pull 取出并删除")
        void pull() {
            var s = new SessionData("id");
            s.put("once", "value");

            assertThat(s.pull("once", String.class)).contains("value");
            assertThat(s.exists("once")).isFalse();
        }

        @Test
        @DisplayName("push 追加到数组")
        void push() {
            var s = new SessionData("id");
            s.push("recent", 1);
            s.push("recent", 2);

            assertThat(s.get("recent", List.class)).get()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("push 到非数组键时报错并说清怎么改")
        void pushOnNonArray() {
            var s = new SessionData("id");
            s.put("scalar", "x");

            assertThatThrownBy(() -> s.push("scalar", 1))
                    .hasMessageContaining("不是数组")
                    .hasMessageContaining("forget");
        }

        @Test
        @DisplayName("increment")
        void increment() {
            var s = new SessionData("id");
            assertThat(s.increment("views")).isEqualTo(1);
            assertThat(s.increment("views", 4)).isEqualTo(5);
        }

        @Test
        @DisplayName("没改过就不是 dirty —— 这决定了要不要写回存储")
        void dirtyTracking() {
            var s = new SessionData("id");
            assertThat(s.isDirty()).isFalse();

            s.get("anything");
            assertThat(s.isDirty()).as("只读不该让它变脏").isFalse();

            s.put("k", "v");
            assertThat(s.isDirty()).isTrue();
        }

        @Test
        @DisplayName("保留键不能用 put 写 —— 否则等于静默关掉 CSRF")
        void reservedKeysAreProtected() {
            var s = new SessionData("id");

            assertThatThrownBy(() -> s.put("_token", "attacker-chosen"))
                    .hasMessageContaining("保留的会话键")
                    .hasMessageContaining("关掉 CSRF 防护");

            assertThatThrownBy(() -> s.put("_flash.new", "x"))
                    .hasMessageContaining("保留的会话键");
        }
    }

    @Nested
    @DisplayName("flash 生命周期")
    class Flash {

        @Test
        @DisplayName("flash 的值在**本次**请求就能读到(控制器设、视图读)")
        void readableInSameRequest() {
            var s = new SessionData("id");
            s.flash("status", "已保存");

            assertThat(s.get("status", String.class)).contains("已保存");
        }

        @Test
        @DisplayName("轮转一次后仍可读(下一个请求),轮转两次后消失")
        void survivesExactlyOneRequest() {
            var s = new SessionData("id");
            s.flash("status", "已保存");

            s.ageFlash();      // 第一个请求结束
            assertThat(s.get("status", String.class)).as("下个请求应该读到").contains("已保存");

            s.ageFlash();      // 第二个请求结束
            assertThat(s.get("status", String.class)).as("再下个请求应该没了").isEmpty();
        }

        @Test
        @DisplayName("keep 把指定 flash 再续一个请求")
        void keepExtendsOneMore() {
            var s = new SessionData("id");
            s.flash("status", "x");
            s.ageFlash();

            s.keep("status");
            s.ageFlash();
            assertThat(s.get("status", String.class)).contains("x");

            s.ageFlash();
            assertThat(s.get("status", String.class)).isEmpty();
        }

        @Test
        @DisplayName("reflash 续所有 flash")
        void reflashExtendsAll() {
            var s = new SessionData("id");
            s.flash("a", 1);
            s.flash("b", 2);
            s.ageFlash();

            s.reflash();
            s.ageFlash();

            assertThat(s.get("a")).isPresent();
            assertThat(s.get("b")).isPresent();
        }

        @Test
        @DisplayName("flashInput + oldInput:校验失败后回填表单")
        void flashInputForFormRedisplay() {
            var s = new SessionData("id");
            s.flashInput(Map.of("title", "用户填过的标题", "body", ""));
            s.ageFlash();

            assertThat(s.oldInput("title")).contains("用户填过的标题");
            assertThat(s.oldInput("nope")).isEmpty();
        }

        @Test
        @DisplayName("普通 put 的值不受 flash 轮转影响")
        void regularValuesSurviveAging() {
            var s = new SessionData("id");
            s.put("cart", "持久的");
            s.flash("status", "临时的");

            s.ageFlash();
            s.ageFlash();

            assertThat(s.get("cart", String.class)).contains("持久的");
            assertThat(s.get("status", String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("安全")
    class Security {

        @Test
        @DisplayName("token 稳定,regenerateToken 换新")
        void csrfToken() {
            var s = new SessionData("id");
            String first = s.token();

            assertThat(s.token()).as("同一会话内令牌稳定").isEqualTo(first);
            assertThat(s.regenerateToken()).isNotEqualTo(first);
        }

        @Test
        @DisplayName("★ regenerate 换 id 但保留数据 —— 防会话固定攻击")
        void regeneratePreservesDataChangesId() {
            var s = new SessionData("original-id");
            s.put("cart", "三件商品");
            String oldToken = s.token();

            s.regenerate();

            assertThat(s.id()).isNotEqualTo("original-id");
            assertThat(s.get("cart", String.class)).as("数据必须保留").contains("三件商品");
            // 令牌也必须换:旧令牌可能已被攻击者获知
            assertThat(s.token()).isNotEqualTo(oldToken);
        }

        @Test
        @DisplayName("invalidate 清数据并换 id")
        void invalidateClearsEverything() {
            var s = new SessionData("original-id");
            s.put("cart", "x");

            s.invalidate();

            assertThat(s.id()).isNotEqualTo("original-id");
            assertThat(s.has("cart")).isFalse();
        }

        @Test
        @DisplayName("session id 足够长且随机")
        void idIsStrong() {
            String a = SessionData.generateId();
            String b = SessionData.generateId();

            // 32 字节 base64url ≈ 43 字符
            assertThat(a).hasSizeGreaterThanOrEqualTo(43);
            assertThat(a).isNotEqualTo(b);
            // 只含 URL 安全字符,避免 cookie 编码问题
            assertThat(a).matches("[A-Za-z0-9_-]+");
        }
    }

    @Nested
    @DisplayName("驱动")
    class Stores {

        @Test
        @DisplayName("MemoryStore 读写与销毁")
        void memoryStore() {
            var store = new MemoryStore();

            store.write("sid", Map.of("k", "v"), Duration.ofMinutes(10));
            assertThat(store.read("sid")).isPresent();
            assertThat(store.read("sid").get()).containsEntry("k", "v");

            store.destroy("sid");
            assertThat(store.read("sid")).isEmpty();
        }

        @Test
        @DisplayName("MemoryStore 返回副本 —— 调用方改了不该污染存储")
        void memoryStoreReturnsCopy() {
            var store = new MemoryStore();
            store.write("sid", new java.util.LinkedHashMap<>(Map.of("k", "v")),
                        Duration.ofMinutes(10));

            var read = store.read("sid").orElseThrow();
            read.put("injected", "x");

            assertThat(store.read("sid").orElseThrow()).doesNotContainKey("injected");
        }

        @Test
        @DisplayName("MemoryStore 过期")
        void memoryStoreExpires() throws InterruptedException {
            var store = new MemoryStore();
            store.write("sid", Map.of("k", "v"), Duration.ofMillis(50));

            Thread.sleep(100);
            assertThat(store.read("sid")).isEmpty();
        }

        @Test
        @DisplayName("MemoryStore 声明自己不可共享 —— 多实例部署的警告靠它")
        void memoryStoreIsNotShared() {
            assertThat(new MemoryStore().isShared()).isFalse();
        }

        @Test
        @DisplayName("CookieStore 往返")
        void cookieStoreRoundTrip() {
            var encrypter = Encrypter.fromBase64(Encrypter.generateKey());
            var store = new CookieStore(encrypter);

            CookieStore.clearPending();
            store.write("ignored", Map.of("user_id", 42), Duration.ofMinutes(10));

            String payload = CookieStore.takePending().orElseThrow();
            assertThat(store.read(payload)).isPresent();
            assertThat(store.read(payload).get()).containsEntry("user_id", 42);
        }

        @Test
        @DisplayName("CookieStore 的密文被篡改时视为无会话,而不是报 500")
        void cookieStoreTolerantToTampering() {
            var store = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));

            assertThat(store.read("完全不是密文")).isEmpty();
            assertThat(store.read("")).isEmpty();
            assertThat(store.read(null)).isEmpty();
        }

        @Test
        @DisplayName("CookieStore 换密钥后读不出来,但不抛异常")
        void cookieStoreAfterKeyRotation() {
            var old = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));
            CookieStore.clearPending();
            old.write("x", Map.of("k", "v"), Duration.ofMinutes(10));
            String payload = CookieStore.takePending().orElseThrow();

            var rotated = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));
            assertThat(rotated.read(payload)).isEmpty();
        }

        @Test
        @DisplayName("CookieStore 服务端自己也校验过期 —— 客户端可以留着过期 cookie 继续发")
        void cookieStoreChecksExpiryServerSide() {
            var store = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));

            CookieStore.clearPending();
            store.write("x", Map.of("k", "v"), Duration.ofMillis(-1));
            String payload = CookieStore.takePending().orElseThrow();

            assertThat(store.read(payload)).isEmpty();
        }

        @Test
        @DisplayName("★ 会话过大时报错,而不是让浏览器静默丢弃 cookie")
        void cookieStoreRejectsOversizedSession() {
            var store = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));
            var big = new java.util.LinkedHashMap<String, Object>();
            big.put("cart", "x".repeat(5000));

            CookieStore.clearPending();
            assertThatThrownBy(() -> store.write("x", big, Duration.ofMinutes(10)))
                    .isInstanceOf(CookieStore.SessionTooLargeException.class)
                    .hasMessageContaining("静默丢弃")
                    .hasMessageContaining("随机掉登录")
                    .hasMessageContaining("redis / database");
        }

        @Test
        @DisplayName("CookieStore 对不可序列化的值给出可操作的报错")
        void cookieStoreRejectsUnserializable() {
            var store = new CookieStore(Encrypter.fromBase64(Encrypter.generateKey()));
            var data = new java.util.LinkedHashMap<String, Object>();
            // 裸 Object 没有任何属性,Jackson 默认会报 "No serializer found"。
            // 不用 Thread —— 它有一堆 getter,Jackson 反而能序列化。
            data.put("opaque", new Object());

            CookieStore.clearPending();
            assertThatThrownBy(() -> store.write("x", data, Duration.ofMinutes(10)))
                    .hasMessageContaining("无法序列化成 JSON")
                    .hasMessageContaining("redis / database");
        }
    }

    @Nested
    @DisplayName("配置校验")
    class Config {

        @Test
        @DisplayName("same-site=None 必须 secure —— 否则浏览器直接拒收")
        void sameSiteNoneRequiresSecure() {
            var bad = new SessionConfig("s", Duration.ofMinutes(1), false, "/", null,
                                        false, true, "None", false);

            assertThatThrownBy(bad::validated)
                    .hasMessageContaining("必须 secure=true")
                    .hasMessageContaining("浏览器会直接拒收");
        }

        @Test
        @DisplayName("same-site 只认三个值,并解释各自的影响")
        void sameSiteValues() {
            var bad = new SessionConfig("s", Duration.ofMinutes(1), false, "/", null,
                                        true, true, "lax", false);

            assertThatThrownBy(bad::validated)
                    .hasMessageContaining("只能是 Lax / Strict / None")
                    .hasMessageContaining("从外站链接点进来会掉登录");
        }

        @Test
        @DisplayName("默认值是安全优先的")
        void secureByDefault() {
            var config = SessionConfig.defaults().validated();

            assertThat(config.secure()).isTrue();
            assertThat(config.httpOnly()).isTrue();
            assertThat(config.sameSite()).isEqualTo("Lax");
        }

        @Test
        @DisplayName("expireOnClose 让 cookie 变成会话 cookie")
        void expireOnClose() {
            var config = new SessionConfig("s", Duration.ofMinutes(10), true, "/", null,
                                           true, true, "Lax", false).validated();

            assertThat(config.cookieMaxAgeSeconds()).isEqualTo(-1);
        }
    }
}
