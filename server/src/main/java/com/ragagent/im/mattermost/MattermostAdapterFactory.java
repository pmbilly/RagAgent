package com.ragagent.im.mattermost;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * Mattermost 渠道工厂——对照 Go {@code internal/im/mattermost/factory.go} L13-43。
 *
 * <p><b>只支持 webhook</b>（outgoing webhook 入站 + REST 出站），且**默认模式就是
 * webhook**（与其它平台的 websocket 默认相反，照 Go）；非 webhook 报
 * {@code unsupported mattermost mode: X (only webhook is supported)}。</p>
 *
 * <p>凭据：{@code site_url}/{@code bot_token}（经 {@link MattermostClient} 校验：必填 +
 * http(s) + SSRF）、{@code outgoing_token}（<b>必填</b>）、{@code bot_user_id}（可选，
 * 防自环）、{@code post_to_main}（可选，真值则回复发到主时间线而非线程）。</p>
 */
public class MattermostAdapterFactory implements ImService.AdapterFactory {

    private final SsrfGuard ssrfGuard;

    public MattermostAdapterFactory(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);

        String mode = ImCredentials.resolveMode(channel, "webhook");
        if (!"webhook".equals(mode)) {
            throw new IllegalArgumentException("unsupported mattermost mode: " + mode
                    + " (only webhook is supported)");
        }

        String siteUrl = ImCredentials.getString(creds, "site_url");
        String botToken = ImCredentials.getString(creds, "bot_token");
        String outgoingToken = ImCredentials.getString(creds, "outgoing_token");
        String botUserId = ImCredentials.getString(creds, "bot_user_id");
        if (outgoingToken.isEmpty()) {
            throw new IllegalArgumentException("mattermost outgoing_token is required");
        }

        MattermostClient client = new MattermostClient(siteUrl, botToken, ssrfGuard);
        boolean postToMain = ImCredentials.getBool(creds, "post_to_main");
        MattermostAdapter adapter = new MattermostAdapter(client, outgoingToken, botUserId,
                postToMain);
        // 照 Go：返回空的 cancel（无长连接）
        return new ImService.AdapterRegistration(adapter, () -> { });
    }
}
