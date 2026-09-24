package com.ragagent.im.wechat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * 微信（个人号）iLink 机器人渠道工厂——对照 Go {@code internal/im/wechat/factory.go} L13-39。
 *
 * <p><b>只有长轮询</b>：照 Go 不读 mode（无分支），凭据必须给 {@code bot_token} +
 * {@code ilink_bot_id}（缺任一报 "wechat credentials require bot_token and ilink_bot_id"）。</p>
 */
public class WechatAdapterFactory implements ImService.AdapterFactory {

    private final SsrfGuard ssrfGuard;
    private final String baseUrl;

    public WechatAdapterFactory(SsrfGuard ssrfGuard) {
        this(ssrfGuard, null);
    }

    /** {@code baseUrl} 非空时指向本地 stub（iLink 基址的测试口）。 */
    public WechatAdapterFactory(SsrfGuard ssrfGuard, String baseUrl) {
        this.ssrfGuard = ssrfGuard;
        this.baseUrl = baseUrl;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);

        String botToken = ImCredentials.getString(creds, "bot_token");
        String ilinkBotId = ImCredentials.getString(creds, "ilink_bot_id");
        if (botToken.isEmpty() || ilinkBotId.isEmpty()) {
            throw new IllegalArgumentException(
                    "wechat credentials require bot_token and ilink_bot_id");
        }

        WechatAdapter adapter = new WechatAdapter(botToken, ilinkBotId, baseUrl, ssrfGuard);
        WechatLongPollClient client = new WechatLongPollClient(botToken, ilinkBotId, baseUrl,
                ssrfGuard, channel.getId(),
                (msg, cid) -> msgHandler.accept(msg, cid));
        Thread thread = new Thread(client::start, "im-wechat-poll-" + channel.getId());
        thread.setDaemon(true);
        thread.start();
        return new ImService.AdapterRegistration(adapter, client::stop);
    }
}
