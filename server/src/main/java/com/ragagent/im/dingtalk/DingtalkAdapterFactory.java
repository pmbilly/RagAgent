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
 * <p>HTTP 适配器<b>两种模式都建</b>（websocket 模式下的回复也走 sessionWebhook/OpenAPI，
 * 照 Go）；{@code websocket} 额外起 {@link DingtalkStreamClient} 消费事件（照 Go 的
 * {@code NewLongConnClient} + {@code RunSupervised}），stop 句柄关连接。</p>
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
            case "websocket": {
                // Stream 模式：HTTP 适配器两种模式都建（回复同样走 sessionWebhook/OpenAPI），
                // 额外起 WS 长连接消费事件（照 Go 的 factory.go + RunSupervised）
                DingtalkStreamClient stream = new DingtalkStreamClient(
                        ImCredentials.getString(creds, "client_id"),
                        ImCredentials.getString(creds, "client_secret"),
                        apiBaseUrl, ssrfGuard, channel.getId(),
                        (msg, cid) -> msgHandler.accept(msg, cid));
                Thread thread = new Thread(stream::start, "im-dingtalk-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                return new ImService.AdapterRegistration(adapter, stream::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported dingtalk mode: " + mode);
        }
    }
}
