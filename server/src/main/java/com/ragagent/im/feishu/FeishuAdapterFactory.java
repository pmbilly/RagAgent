package com.ragagent.im.feishu;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * 飞书 / Lark 渠道工厂（对照 Go {@code internal/im/feishu/factory.go}）。
 *
 * <p>HTTP 适配器<b>两种模式都建</b>（websocket 模式下的 SendReply 也走它，照 Go）；
 * 凭据 {@code app_id}/{@code app_secret}/{@code verification_token}/{@code encrypt_key}/
 * {@code api_base_url}（后者经 {@link FeishuAdapter} 校验：http(s) + SSRF，允许明文 http）。</p>
 *
 * <p><b>未落地（本子批明确不做）</b>：Go 的 {@code longconn.go} 369 行用 lark 官方 SDK 的
 * WebSocket 事件流（protobuf 协议）。Java 走到 websocket 时明确抛错（不静默假装成功），
 * 排 W5γ3 后续子批——届时应引入 lark 官方 Java SDK 或用自持 WS 实现。</p>
 */
public class FeishuAdapterFactory implements ImService.AdapterFactory {

    private final FeishuRegion region;
    private final SsrfGuard ssrfGuard;

    public FeishuAdapterFactory(FeishuRegion region, SsrfGuard ssrfGuard) {
        this.region = region == null ? FeishuRegion.FEISHU : region;
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);

        FeishuAdapter adapter;
        try {
            adapter = new FeishuAdapter(region,
                    ImCredentials.getString(creds, "app_id"),
                    ImCredentials.getString(creds, "app_secret"),
                    ImCredentials.getString(creds, "verification_token"),
                    ImCredentials.getString(creds, "encrypt_key"),
                    ImCredentials.getString(creds, "api_base_url"),
                    ssrfGuard);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("create " + region.platform() + " adapter: "
                    + e.getMessage(), e);
        }

        String mode = ImCredentials.resolveMode(channel, "websocket");
        switch (mode) {
            case "webhook":
                return new ImService.AdapterRegistration(adapter, null);
            case "websocket":
                throw new UnsupportedOperationException(region.platform()
                        + " websocket mode (long-connection event stream) not implemented in"
                        + " this batch — tracked as W5γ3 follow-up sub-batch");
            default:
                throw new IllegalArgumentException("unknown " + region.platform() + " mode: "
                        + mode);
        }
    }
}
