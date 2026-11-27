package net.dobeen.jaravel.broadcasting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 频道注册表:鉴权语义(docs/19 §3)。
 */
class ChannelRegistryTest {

    record User(Long id, String name) {
    }

    @Nested
    @DisplayName("鉴权")
    class Authorization {

        @Test
        @DisplayName("未注册的频道 → 拒绝")
        void unregisteredDenied() {
            var registry = new ChannelRegistry();

            var result = registry.authorize("private-orders.42", new User(1L, "a"));

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("没有注册");
        }

        @Test
        @DisplayName("guest 连回调都不跑,直接拒绝")
        void guestDeniedBeforeCallback() {
            var registry = new ChannelRegistry()
                    .channel("private-orders.{orderId}", (User u, Map<String, String> p) -> {
                        throw new AssertionError("guest 不该走到回调里");
                    });

            var result = registry.authorize("private-orders.42", null);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("未登录");
        }

        @Test
        @DisplayName("回调返回 true → 允许,捕获的参数是字符串")
        void allowedWhenCheckPasses() {
            var registry = new ChannelRegistry()
                    .channel("private-orders.{orderId}", (User u, Map<String, String> p) ->
                            u.id().equals(Long.parseLong(p.get("orderId"))));

            assertThat(registry.authorize("private-orders.42", new User(42L, "a")).allowed())
                    .isTrue();
            assertThat(registry.authorize("private-orders.42", new User(7L, "a")).allowed())
                    .isFalse();
        }

        @Test
        @DisplayName("多条规则命中时全部必须通过")
        void allMatchingRulesMustPass() {
            var registry = new ChannelRegistry()
                    .channel("private-user.*", (User u, Map<String, String> p) -> true)
                    .channel("private-user.orders.{id}",
                            (User u, Map<String, String> p) -> false);

            var result = registry.authorize("private-user.orders.9", new User(9L, "a"));

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("private-user.orders");
        }

        @Test
        @DisplayName("回调声明的用户类型与实际不符 → 拒绝并说明")
        void typeMismatchIsDeniedWithReason() {
            var registry = new ChannelRegistry()
                    .channel("private-orders.{orderId}",
                            (User u, Map<String, String> p) -> true);

            var result = registry.authorize("private-orders.1", "not-a-user");

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("类型");
        }

        @Test
        @DisplayName("回调抛异常 → 拒绝,原因里带异常信息")
        void callbackExceptionDenies() {
            var registry = new ChannelRegistry()
                    .channel("private-orders.{orderId}", (User u, Map<String, String> p) -> {
                        throw new IllegalStateException("数据库挂了");
                    });

            var result = registry.authorize("private-orders.1", new User(1L, "a"));

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("数据库挂了");
        }
    }

    @Nested
    @DisplayName("presence")
    class Presence {

        @Test
        @DisplayName("通过时返回成员信息")
        void allowedWithPresenceInfo() {
            var registry = new ChannelRegistry()
                    .presence("presence-chat.{room}",
                            (User u, Map<String, String> p) -> true,
                            (User u) -> Map.of("name", u.name()));

            var result = registry.authorize("presence-chat.lobby", new User(1L, "张三"));

            assertThat(result.allowed()).isTrue();
            assertThat(result.presenceInfo()).containsEntry("name", "张三");
        }

        @Test
        @DisplayName("鉴权不过时 info 回调不跑")
        void infoNotEvaluatedWhenDenied() {
            var registry = new ChannelRegistry()
                    .presence("presence-chat.{room}",
                            (User u, Map<String, String> p) -> false,
                            (User u) -> {
                                throw new AssertionError("deny 时不该取成员信息");
                            });

            var result = registry.authorize("presence-chat.lobby", new User(1L, "张三"));

            assertThat(result.allowed()).isFalse();
        }

        @Test
        @DisplayName("info 回调抛异常 → 订阅仍然成功,成员信息为空")
        void infoFailureDoesNotBlockSubscription() {
            var registry = new ChannelRegistry()
                    .presence("presence-chat.{room}",
                            (User u, Map<String, String> p) -> true,
                            (User u) -> {
                                throw new IllegalStateException("取不到昵称");
                            });

            var result = registry.authorize("presence-chat.lobby", new User(1L, "张三"));

            assertThat(result.allowed()).isTrue();
            assertThat(result.presenceInfo()).isNull();
        }
    }

    @Nested
    @DisplayName("whisper")
    class Whisper {

        @Test
        @DisplayName("默认拒绝,注册后允许")
        void whisperOptIn() {
            var registry = new ChannelRegistry()
                    .channel("private-chat.{room}", (User u, Map<String, String> p) -> true);

            assertThat(registry.whispersAllowed("private-chat.1")).isFalse();

            registry.whisper("private-chat.{room}");

            assertThat(registry.whispersAllowed("private-chat.1")).isTrue();
            assertThat(registry.whispersAllowed("private-chat.2")).isTrue();
            assertThat(registry.whispersAllowed("other.1")).isFalse();
        }
    }
}
