package com.ragagent.im.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.service.ImChannelService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 渠道 CRUD + 微信扫码状态面。

 * <p><b>响应形态</b>：CRUD 的信封是 {@code {"data": …}}（**没有**
 * success 键）；删除是 {@code {"success": true}}；渠道行按实体字段声明序输出；
 * 列表行按 IMChannelSummary / ChannelWithAgent 各自的字段序输出（三套键序并存）。</p>
 *
 * <p><b>接缝（不实现）</b>：{@code POST /wechat/qrcode} 的真实 iLink 出站（GetLoginQRCode）
 * 与 {@code /wechat/qrcode/status} 的轮询出站（PollQRCodeStatus）是外部微信集成，
 * 本仓不实现外呼。Java 侧保留绑定分支（qrcode 必填 → 400 "qrcode is required"）
 * 与错误形态（500 固定文案），外呼本身抛接缝异常；
 * {@code /im/callback/:channel_id} 两条回调路由同样不实现。</p>
 */
@RestController
public class ImChannelController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ImChannelService service;

    public ImChannelController(ImChannelService service) {
        this.service = service;
    }

    // ═══════════════════ 请求体 ═══════════════════

    record CreateRequest(
            String platform,
            String name,
            String mode,
            String outputMode,
            String sessionMode,
            String knowledgeBaseId,
            JsonNode credentials,
            Boolean enabled) {
    }

    record UpdateRequest(
            String name,
            String mode,
            String outputMode,
            String sessionMode,
            String knowledgeBaseId,
            JsonNode credentials,
            Boolean enabled,
            String agentId) {
    }

    // ═══════════════════ CRUD ═══════════════════

    /** 创建渠道：201 + 裸资源行。 */
    @PostMapping("/api/v1/agents/{id}/im-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        if (agentId == null || agentId.isEmpty()) {
            return plain(400, "agent_id is required");
        }
        CreateRequest req = bindCreate(rawBody);
        if (req.platform() == null || req.platform().isEmpty()) {
            // 400 文案为字段级校验格式（字段名 Platform，非 json 键名）
            return plain(400, "Key: 'Platform' Error:Field validation for 'Platform' "
                    + "failed on the 'required' tag");
        }
        if (!isValidPlatform(req.platform())) {
            return plain(400, ImChannelService.INVALID_PLATFORM_ERROR);
        }
        ImChannelEntity channel = new ImChannelEntity();
        channel.setTenantId(currentTenant());
        channel.setAgentId(agentId);
        channel.setPlatform(req.platform());
        channel.setName(orEmpty(req.name()));
        channel.setMode(req.mode());
        channel.setOutputMode(req.outputMode());
        channel.setSessionMode(req.sessionMode());
        channel.setKnowledgeBaseId(orEmpty(req.knowledgeBaseId()));
        channel.setCredentials(credentialsColumn(req.credentials()));
        channel.setEnabled(req.enabled() == null || req.enabled());
        // WeChat 用长轮询 + 全量输出；其余平台缺省 websocket + stream
        if ("wechat".equals(req.platform())) {
            channel.setMode("longpoll");
            channel.setOutputMode("full");
        } else {
            if (channel.getMode() == null || channel.getMode().isEmpty()) {
                channel.setMode("mattermost".equals(req.platform()) || "yunzhijia".equals(req.platform())
                        ? "webhook" : "websocket");
            }
            if (channel.getOutputMode() == null || channel.getOutputMode().isEmpty()) {
                channel.setOutputMode("stream");
            }
        }
        if (channel.getCredentials() == null) {
            channel.setCredentials("{}");
        }
        try {
            service.createChannel(channel);
        } catch (ImChannelService.DuplicateBotException e) {
            return plain(409, e.getMessage());
        } catch (RuntimeException e) {
            return plain(500, "failed to create channel");
        }
        return ResponseEntity.status(201).body(channelRow(channel));
    }

    /** per-agent 列表 = 裸数组（凭据不出现在列表行，IMChannelSummary）。 */
    @GetMapping("/api/v1/agents/{id}/im-channels")
    public ResponseEntity<List<Map<String, Object>>> listByAgent(@PathVariable("id") String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return plain(400, "agent_id is required");
        }
        List<ImChannelEntity> channels;
        try {
            channels = service.listChannelsByAgent(agentId, currentTenant());
        } catch (RuntimeException e) {
            return plain(500, "failed to list channels");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (ImChannelEntity ch : channels) {
            data.add(summaryRow(ch));
        }
        return ResponseEntity.ok(data);
    }

    /** 跨 agent 总览 = 裸数组（行带 agentName）。 */
    @GetMapping("/api/v1/im-channels")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<Map<String, Object>> rows;
        try {
            rows = service.listChannelsByTenant(currentTenant());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ImChannelController.class)
                    .error("[IM] list all channels failed", e);
            return plain(500, "failed to list channels");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("tenantId", row.get("tenant_id"));
            m.put("agentId", row.get("agent_id"));
            m.put("agentName", row.get("agent_name"));
            m.put("platform", row.get("platform"));
            m.put("name", row.get("name"));
            m.put("enabled", row.get("enabled"));
            m.put("mode", row.get("mode"));
            m.put("outputMode", row.get("output_mode"));
            m.put("sessionMode", row.get("session_mode"));
            m.put("botIdentity", row.get("bot_identity"));
            m.put("createdAt", row.get("created_at"));
            m.put("updatedAt", row.get("updated_at"));
            data.add(m);
        }
        return ResponseEntity.ok(data);
    }

    /** 更新渠道：成功 200 + 资源行。 */
    @PutMapping("/api/v1/im-channels/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        if (channelId == null || channelId.isEmpty()) {
            return plain(400, "channel id is required");
        }
        ImChannelEntity channel = service.getChannelByIdAndTenant(channelId, currentTenant());
        if (channel == null) {
            return plain(404, "channel not found");
        }
        UpdateRequest req = bindUpdate(rawBody);
        if (req.name() != null) {
            channel.setName(req.name());
        }
        if (req.mode() != null) {
            channel.setMode(req.mode());
        }
        if (req.outputMode() != null) {
            channel.setOutputMode(req.outputMode());
        }
        if (req.sessionMode() != null) {
            channel.setSessionMode(req.sessionMode());
        }
        if (req.knowledgeBaseId() != null) {
            channel.setKnowledgeBaseId(req.knowledgeBaseId());
        }
        if (credentialsColumn(req.credentials()) != null) {
            channel.setCredentials(credentialsColumn(req.credentials()));
        }
        if (req.enabled() != null) {
            channel.setEnabled(req.enabled());
        }
        if (req.agentId() != null) {
            String newAgentId = req.agentId().trim();
            if (!newAgentId.isEmpty() && !newAgentId.equals(channel.getAgentId())) {
                try {
                    service.setChannelAgentId(channel, newAgentId);
                } catch (RuntimeException e) {
                    return plain(400, "agent not found");
                }
            }
        }
        try {
            service.updateChannel(channel);
        } catch (ImChannelService.DuplicateBotException e) {
            return plain(409, e.getMessage());
        } catch (RuntimeException e) {
            return plain(500, "failed to update channel");
        }
        return ResponseEntity.ok(channelRow(channel));
    }

    /** 删除渠道：任何失败都落 500 "failed to delete channel"；成功 204。 */
    @DeleteMapping("/api/v1/im-channels/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return plain(400, "channel id is required");
        }
        try {
            service.deleteChannel(channelId, currentTenant());
        } catch (RuntimeException e) {
            return plain(500, "failed to delete channel");
        }
        return ResponseEntity.noContent().build();
    }

    /** 切换启用状态：任何失败都落 500 "failed to toggle channel"。 */
    @PostMapping("/api/v1/im-channels/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable("id") String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return plain(400, "channel id is required");
        }
        ImChannelEntity channel;
        try {
            channel = service.toggleChannel(channelId, currentTenant());
        } catch (RuntimeException e) {
            return plain(500, "failed to toggle channel");
        }
        return ResponseEntity.ok(channelRow(channel));
    }

    // ═══════════════════ 微信扫码（绑定分支 + 接缝） ═══════════════════

    /**
     * 扫码出站（iLink）：{@code WechatQRCodeService} bean 缺位时保留接缝文案
     * （不阻塞装配）；有 bean 则真调。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ragagent.im.wechat.WechatQRCodeService wechatQRCodeService;

    /** 微信扫码出站：200 裸对象 {qrcode, qrcodeUrl}。 */
    @PostMapping("/api/v1/wechat/qrcode")
    public ResponseEntity<Map<String, Object>> wechatQrcode() {
        if (wechatQRCodeService == null) {
            throw new PlainErrorException(500,
                    "failed to generate QR code: wechat iLink integration is not wired");
        }
        com.ragagent.im.wechat.WechatQRCodeService.QRCodeResult result;
        try {
            result = wechatQRCodeService.getLoginQRCode();
        } catch (Exception e) {
            throw new PlainErrorException(500, "failed to generate QR code: " + errText(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("qrcode", result.qrcode());
        data.put("qrcodeUrl", result.qrcodeUrl());
        return ResponseEntity.ok(data);
    }

    /**
     * qrcode 必填（一切绑定失败都是固定文案）；{@code confirmed} 时才给 credentials
     * （+ 非空 baseurl）。响应键序固定：status、credentials、baseUrl。
     */
    @PostMapping("/api/v1/wechat/qrcode/status")
    public ResponseEntity<Map<String, Object>> wechatQrcodeStatus(
            @RequestBody(required = false) String rawBody) {
        QrcodeRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = MAPPER.readValue(rawBody, QrcodeRequest.class);
            } catch (Exception ignored) {
                req = null;
            }
        }
        if (req == null || req.qrcode() == null || req.qrcode().isEmpty()) {
            return plain(400, "qrcode is required");
        }
        if (wechatQRCodeService == null) {
            return plain(500, "failed to check QR code status");
        }
        com.ragagent.im.wechat.WechatQRCodeService.LoginResult result;
        try {
            result = wechatQRCodeService.pollQRCodeStatus(req.qrcode());
        } catch (Exception e) {
            return plain(500, "failed to check QR code status");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", result.status());
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("botToken", result.botToken());
        credentials.put("ilinkBotId", result.ilinkBotId());
        credentials.put("ilinkUserId", result.ilinkUserId());
        data.put("credentials", "confirmed".equals(result.status()) ? credentials : null);
        data.put("baseUrl", "confirmed".equals(result.status()) ? result.baseUrl() : null);
        return ResponseEntity.ok(data);
    }

    private static String errText(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }

    /** 供测试注入扫码服务（生产走 Spring 字段注入）。 */
    void wechatQRCodeService(com.ragagent.im.wechat.WechatQRCodeService service) {
        this.wechatQRCodeService = service;
    }

    record QrcodeRequest(String qrcode) {
    }

    // ═══════════════════ 响应行（三套键序并存） ═══════════════════

    /** 渠道行（create/update/toggle 的资源行），键名即字段名。 */
    private static Map<String, Object> channelRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenantId", ch.getTenantId());
        m.put("agentId", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("outputMode", ch.getOutputMode());
        m.put("knowledgeBaseId", ch.getKnowledgeBaseId());
        m.put("botIdentity", ch.getBotIdentity());
        m.put("sessionMode", ch.getSessionMode());
        m.put("credentials", rawJson(ch.getCredentials()));
        m.put("createdAt", ch.getCreatedAt());
        m.put("updatedAt", ch.getUpdatedAt());
        m.put("deletedAt", null);
        return m;
    }

    /** per-agent 列表行（IMChannelSummary：凭据不出行，只出 credentialsConfigured）。 */
    private static Map<String, Object> summaryRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenantId", ch.getTenantId());
        m.put("agentId", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("outputMode", ch.getOutputMode());
        m.put("knowledgeBaseId", ch.getKnowledgeBaseId());
        m.put("botIdentity", ch.getBotIdentity());
        m.put("sessionMode", ch.getSessionMode());
        m.put("credentialsConfigured", credentialsConfigured(ch.getCredentials()));
        m.put("createdAt", ch.getCreatedAt());
        m.put("updatedAt", ch.getUpdatedAt());
        return m;
    }

    /** trim 后非 "" 且非 "{}"。 */
    private static boolean credentialsConfigured(String credentials) {
        String s = credentials == null ? "" : credentials.trim();
        return !s.isEmpty() && !"{}".equals(s);
    }

    /** credentials 是任意 jsonb：坏 JSON 在绑定阶段已被拒，这里容错回 "{}"。 */
    private static Object rawJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ═══════════════════ 工具 ═══════════════════

    /**
     * credentials 列语义：显式 {@code null} 与缺键都等价于"无值"
     * （create 落 "{}"，update 视为"不改动"）。
     */
    private static String credentialsColumn(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static boolean isValidPlatform(String platform) {
        return PLATFORMS.contains(platform);
    }

    private static final java.util.Set<String> PLATFORMS = java.util.Set.of(
            "wecom", "feishu", "lark", "slack", "telegram", "dingtalk",
            "mattermost", "wechat", "qqbot", "yunzhijia");

    private static long currentTenant() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0L : tid;
    }

    private static <T> ResponseEntity<T> plain(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body((T) body);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 绑定语义：空 body → platform 缺失的校验文案由上面补；
     * 坏 JSON → 走绑定错误兼容文案。 */
    private static CreateRequest bindCreate(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            // 空 body：等价于零值请求，报 Platform required
            return new CreateRequest(null, null, null, null, null, null, null, null);
        }
        try {
            return MAPPER.readValue(rawBody, CreateRequest.class);
        } catch (Exception e) {
            throw new PlainErrorException(400,
                    GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private static UpdateRequest bindUpdate(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new PlainErrorException(400, "EOF");
        }
        try {
            return MAPPER.readValue(rawBody, UpdateRequest.class);
        } catch (Exception e) {
            throw new PlainErrorException(400,
                    GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
        }
    }
}
