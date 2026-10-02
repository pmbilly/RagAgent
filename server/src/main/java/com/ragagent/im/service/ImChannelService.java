package com.ragagent.im.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.mapper.ImChannelMapper;

/**
 * IM 渠道 service（对照 Go internal/im/service.go 的 CRUD 段 L3117-3267 +
 * internal/im/types.go 的 BeforeCreate/BeforeSave/computeBotIdentity）。
 *
 * <p><b>接缝（本批不实现，javadoc 声明）</b>：{@code StartChannel / StopChannel /
 * publishChannelConfigChange} —— 渠道运行时（adapter 长连接、Redis 配置变更广播）
 * 属执行体 service.go 其余 3000+ 行，golden 的 CRUD 面不经过它们。Java 侧为 no-op
 * （对照 Go 在"渠道无法启动只 Warnf 不影响 HTTP 响应"的语义）。</p>
 *
 * <p>错误族（对照 handler 的分派）：duplicate_bot 前缀 → 409 + 去前缀原文；
 * BeforeCreate/BeforeSave 校验失败（session_mode）等 → 500 "failed to create/update channel"。</p>
 */
@Service
public class ImChannelService {

    /** 对照 validIMPlatforms（错误文案按 map 键排序后拼接）。 */
    public static final String INVALID_PLATFORM_ERROR =
            "platform must be one of: 'dingtalk', 'feishu', 'lark', 'mattermost', 'qqbot', "
                    + "'slack', 'telegram', 'wechat', 'wecom', 'yunzhijia'";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImChannelMapper mapper;
    private final com.ragagent.agent.management.mapper.CustomAgentMapper agentMapper;
    private final com.ragagent.agent.management.service.BuiltinAgentRegistry registry;

    public ImChannelService(ImChannelMapper mapper,
                            com.ragagent.agent.management.mapper.CustomAgentMapper agentMapper,
                            com.ragagent.agent.management.service.BuiltinAgentRegistry registry) {
        this.mapper = mapper;
        this.agentMapper = agentMapper;
        this.registry = registry;
    }

    // ═══════════════════ 钩子（对照 types.go 的 BeforeCreate / BeforeSave） ═══════════════════

    /** 对照 BeforeCreate（L94-116）：兜底 + session_mode 校验 + bot_identity。 */
    public void beforeCreate(ImChannelEntity ch) {
        if (ch.getId() == null || ch.getId().isEmpty()) {
            ch.setId(java.util.UUID.randomUUID().toString());
        }
        if (ch.getMode() == null || ch.getMode().isEmpty()) {
            ch.setMode("mattermost".equals(ch.getPlatform()) || "yunzhijia".equals(ch.getPlatform())
                    ? "webhook" : "websocket");
        }
        if (ch.getOutputMode() == null || ch.getOutputMode().isEmpty()) {
            ch.setOutputMode("stream");
        }
        if (ch.getSessionMode() == null || ch.getSessionMode().isEmpty()) {
            ch.setSessionMode("user");
        }
        validateSessionMode(ch);
        ch.setBotIdentity(computeBotIdentity(ch));
    }

    /** 对照 BeforeSave（L120-129）。 */
    public void beforeSave(ImChannelEntity ch) {
        if (ch.getSessionMode() == null || ch.getSessionMode().isEmpty()) {
            ch.setSessionMode("user");
        }
        validateSessionMode(ch);
        ch.setBotIdentity(computeBotIdentity(ch));
    }

    private static void validateSessionMode(ImChannelEntity ch) {
        if (!"user".equals(ch.getSessionMode()) && !"thread".equals(ch.getSessionMode())) {
            throw new InvalidSessionModeException(ch.getSessionMode());
        }
    }

    /** 对照 validateSessionMode 的错误（Create/Update 都翻成 500 固定文案，原文仅日志）。 */
    public static final class InvalidSessionModeException extends RuntimeException {
        public InvalidSessionModeException(String mode) {
            super("invalid session_mode: " + mode);
        }
    }

    /** 对照 DuplicateBotException（handler 按前缀去重后的原文回 409）。 */
    public static final class DuplicateBotException extends RuntimeException {
        public DuplicateBotException(String message) {
            super(message);
        }
    }

    /** 对照 ChannelNotFound（Delete 的 RowsAffected=0 路径；handler 落 500）。 */
    public static final class ChannelNotFoundException extends RuntimeException {
        public ChannelNotFoundException() {
            super("channel not found");
        }
    }

    // ═══════════════════ bot_identity（对照 computeBotIdentity L143-217） ═══════════════════

    /** 凭据 JSON 的键序无关读取；解析失败 → ""（对照 json.Unmarshal 失败分支）。 */
    public String computeBotIdentity(ImChannelEntity ch) {
        JsonNode creds;
        try {
            creds = MAPPER.readTree(ch.getCredentials() == null ? "" : ch.getCredentials());
        } catch (Exception e) {
            return "";
        }
        if (creds == null || !creds.isObject()) {
            return "";
        }
        switch (ch.getPlatform() == null ? "" : ch.getPlatform()) {
            case "wecom":
                if ("websocket".equals(ch.getMode())) {
                    String botId = text(creds, "bot_id");
                    if (!botId.isEmpty()) {
                        return "wecom:ws:" + botId;
                    }
                } else if ("webhook".equals(ch.getMode())) {
                    String corpId = text(creds, "corp_id");
                    String corpAgentId = text(creds, "corp_agent_id");
                    if (!corpId.isEmpty() && !corpAgentId.isEmpty()) {
                        return "wecom:wh:" + corpId + ":" + corpAgentId;
                    }
                }
                return "";
            case "feishu", "lark":
                String appId = text(creds, "app_id");
                return appId.isEmpty() ? "" : ch.getPlatform() + ":" + appId;
            case "telegram":
                String botToken = text(creds, "bot_token");
                if (!botToken.isEmpty()) {
                    int idx = botToken.indexOf(':');
                    if (idx > 0) {
                        return "telegram:" + botToken.substring(0, idx);
                    }
                    return "telegram:" + botToken;
                }
                return "";
            case "dingtalk":
                String clientId = text(creds, "client_id");
                return clientId.isEmpty() ? "" : "dingtalk:" + clientId;
            case "mattermost":
                String tok = text(creds, "outgoing_token");
                return tok.isEmpty() ? "" : "mattermost:wh:" + tok;
            case "wechat":
                String ilinkBotId = text(creds, "ilink_bot_id");
                return ilinkBotId.isEmpty() ? "" : "wechat:" + ilinkBotId;
            case "qqbot":
                String qqAppId = text(creds, "app_id");
                return qqAppId.isEmpty() ? "" : "qqbot:" + qqAppId;
            case "yunzhijia":
                String sendMsgUrl = text(creds, "send_msg_url");
                if (!sendMsgUrl.isEmpty()) {
                    try {
                        var parsed = java.net.URI.create(sendMsgUrl);
                        String query = parsed.getQuery() == null ? "" : parsed.getQuery();
                        for (String pair : query.split("&")) {
                            int eq = pair.indexOf('=');
                            if (eq > 0 && "yzjtoken".equals(pair.substring(0, eq))) {
                                String token = pair.substring(eq + 1).trim();
                                return "yunzhijia:" + hexSha256(token);
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        return "";
                    }
                }
                return "";
            default:
                return "";
        }
    }

    /** 对照 str(key)：字符串原样、数字 %.0f、缺失 ""。 */
    private static String text(JsonNode creds, String key) {
        JsonNode v = creds.get(key);
        if (v == null || v.isNull()) {
            return "";
        }
        if (v.isNumber()) {
            // 对照 fmt.Sprintf("%.0f")
            return String.format(java.util.Locale.ROOT, "%.0f", v.asDouble());
        }
        return v.isTextual() ? v.asText() : "";
    }

    private static String hexSha256(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ═══════════════════ CRUD（对照 service.go L3117-3267） ═══════════════════

    /**
     * 对照 CreateChannel：重复 bot 检查在前（409），落库触发 BeforeCreate（session_mode
     * 校验失败 → 500），启动/广播为 no-op 接缝。
     */
    public void createChannel(ImChannelEntity channel) {
        checkDuplicateBot(channel, "");
        beforeCreate(channel);
        channel.setCreatedAt(OffsetDateTime.now());
        channel.setUpdatedAt(OffsetDateTime.now());
        mapper.insertChannel(channel);
        // StartChannel / publishChannelConfigChange：运行时接缝，no-op
    }

    /** 对照 UpdateChannel：重复 bot 检查（排除自身）→ Save（BeforeSave）→ 重启接缝 no-op。 */
    public void updateChannel(ImChannelEntity channel) {
        checkDuplicateBot(channel, channel.getId());
        beforeSave(channel);
        channel.setUpdatedAt(OffsetDateTime.now());
        mapper.saveChannel(channel);
    }

    /** 对照 SetChannelAgentID（update 换绑 agent 的校验段）。 */
    public void setChannelAgentId(ImChannelEntity channel, String agentId) {
        String trimmed = agentId == null ? "" : agentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("agent_id is required");
        }
        // 对照 Go：GetAgentByID 失败或 tenant 不匹配 → "agent not found"
        com.ragagent.agent.management.domain.CustomAgentEntity agent;
        try {
            agent = agentMapper.getByIDAndTenant(trimmed, channel.getTenantId() == null
                    ? 0 : channel.getTenantId());
        } catch (RuntimeException e) {
            throw new AgentNotFoundException();
        }
        if (agent == null || agent.getTenantId() == null
                || agent.getTenantId().longValue() != (channel.getTenantId() == null
                        ? 0 : channel.getTenantId())) {
            throw new AgentNotFoundException();
        }
        channel.setAgentId(trimmed);
    }

    public static final class AgentNotFoundException extends RuntimeException {
        public AgentNotFoundException() {
            super("agent not found");
        }
    }

    /** 对照 DeleteChannel：0 行受影响 → ChannelNotFound（handler 落 500）。 */
    public void deleteChannel(String channelId, long tenantId) {
        int rows = mapper.softDelete(channelId, tenantId, OffsetDateTime.now());
        if (rows == 0) {
            throw new ChannelNotFoundException();
        }
        // StopChannel / publishChannelConfigChange：no-op 接缝
    }

    /** 对照 ToggleChannel：First（无行 → handler 500）→ 取反 → Save。 */
    public ImChannelEntity toggleChannel(String channelId, long tenantId) {
        ImChannelEntity ch = mapper.getByIdAndTenant(channelId, tenantId);
        if (ch == null) {
            throw new ChannelNotFoundException();
        }
        ch.setEnabled(!ch.isEnabled());
        beforeSave(ch);
        ch.setUpdatedAt(OffsetDateTime.now());
        mapper.saveChannel(ch);
        return ch;
    }

    public List<ImChannelEntity> listChannelsByAgent(String agentId, long tenantId) {
        return mapper.listByAgent(agentId, tenantId);
    }

    public List<Map<String, Object>> listChannelsByTenant(long tenantId) {
        return mapper.listByTenantWithAgent(tenantId,
                registry.orderedIds());
    }

    // ── W5a：IM 回调面（对照 EnsureChannelAdapter，service.go L1567-1593） ──

    /** 回调通道的三态失败（HTTP 形态由 controller 逐个映射，照抄 Go handler）。 */
    public static final class CallbackChannelNotFoundException extends RuntimeException {
        public CallbackChannelNotFoundException() { super("channel not found"); }
    }

    public static final class CallbackChannelDisabledException extends RuntimeException {
        public CallbackChannelDisabledException() { super("channel is disabled"); }
    }

    public static final class CallbackChannelUnavailableException extends RuntimeException {
        public CallbackChannelUnavailableException() { super("channel not available"); }
    }

    /**
     * 对照 EnsureChannelAdapter 的**确定性前缀**：渠道行缺失（404）→ disabled
     * （503）。第三段（适配器工厂 + 平台验签/解析）属于波 5 im 执行体——
     * Java 侧还没有任何 adapter factory，StartChannel 等价分支恒走
     * "channel adapter is not active" → 503 "channel not available"
     * （与 Go 对未知 platform 的行为一致；对**已知** platform 是已备案的
     * A/B 差异——Go 会进入平台验签（403 verification failed 等），见约定 §9）。
     *
     * @return 渠道行（仅 enabled 且 404/503 检查已过的调用点使用）
     */
    public ImChannelEntity ensureChannelForCallback(String channelId) {
        ImChannelEntity fresh = mapper.getById(channelId);
        if (fresh == null) {
            // 对照 gorm.ErrRecordNotFound → 404 "channel not found"（+ StopChannel 清缓存）
            throw new CallbackChannelNotFoundException();
        }
        if (!fresh.isEnabled()) {
            throw new CallbackChannelDisabledException();
        }
        // γ2 起适配器可用性由 ImService.adapterFor 判定（工厂注册 + 运行态）；
        // 未注册平台的渠道由控制器回 503 "channel not available"（W5a golden 形态）。
        return fresh;
    }

    /** 对照 checkDuplicateBot（L3234-3260）。 */
    public void checkDuplicateBot(ImChannelEntity channel, String excludeId) {
        String botKey = computeBotIdentity(channel);
        if (botKey == null || botKey.isEmpty()) {
            return;
        }
        ImChannelEntity existing = mapper.findByBotIdentity(botKey, excludeId);
        if (existing == null) {
            return;
        }
        throw new DuplicateBotException("this bot is already bound to channel "
                + quote(existing.getName()) + " (" + existing.getId() + "); "
                + "each bot can only be connected to one channel");
    }

    /** 对照 fmt.Sprintf("%q")：非字母数字安全字符转义；种子全是可打印 ASCII，直包引号即可。 */
    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** GetChannelByIDAndTenant（对照 L1636）。 */
    public ImChannelEntity getChannelByIdAndTenant(String channelId, long tenantId) {
        return mapper.getByIdAndTenant(channelId, tenantId);
    }
}
