package com.ragagent.im.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImChannelService;
import com.ragagent.im.service.ImService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * IM 平台回调面（W5a 骨架 + W5γ2 全量接线，对照 Go handler/im.go IMCallback
 * L371-440 + writeIMCallbackACK L339-352，路由 GET/POST /api/v1/im/callback/{channel_id}）。
 *
 * <h2>鉴权（与 Go 严格一致）</h2>
 * Go 把这两条注册在 **engine 级、Auth 中间件之前**（"IM platforms use their own
 * signature verification"）——无 JWT、无 API Key；AuthFilter 对本前缀整体让路
 * （对照 embed 公开面的既有做法）。RbacInterceptor / APIKeyGate 均不覆盖。
 *
 * <h2>响应形态（plain c.JSON，与 im CRUD 同族——无 success 信封）</h2>
 * <ul>
 *   <li>渠道缺失 → 404 {"error":"channel not found"}</li>
 *   <li>渠道停用 → 503 {"error":"channel is disabled"}</li>
 *   <li>适配器不可用（平台无工厂/未启动）→ 503 {"error":"channel not available"}
 *       ——W5a golden 钉住的形态；γ3 每注册一个平台工厂，该平台才走真实路径</li>
 *   <li>URL 验证由适配器直写响应；验签失败 → 403 {"error":"verification failed"}；
 *       解析失败 → 400 {"error":"parse failed"}；非消息事件 → 200 ACK</li>
 *   <li>ACK：yunzhijia → {"success":true,"data":{"type":2,"content":""}}，其余
 *       → {"success":true}（L339-352）</li>
 * </ul>
 */
@RestController
public class ImCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ImCallbackController.class);

    private final ImChannelService imChannelService;
    private final ImService imService;

    public ImCallbackController(ImChannelService imChannelService, ImService imService) {
        this.imChannelService = imChannelService;
        this.imService = imService;
    }

    @GetMapping("/api/v1/im/callback/{channel_id}")
    public void callbackGet(@PathVariable("channel_id") String channelId,
            HttpServletRequest request, HttpServletResponse response) {
        handle(channelId, request, response);
    }

    @PostMapping("/api/v1/im/callback/{channel_id}")
    public void callbackPost(@PathVariable("channel_id") String channelId,
            HttpServletRequest request, HttpServletResponse response) {
        handle(channelId, request, response);
    }

    private void handle(String channelId, HttpServletRequest request, HttpServletResponse response) {
        CallbackExchange.Servlet exchange = new CallbackExchange.Servlet(request, response);

        // 先校验 durable 行（Go EnsureChannelAdapter 的正确性兜底：陈旧凭据不可用）。
        ImChannelEntity channel;
        try {
            channel = imChannelService.ensureChannelForCallback(channelId);
        } catch (ImChannelService.CallbackChannelNotFoundException e) {
            log.error("[IM] Channel not found for callback: {}", channelId);
            writeJson(response, 404, "channel not found");
            return;
        } catch (ImChannelService.CallbackChannelDisabledException e) {
            log.error("[IM] Channel disabled for callback: {}", channelId);
            writeJson(response, 503, "channel is disabled");
            return;
        } catch (ImChannelService.CallbackChannelUnavailableException e) {
            log.error("[IM] Channel unavailable for callback {}", channelId);
            writeJson(response, 503, "channel not available");
            return;
        }

        // 平台工厂/运行态适配器（γ3 注册；未注册 = Go 的 adapter-not-active 分支）。
        Adapter adapter = imService.adapterFor(channel);
        if (adapter == null) {
            log.info("[IM] Callback received platform={} path_channel_id={} — adapter not active",
                    channel.getPlatform(), channelId);
            writeJson(response, 503, "channel not available");
            return;
        }

        log.info("[IM] Callback received platform={} path_channel_id={}",
                channel.getPlatform(), channelId);

        // URL verification（适配器直写响应）
        if (adapter.handleURLVerification(exchange)) {
            return;
        }

        // 平台验签
        Exception verifyErr = adapter.verifyCallback(exchange);
        if (verifyErr != null) {
            log.error("[IM] Callback verification failed for channel {}: {}",
                    channelId, verifyErr.getMessage());
            writeJson(response, 403, "verification failed");
            return;
        }

        // 解析消息
        IncomingMessage msg;
        try {
            msg = adapter.parseCallback(exchange);
        } catch (Exception e) {
            log.error("[IM] Parse callback failed for channel {}: {}", channelId, e.getMessage());
            writeJson(response, 400, "parse failed");
            return;
        }

        if (msg == null) {
            // 非消息事件：只 ACK
            if ("mattermost".equals(channel.getPlatform())) {
                log.info("[IM] Mattermost callback ignored (no message): path_channel_id={} — check trigger word/bot_user_id", channelId);
            } else {
                log.info("[IM] Callback parsed no message to process platform={} path_channel_id={}",
                        channel.getPlatform(), channelId);
            }
            writeAck(response, channel.getPlatform());
            return;
        }

        // 先 ACK 防平台超时，再异步处理（虚拟线程）。
        writeAck(response, channel.getPlatform());
        Thread.startVirtualThread(() -> {
            try {
                imService.handleMessage(msg, channelId);
            } catch (Exception e) {
                log.error("[IM] Handle message error for channel {}: {}", channelId, e.getMessage(), e);
            }
        });
    }

    /** 对照 writeIMCallbackACK（L339-352）。 */
    private static void writeAck(HttpServletResponse response, String platform) {
        if ("yunzhijia".equals(platform)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", 2);
            data.put("content", "");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", data);
            writeRaw(response, 200, body);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        writeRaw(response, 200, body);
    }

    private static void writeJson(HttpServletResponse response, int status, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        writeRaw(response, status, body);
    }

    private static void writeRaw(HttpServletResponse response, int status, Object body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json; charset=utf-8");
            byte[] bytes = CallbackExchange.ImJson.encode(body)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } catch (Exception ignored) {
            // 客户端断开
        }
    }
}
