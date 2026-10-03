package com.ragagent.im.wecom;

import java.net.http.HttpClient;
import java.time.Duration;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * 企业微信长连接（智能机器人）适配器。
 *
 * <p>入站三面是<b>明确不支持</b>（消息走 WS 而非 HTTP 回调）：{@code verifyCallback} 返回
 * 异常对象、{@code parseCallback} 抛出、{@code handleURLVerification} 恒 false。
 * 出站与流式全部委托 {@link WecomLongConnClient}；文件下载在
 * {@link WecomSupport#downloadFromUrl} 之后，若消息带逐条 {@code aes_key}
 * 则再做 AES-CBC 解密。</p>
 */
public class WecomWSAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    static final String WEBHOOK_UNSUPPORTED = "WeCom bot adapter does not support webhook callbacks";

    private final WecomLongConnClient client;
    private final HttpClient http;
    private final SsrfGuard ssrfGuard;

    public WecomWSAdapter(WecomLongConnClient client, SsrfGuard ssrfGuard) {
        this.client = client;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_WECOM;
    }

    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        // 长连接模式不支持 HTTP 回调（返回异常对象，由调用点折成响应）
        return new IllegalStateException(WEBHOOK_UNSUPPORTED);
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) {
        throw new IllegalStateException(WEBHOOK_UNSUPPORTED);
    }

    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    // ── 出站（委托长连接） ──────────────────────────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        client.sendReply(incoming, reply.content);
    }

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        return client.startStream(incoming);
    }

    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        client.updateStreamContent(incoming, streamId, fullContent);
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        client.finalizeStream(incoming, streamId, finalContent);
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        client.endStream(incoming, streamId);
    }

    // ── 下载（可能带逐条 aes_key） ──────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("no file URL in message");
        }
        String fileName = msg.fileName == null || msg.fileName.isEmpty() ? msg.fileKey : msg.fileName;

        WecomSupport.Downloaded downloaded = WecomSupport.downloadFromUrl(
                http, msg.fileKey, fileName, client.extraAllowedHost(), ssrfGuard);

        String aesKeyB64 = msg.extra == null ? "" : msg.extra.getOrDefault("aes_key", "");
        if (aesKeyB64.isEmpty()) {
            // 无加密（如 webhook 模式走 media API）→ 原样返回
            return new DownloadedFile(downloaded.content(), downloaded.fileName());
        }
        byte[] decrypted = WecomSupport.decryptAesCbc(downloaded.content(), aesKeyB64);
        return new DownloadedFile(decrypted, downloaded.fileName());
    }
}
