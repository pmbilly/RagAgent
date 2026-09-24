package com.ragagent.im.wecom;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * 企业微信渠道工厂（对照 Go {@code internal/im/wecom/factory.go}）。
 *
 * <p>凭据：webhook 模式 {@code corp_id}/{@code agent_secret}/{@code token}/
 * {@code encoding_aes_key}/{@code corp_agent_id}/{@code api_base_url}；
 * websocket（智能机器人长连接，Go 的默认模式）<b>尚未落地</b>——本子批只做 webhook，
 * 走到 websocket 时明确抛错（不静默假装成功），长连接排在 W5γ3 后续子批。</p>
 */
@Component
public class WecomAdapterFactory implements ImService.AdapterFactory {

    private final SsrfGuard ssrfGuard;

    @Autowired
    public WecomAdapterFactory(ObjectProvider<SsrfGuard> ssrfGuard) {
        this(ssrfGuard.getIfAvailable());
    }

    /** 测试用直传构造。 */
    WecomAdapterFactory(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);
        String mode = ImCredentials.resolveMode(channel, "websocket");

        switch (mode) {
            case "webhook": {
                int corpAgentId = intOf(creds.get("corp_agent_id"));
                return new ImService.AdapterRegistration(new WecomWebhookAdapter(
                        ImCredentials.getString(creds, "corp_id"),
                        ImCredentials.getString(creds, "agent_secret"),
                        ImCredentials.getString(creds, "token"),
                        ImCredentials.getString(creds, "encoding_aes_key"),
                        corpAgentId,
                        ImCredentials.getString(creds, "api_base_url"),
                        ssrfGuard), null);
            }
            case "websocket":
                throw new UnsupportedOperationException(
                        "wecom websocket mode (智能机器人长连接) not implemented in this batch"
                                + " — tracked as W5γ3 follow-up sub-batch");
            default:
                throw new IllegalArgumentException("unknown WeCom mode: " + mode);
        }
    }

    /** 对照 Go 的 {@code float64}/{@code int} 两形态取值。 */
    static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
