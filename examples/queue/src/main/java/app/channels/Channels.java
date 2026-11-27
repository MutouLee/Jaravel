package app.channels;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.Customer;
import net.dobeen.jaravel.broadcasting.BroadcastChannels;
import net.dobeen.jaravel.broadcasting.ChannelRegistry;

/**
 * 频道的鉴权规则(docs/19 §3)。
 *
 * <p>{@code private-customers.{customerId}}:登录用户只能订阅自己的频道。
 * whisper 允许该频道上的客户端事件(如「正在输入」)。
 */
@Component
public class Channels implements BroadcastChannels {

    @Override
    public void define(ChannelRegistry channels) {
        channels
                .channel("private-customers.{customerId}",
                        (Customer customer, Map<String, String> params) ->
                                customer.id.equals(Long.parseLong(params.get("customerId"))))
                .whisper("private-customers.{customerId}");
    }
}
