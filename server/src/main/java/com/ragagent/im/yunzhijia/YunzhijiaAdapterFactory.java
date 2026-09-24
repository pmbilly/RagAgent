package com.ragagent.im.yunzhijia;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImService;

/**
 * 云之家渠道工厂——对照 Go {@code internal/im/yunzhijia/factory.go} L14-77。
 *
 * <p>凭据：{@code send_msg_url}（<b>必填</b>）、{@code secret}（配了才验签）、
 * {@code app_id}/{@code app_secret}（下载换 token 用）、
 * {@code allowed_webhook_host_suffix}（出站域名白名单，端点校验要求<b>非空</b>）、
 * {@code timeout_seconds}（正数，数字或数字字符串两形态；缺省 10s）。</p>
 *
 * <p>模式：<b>webhook 是默认</b>；{@code websocket} 由 {@code send_msg_url} 的
 * {@code yzjtoken} 推导 WS 地址（{@code wss://<host>/xuntong/websocket?yzjtoken=…}），
 * 起长连接并把 stop 交给调用方；其它模式报
 * {@code unsupported yunzhijia mode: X}。</p>
 */
public class YunzhijiaAdapterFactory implements ImService.AdapterFactory {

    private final SsrfGuard ssrfGuard;
    private final String authUrl;
    private final String downloadBaseUrl;
    private final boolean allowPrivateHosts;

    public YunzhijiaAdapterFactory(SsrfGuard ssrfGuard) {
        this(ssrfGuard, null, null, false);
    }

    /** 后三个参数是测试口（生产走默认基址 + 严格公网校验）。 */
    public YunzhijiaAdapterFactory(SsrfGuard ssrfGuard, String authUrl, String downloadBaseUrl,
                                   boolean allowPrivateHosts) {
        this.ssrfGuard = ssrfGuard;
        this.authUrl = authUrl;
        this.downloadBaseUrl = downloadBaseUrl;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    @Override
    public ImService.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        byte[] raw = channel.getCredentials() == null
                ? new byte[0] : channel.getCredentials().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> creds = ImCredentials.parseCredentials(raw);

        String sendMsgUrl = ImCredentials.getString(creds, "send_msg_url");
        if (sendMsgUrl.isEmpty()) {
            throw new IllegalArgumentException("yunzhijia send_msg_url is required");
        }
        String secret = ImCredentials.getString(creds, "secret");
        String appId = ImCredentials.getString(creds, "app_id");
        String appSecret = ImCredentials.getString(creds, "app_secret");
        String allowedHostSuffix = ImCredentials.getString(creds, "allowed_webhook_host_suffix");
        int timeoutSeconds = positiveIntCredential(creds, "timeout_seconds", 10);

        YunzhijiaAdapter adapter = new YunzhijiaAdapter(sendMsgUrl, secret, appId, appSecret,
                timeoutSeconds, allowedHostSuffix, ssrfGuard, authUrl, downloadBaseUrl,
                allowPrivateHosts);
        adapter.validateSendUrl();

        String mode = ImCredentials.resolveMode(channel, "webhook");
        switch (mode) {
            case "webhook":
                return new ImService.AdapterRegistration(adapter, null);
            case "websocket": {
                String wsUrl = YunzhijiaUrl.deriveWebSocketUrl(sendMsgUrl, allowedHostSuffix);
                YunzhijiaLongConnClient client = new YunzhijiaLongConnClient(wsUrl,
                        channel.getId(), (msg, cid) -> msgHandler.accept(msg, cid));
                Thread thread = new Thread(client::start, "im-yzj-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                return new ImService.AdapterRegistration(adapter, client::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported yunzhijia mode: " + mode);
        }
    }

    /** 对照 {@code positiveIntCredential}：数字或数字字符串且 > 0 才用，否则回落。 */
    static int positiveIntCredential(Map<String, Object> creds, String key, int fallback) {
        Object value = creds == null ? null : creds.get(key);
        if (value instanceof Number number) {
            long parsed = number.longValue();
            return parsed > 0 ? (int) parsed : fallback;
        }
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
