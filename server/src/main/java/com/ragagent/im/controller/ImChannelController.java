package com.ragagent.im.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * IM 渠道 CRUD + 微信扫码状态面（对照 Go internal/handler/im.go 的渠道段 +
 * wechat_qrcode.go + internal/router/routes_agent.go RegisterIMChannelRoutes）。
 *
 * <p><b>响应形态（golden 钉死）</b>：CRUD 的信封是 {@code {"data": …}}（**没有**
 * success 键）；删除是 {@code {"success": true}}；渠道行按 Go struct 字段声明序输出；
 * 列表行按 IMChannelSummary / ChannelWithAgent 各自的字段序输出（三套键序并存）。</p>
 *
 * <p><b>接缝（不实现）</b>：{@code POST /wechat/qrcode} 的真实 iLink 出站（GetLoginQRCode）
 * 与 {@code /wechat/qrcode/status} 的轮询出站（PollQRCodeStatus）是外部微信集成——
 * 录制脚本明确不录（错误体含两侧 HTTP client 各异的消息，XDEP）。Java 侧绑定分支
 * （qrcode 必填 → 400 "qrcode is required"）与错误形态（500 固定文案）照 Go 落，
 * 外呼本身抛接缝异常；{@code /im/callback/:channel_id} 两条回调路由同批不实现。</p>
 */
@RestController
public class ImChannelController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ImChannelService service;

    public ImChannelController(ImChannelService service) {
        this.service = service;
    }

    // ═══════════════════ 请求体（对照 im.go 内联 struct） ═══════════════════

    record CreateRequest(
            @JsonProperty("platform") String platform,
            @JsonProperty("name") String name,
            @JsonProperty("mode") String mode,
            @JsonProperty("output_mode") String outputMode,
            @JsonProperty("session_mode") String sessionMode,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("credentials") JsonNode credentials,
            @JsonProperty("enabled") Boolean enabled) {
    }

    record UpdateRequest(
            @JsonProperty("name") String name,
            @JsonProperty("mode") String mode,
            @JsonProperty("output_mode") String outputMode,
            @JsonProperty("session_mode") String sessionMode,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("credentials") JsonNode credentials,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("agent_id") String agentId) {
    }

    // ═══════════════════ CRUD ═══════════════════

    /** 对照 CreateIMChannel：200（不是 201）。 */
    @PostMapping("/api/v1/agents/{id}/im-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        if (agentId == null || agentId.isEmpty()) {
            return plain(400, "agent_id is required");
        }
        CreateRequest req = bindCreate(rawBody);
        if (req.platform() == null || req.platform().isEmpty()) {
            // gin binding:"required" 的 validator 文案（struct 字段名，非 json tag）
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
        // WeChat 用长轮询 + 全量输出；其余平台缺省 websocket + stream（im.go L98-113）
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
        return ResponseEntity.ok(Map.of("data", channelRow(channel)));
    }

    /** 对照 ListIMChannels：凭据不出现在列表行（IMChannelSummary）。 */
    @GetMapping("/api/v1/agents/{id}/im-channels")
    public ResponseEntity<Map<String, Object>> listByAgent(@PathVariable("id") String agentId) {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** 对照 ListAllIMChannels：跨 agent 总览（ChannelWithAgent，带 agent_name）。 */
    @GetMapping("/api/v1/im-channels")
    public ResponseEntity<Map<String, Object>> listAll() {
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
            m.put("tenant_id", row.get("tenant_id"));
            m.put("agent_id", row.get("agent_id"));
            m.put("agent_name", row.get("agent_name"));
            m.put("platform", row.get("platform"));
            m.put("name", row.get("name"));
            m.put("enabled", row.get("enabled"));
            m.put("mode", row.get("mode"));
            m.put("output_mode", row.get("output_mode"));
            m.put("session_mode", row.get("session_mode"));
            m.put("bot_identity", row.get("bot_identity"));
            m.put("created_at", row.get("created_at"));
            m.put("updated_at", row.get("updated_at"));
            data.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** 对照 UpdateIMChannel。 */
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
        return ResponseEntity.ok(Map.of("data", channelRow(channel)));
    }

    /** 对照 DeleteIMChannel：任何失败都落 500 "failed to delete channel"。 */
    @DeleteMapping("/api/v1/im-channels/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return plain(400, "channel id is required");
        }
        try {
            service.deleteChannel(channelId, currentTenant());
        } catch (RuntimeException e) {
            return plain(500, "failed to delete channel");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 ToggleIMChannel：任何失败都落 500 "failed to toggle channel"。 */
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
        return ResponseEntity.ok(Map.of("data", channelRow(channel)));
    }

    // ═══════════════════ 微信扫码（绑定分支 + 接缝） ═══════════════════

    /**
     * 对照 WeChatGetQRCode：出站 iLink 调用是接缝（golden 刻意不录该错误体的 XDEP 文案），
     * Java 侧恒走失败分支（500 固定前缀 "failed to generate QR code: "）。
     */
    @PostMapping("/api/v1/wechat/qrcode")
    public ResponseEntity<Map<String, Object>> wechatQrcode() {
        throw new PlainErrorException(500, "failed to generate QR code: wechat iLink integration is not wired");
    }

    /** 对照 WeChatPollQRCodeStatus：qrcode 必填（一切 bind 失败都是固定文案）。 */
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
        // PollQRCodeStatus 出站调用是接缝：恒走失败分支（500 固定文案，对照 Go）
        return plain(500, "failed to check QR code status");
    }

    record QrcodeRequest(@JsonProperty("qrcode") String qrcode) {
    }

    // ═══════════════════ 响应行（三套键序并存，对照 Go 三个 struct） ═══════════════════

    /** 对照 IMChannel struct 字段序（create/update/toggle 的 data 行）。 */
    private static Map<String, Object> channelRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenant_id", ch.getTenantId());
        m.put("agent_id", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("output_mode", ch.getOutputMode());
        m.put("knowledge_base_id", ch.getKnowledgeBaseId());
        m.put("bot_identity", ch.getBotIdentity());
        m.put("session_mode", ch.getSessionMode());
        m.put("credentials", rawJson(ch.getCredentials()));
        m.put("created_at", ch.getCreatedAt());
        m.put("updated_at", ch.getUpdatedAt());
        m.put("deleted_at", null);
        return m;
    }

    /** 对照 IMChannelSummary struct 字段序（per-agent 列表行）。 */
    private static Map<String, Object> summaryRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenant_id", ch.getTenantId());
        m.put("agent_id", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("output_mode", ch.getOutputMode());
        m.put("knowledge_base_id", ch.getKnowledgeBaseId());
        m.put("bot_identity", ch.getBotIdentity());
        m.put("session_mode", ch.getSessionMode());
        m.put("credentials_configured", credentialsConfigured(ch.getCredentials()));
        m.put("created_at", ch.getCreatedAt());
        m.put("updated_at", ch.getUpdatedAt());
        return m;
    }

    /** 对照 imCredentialsConfigured：trim 后非 "" 且非 "{}"。 */
    private static boolean credentialsConfigured(String credentials) {
        String s = credentials == null ? "" : credentials.trim();
        return !s.isEmpty() && !"{}".equals(s);
    }

    /** credentials 是任意 jsonb：坏 JSON 在 Go 的 bind 阶段已被拒，这里容错回 "{}"。 */
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
     * 对照 types.JSON 的 UnmarshalJSON：显式 {@code null} 与缺键都等价于 nil
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

    private static ResponseEntity<Map<String, Object>> plain(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 对照 create 的 ShouldBindJSON：空 body → platform 缺失的 validator 文案由上面补；
     * 坏 JSON → Go 措辞。 */
    private static CreateRequest bindCreate(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            // 空 body：Go 的 validator 在零值 struct 上报 Platform required
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
