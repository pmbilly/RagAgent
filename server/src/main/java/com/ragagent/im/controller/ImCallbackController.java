package com.ragagent.im.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.service.ImChannelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 平台回调面（W5a，对照 Go routes_agent.go RegisterIMRoutes L284-290 +
 * handler/im.go IMCallback L371-440，2 端点：GET/POST /api/v1/im/callback/{channel_id}）。
 *
 * <h2>鉴权（与 Go 严格一致）</h2>
 * Go 把这两条注册在 **engine 级、Auth 中间件之前**（"IM platforms use their own
 * signature verification"）——无 JWT、无 API Key；AuthFilter 对本前缀整体让路
 * （对照 embed 公开面的既有做法）。RbacInterceptor / APIKeyGate 均不覆盖
 * （Go 侧同样无 RBAC；无 X-API-Key 时网关直通）。
 *
 * <h2>响应形态（plain c.JSON，与 im CRUD 同族——无 success 信封）</h2>
 * <ul>
 *   <li>渠道缺失 → 404 {"error":"channel not found"}</li>
 *   <li>渠道停用 → 503 {"error":"channel is disabled"}</li>
 *   <li>适配器不可用 → 503 {"error":"channel not available"}</li>
 *   <li>URL 验证 / 平台验签 / 消息解析 → 波 5 im 执行体（dev 无真实 IM 平台，
 *       A/B 对已知 platform 的验签分支按部署标 XDEP，约定 §9）</li>
 * </ul>
 */
@RestController
public class ImCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ImCallbackController.class);

    private final ImChannelService imChannelService;

    public ImCallbackController(ImChannelService imChannelService) {
        this.imChannelService = imChannelService;
    }

    @GetMapping("/api/v1/im/callback/{channel_id}")
    public ResponseEntity<Map<String, Object>> callbackGet(
            @PathVariable("channel_id") String channelId) {
        return handle(channelId);
    }

    @PostMapping("/api/v1/im/callback/{channel_id}")
    public ResponseEntity<Map<String, Object>> callbackPost(
            @PathVariable("channel_id") String channelId) {
        return handle(channelId);
    }

    private ResponseEntity<Map<String, Object>> handle(String channelId) {
        ImChannelEntity channel;
        try {
            channel = imChannelService.ensureChannelForCallback(channelId);
        } catch (ImChannelService.CallbackChannelNotFoundException e) {
            log.error("[IM] Channel not found for callback: {}", channelId);
            return plainJson(404, "channel not found");
        } catch (ImChannelService.CallbackChannelDisabledException e) {
            log.error("[IM] Channel disabled for callback: {}", channelId);
            return plainJson(503, "channel is disabled");
        } catch (ImChannelService.CallbackChannelUnavailableException e) {
            log.error("[IM] Channel unavailable for callback {}", channelId);
            return plainJson(503, "channel not available");
        }
        // 不可达：ensureChannelForCallback 对 enabled 渠道抛 Unavailable（波 5 前恒然）。
        // Go 在此处进入 HandleURLVerification → VerifyCallback → ParseCallback → ACK。
        log.info("[IM] Callback received platform={} path_channel_id={}", channel.getPlatform(), channelId);
        return plainJson(503, "channel not available");
    }

    /** 对照 gin 的 c.JSON(status, gin.H{"error": msg})：纯字符串错误体。 */
    private static ResponseEntity<Map<String, Object>> plainJson(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
