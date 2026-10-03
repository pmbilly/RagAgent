package com.ragagent.im.telegram;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Component;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * Telegram 渠道工厂。
 *
 * <p>凭据取 {@code bot_token}（+ webhook 模式下的 {@code secret_token}），
 * 模式由 {@code channel.mode} 决定、缺省 {@code websocket}（长轮询）：</p>
 * <ul>
 *   <li>{@code webhook} → 只出站（回调由 HTTP 面驱动），stop 为 null；</li>
 *   <li>{@code websocket} → 起守护线程跑 {@link TelegramLongPollingClient}，
 *       stop = 停轮询。</li>
 * </ul>
 */
@Component
public class TelegramAdapterFactory implements ImService.AdapterFactory {

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);
        String botToken = ImCredentials.getString(creds, "bot_token");
        String mode = ImCredentials.resolveMode(channel, "websocket");

        switch (mode) {
            case "webhook": {
                String secretToken = ImCredentials.getString(creds, "secret_token");
                return new ImService.AdapterRegistration(
                        new TelegramAdapter(botToken, secretToken), null);
            }
            case "websocket": {
                TelegramLongPollingClient client =
                        new TelegramLongPollingClient(botToken, channel.getId(), msgHandler);
                Thread thread = new Thread(client::start, "im-telegram-poll-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                return new ImService.AdapterRegistration(
                        new TelegramAdapter(botToken, ""), client::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported telegram mode: " + mode);
        }
    }
}
