package net.dobeen.jaravel.broadcasting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Broadcast 门面:emit 与断言(docs/19 §2.3 / §6.6)。
 */
class BroadcastFacadeTest {

    @BeforeEach
    void setUp() {
        Broadcast.use(new BroadcastManager());
    }

    @AfterEach
    void tearDown() {
        Broadcast.reset();
    }

    @Test
    @DisplayName("emit 直接推一条消息")
    void emitPublishes() {
        Broadcast.fake();

        Broadcast.emit("orders", "refresh", Map.of("at", "now"));

        Broadcast.assertBroadcastedTo("orders", "refresh");
    }

    @Test
    @DisplayName("emit 不带 payload 的便捷写法")
    void emitWithoutPayload() {
        Broadcast.fake();

        Broadcast.emit("orders", "ping");

        Broadcast.assertBroadcastedTo("orders", "ping");
    }

    @Test
    @DisplayName("断言:频道对、事件名错 → 报错并列出该频道实际收到的")
    void assertionMismatchShowsChannelHistory() {
        Broadcast.fake();
        Broadcast.emit("orders", "refresh");

        assertThatThrownBy(() -> Broadcast.assertBroadcastedTo("orders", "other"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("refresh");
    }

    @Test
    @DisplayName("断言:频道对但从未广播 → 列出全部广播")
    void assertionOnEmptyChannelShowsAll() {
        Broadcast.fake();
        Broadcast.emit("orders", "refresh");

        assertThatThrownBy(() -> Broadcast.assertBroadcastedTo("chat"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("orders");
    }

    @Test
    @DisplayName("断言:什么都没广播")
    void assertNothingBroadcasted() {
        Broadcast.fake();

        Broadcast.assertNothingBroadcasted();
    }

    @Test
    @DisplayName("不在 fake 模式下用断言 → 明确报错")
    void assertionWithoutFakeFailsLoud() {
        assertThatThrownBy(() -> Broadcast.assertBroadcastedTo("orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Broadcast.fake()");
    }
}
