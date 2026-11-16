package io.jaravel.broadcasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 频道模式:编译与匹配(docs/19 §3.2)。
 */
class ChannelPatternTest {

    @Nested
    @DisplayName("匹配")
    class Matching {

        @Test
        @DisplayName("字面模式:段数与内容都相等才匹配")
        void literalMatchesExactly() {
            var pattern = ChannelPattern.compile("orders");

            assertThat(pattern.match("orders")).isEqualTo(java.util.Map.of());
            assertThat(pattern.match("orders.detail")).isNull();
            assertThat(pattern.match("other")).isNull();
        }

        @Test
        @DisplayName("{name} 捕获一个非空段")
        void captureMatchesOneSegment() {
            var pattern = ChannelPattern.compile("private-orders.{orderId}");

            assertThat(pattern.match("private-orders.42"))
                    .containsEntry("orderId", "42");
            assertThat(pattern.match("private-orders.42.items")).isNull();
            assertThat(pattern.match("public-orders.42")).isNull();
        }

        @Test
        @DisplayName("* 匹配任意剩余段,以点连接放进 \"*\" 参数")
        void wildcardMatchesRemainder() {
            var pattern = ChannelPattern.compile("private-users.*");

            assertThat(pattern.match("private-users.42"))
                    .containsEntry("*", "42");
            assertThat(pattern.match("private-users.42.orders"))
                    .containsEntry("*", "42.orders");
            assertThat(pattern.match("private-users")).isNull();
            assertThat(pattern.match("public-users.42")).isNull();
        }

        @Test
        @DisplayName("捕获与 * 可以组合")
        void captureAndWildcard() {
            var pattern = ChannelPattern.compile("presence-chat.{room}.{user}");

            var params = pattern.match("presence-chat.lobby.7");
            assertThat(params).containsEntry("room", "lobby").containsEntry("user", "7");
        }
    }

    @Nested
    @DisplayName("校验")
    class Validation {

        @Test
        @DisplayName("* 只能出现在末尾")
        void wildcardMustBeLast() {
            assertThatThrownBy(() -> ChannelPattern.compile("*.orders"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只能出现在末尾");
        }

        @Test
        @DisplayName("{name} 的名字限制字符集")
        void captureNameMustBeSimple() {
            assertThatThrownBy(() -> ChannelPattern.compile("x.{a-b}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("字母、数字、下划线");
        }

        @Test
        @DisplayName("频道名:非法字符、空段、超长都被拒绝")
        void channelNameConstraints() {
            assertThatThrownBy(() -> ChannelPattern.validate("bad name"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法字符");
            assertThatThrownBy(() -> ChannelPattern.validate("a..b"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("空段");
            assertThatThrownBy(() -> ChannelPattern.validate(".a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("空段");
            assertThatThrownBy(() -> ChannelPattern.validate("a".repeat(201)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("太长");

            // 合法形态不抛
            ChannelPattern.validate("orders");
            ChannelPattern.validate("private-orders.42");
            ChannelPattern.validate("presence-chat.lobby");
            ChannelPattern.validate("A-1_b.c-2");
        }

        @Test
        @DisplayName("空名被拒绝")
        void emptyNameRejected() {
            assertThatThrownBy(() -> ChannelPattern.validate(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
