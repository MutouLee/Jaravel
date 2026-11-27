package net.dobeen.jaravel.broadcasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.broadcasting.channel.BroadcastChannel;
import net.dobeen.jaravel.notifications.Notification;
import net.dobeen.jaravel.notifications.NotificationChannel;
import net.dobeen.jaravel.notifications.Notifiable;

/**
 * 通知 broadcast 渠道(docs/19 §4)。
 */
class BroadcastChannelTest {

    static class User implements Notifiable, BroadcastNotifiable {
        @Override public Object notifiableId() {
            return 42L;
        }

        @Override public String notifiableType() {
            return "users";
        }

        @Override public String routeNotificationFor(String channel) {
            return null;
        }

        @Override public String receivesBroadcastNotificationsOn() {
            return null;   // 默认推导
        }
    }

    static class Admin extends User {
        @Override public String receivesBroadcastNotificationsOn() {
            return "private-admin." + notifiableId();
        }
    }

    static class Rude implements Notifiable {
        @Override public String routeNotificationFor(String channel) {
            return null;
        }
        // 不实现 notifiableId()
    }

    static class OrderShipped extends Notification implements HasBroadcast {
        @Override public List<String> via(Notifiable n) {
            return List.of("broadcast");
        }

        @Override public BroadcastMessage toBroadcast(Notifiable n) {
            return BroadcastMessage.of(Map.of("orderId", 1L));
        }
    }

    static class NoBroadcast extends Notification {
        @Override public List<String> via(Notifiable n) {
            return List.of("broadcast");
        }
    }

    @Nested
    @DisplayName("频道推导")
    class ChannelDerivation {

        @Test
        @DisplayName("默认 private-{notifiableType}.{notifiableId}")
        void defaultChannel() {
            assertThat(BroadcastChannel.channelOf(new User()))
                    .isEqualTo("private-users.42");
        }

        @Test
        @DisplayName("BroadcastNotifiable 覆盖")
        void overriddenChannel() {
            assertThat(BroadcastChannel.channelOf(new Admin()))
                    .isEqualTo("private-admin.42");
        }

        @Test
        @DisplayName("没有 notifiableId → 报错并给出两种修法")
        void missingIdFailsLoud() {
            assertThatThrownBy(() -> BroadcastChannel.channelOf(new Rude()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("notifiableId")
                    .hasMessageContaining("BroadcastNotifiable");
        }
    }

    @Nested
    @DisplayName("投递")
    class Delivery {

        @Test
        @DisplayName("推送到收件人的私有频道,事件名是通知的 displayName")
        void publishesToNotifiableChannel() {
            var manager = new BroadcastManager().fake();
            var channel = new BroadcastChannel(manager);

            channel.send(new User(), new OrderShipped());

            var entry = manager.recorder().all().get(0);
            assertThat(entry.channel()).isEqualTo("private-users.42");
            assertThat(entry.event()).isEqualTo("OrderShipped");
            assertThat(entry.data()).containsEntry("orderId", 1L);
        }

        @Test
        @DisplayName("via() 里有 broadcast 但没实现 HasBroadcast → require() 报错")
        void missingHasBroadcastFailsLoud() {
            var channel = new BroadcastChannel(new BroadcastManager());

            assertThatThrownBy(() -> channel.send(new User(), new NoBroadcast()))
                    .isInstanceOf(NotificationChannel.MissingChannelMethodException.class)
                    .hasMessageContaining("HasBroadcast");
        }

        @Test
        @DisplayName("requiresRoute = false —— 频道由渠道推导,不走 routeNotificationFor")
        void requiresRouteFalse() {
            assertThat(new BroadcastChannel(new BroadcastManager()).requiresRoute()).isFalse();
        }
    }
}
