package com.ragagent.im.dingtalk;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * 钉钉渠道工厂（对照 Go {@code internal/im/dingtalk/factory.go}）。
 *
 * <p>凭据 {@code client_id}/{@code client_secret} 必填，{@code card_template_id} 可选
 * （配了才走 AI 卡片流式，否则退回 sessionWebhook 整段回复）。</p>
 *
 * <p><b>未落地（本子批明确不做）</b>：Go 的 {@code longconn.go} 用钉钉 Stream SDK 的
 * WS 长连接（{@code StreamClient} + {@code BotCallbackDataModel}），且 Go 的默认模式就是
 * websocket——Java 走到 websocket 时明确抛错（不静默假装成功），排 W5γ3 后续子批。</p>
 */
public class DingtalkAdapterFactory implements ImService.AdapterFactory {

    private final SsrfGuard ssrfGuard;
    private final String apiBaseUrl;

    public DingtalkAdapterFactory(SsrfGuard ssrfGuard) {
        this(ssrfGuard, null);
    }

    /** {@code apiBaseUrl} 非空可指向本地 stub（照 Go 的包级 apiBaseURL 变量）。 */
    public DingtalkAdapterFactory(SsrfGuard ssrfGuard, String apiBaseUrl) {
        this.ssrfGuard = ssrfGuard;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);

        DingtalkAdapter adapter = new DingtalkAdapter(
                ImCredentials.getString(creds, "client_id"),
                ImCredentials.getString(creds, "client_secret"),
                ImCredentials.getString(creds, "card_template_id"),
                apiBaseUrl, ssrfGuard);

        String mode = ImCredentials.resolveMode(channel, "websocket");
        switch (mode) {
            case "webhook":
                return new ImService.AdapterRegistration(adapter, null);
            case "websocket":
                throw new UnsupportedOperationException(
                        "dingtalk websocket mode (stream long-connection) not implemented in"
                        + " this batch — tracked as W5γ3 follow-up sub-batch");
            default:
                throw new IllegalArgumentException("unsupported dingtalk mode: " + mode);
        }
    }
}
