package com.ragagent.embed.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ragagent.common.deployment.DeploymentProperties;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agent.management.mapper.CustomAgentMapper;
import com.ragagent.agent.management.service.CustomAgentService;
import com.ragagent.agent.management.domain.CustomAgentEntity;
import com.ragagent.common.context.TenantContext;
import com.ragagent.embed.EmbedError;
import com.ragagent.embed.EmbedTokens;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.mapper.EmbedChannelMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionPage;
import com.ragagent.session.mapper.SessionRepository;

/**
 * embed 渠道 service（含 session token/签名段）。
 *
 * <p>golden 钉死的两个既有落库行为：</p>
 * <ol>
 *   <li><b>create 后 enabled / show_suggested_questions 恒非 false</b>：
 *       请求 enabled:false / show_suggested_questions:false 落库后仍为 true，响应同
 *       （service 层归一）。禁用态只能经 update（全列写）达成；</li>
 *   <li><b>update 不带 allowed_origins 时整列覆写为 JSON "null"</b>，
 *       读回 {@code allowed_origins: null}。</li>
 * </ol>
 */
@Service
public class EmbedChannelService {

    public static final String EMBED_SESSION_MARKER_PREFIX = "embed_channel:";

    public static final String DEFAULT_WIDGET_POSITION = "bottom-right";
    public static final String DEFAULT_HEADER_TITLE_MODE = "channel";
    private static final int DEFAULT_RATE_PER_MINUTE = 30;
    private static final int DEFAULT_RATE_PER_DAY = 10000;
    private static final Set<String> SUPPORTED_LOCALES =
            Set.of("zh-CN", "en-US", "ko-KR", "ja-JP", "ru-RU");
    private static final Set<String> SUPPORTED_WIDGET_POSITIONS =
            Set.of("bottom-left", "top-right", "top-left", "bottom-right");
    private static final int MAX_LAUNCHER_ICON_BYTES = 200 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmbedChannelMapper repo;
    private final CustomAgentMapper agentMapper;
    private final CustomAgentService agentService;
    private final ChunkRepository chunkRepository;
    private final EmbedTokenStore tokenStore;
    /** 部署形态（是否生产影响 embed 渠道来源白名单校验）。 */
    private final DeploymentProperties deploymentProperties;

    public EmbedChannelService(EmbedChannelMapper repo,
                               CustomAgentMapper agentMapper,
                               CustomAgentService agentService,
                               ChunkRepository chunkRepository,
                               EmbedTokenStore tokenStore,
                               DeploymentProperties deploymentProperties) {
        this.repo = repo;
        this.agentMapper = agentMapper;
        this.agentService = agentService;
        this.chunkRepository = chunkRepository;
        this.tokenStore = tokenStore;
        this.deploymentProperties = deploymentProperties;
    }

    // ═══════════════════ 规范化 ═══════════════════

    public static String normalizeWidgetPosition(String position) {
        String trimmed = position == null ? "" : position.trim();
        return SUPPORTED_WIDGET_POSITIONS.contains(trimmed) ? trimmed : DEFAULT_WIDGET_POSITION;
    }

    public static String normalizeHeaderTitleMode(String mode) {
        String trimmed = mode == null ? "" : mode.trim();
        return "session".equals(trimmed) ? "session" : DEFAULT_HEADER_TITLE_MODE;
    }

    /** 不在支持清单里 → 空串。 */
    public static String normalizeDefaultLocale(String locale) {
        String trimmed = locale == null ? "" : locale.trim();
        return SUPPORTED_LOCALES.contains(trimmed) ? trimmed : "";
    }

    // ═══════════════════ 校验（文案固定） ═══════════════════

    /** allowed_origins 校验；生产判定取部署模式属性。 */
    public void validateAllowedOrigins(List<String> origins) {
        List<String> cleaned = new ArrayList<>();
        if (origins != null) {
            for (String o : origins) {
                String trimmed = o == null ? "" : o.trim();
                if (!trimmed.isEmpty()) {
                    cleaned.add(trimmed);
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw EmbedError.badRequest("at least one allowed origin is required");
        }
        // 生产/开发判定取 WEKNORA_DEPLOYMENT_MODE
        boolean production = deploymentProperties.isProduction();
        for (String o : cleaned) {
            if ("*".equals(o)) {
                if (production) {
                    throw EmbedError.badRequest("wildcard origin '*' is not allowed in production");
                }
                continue;
            }
            String host = o;
            if (o.startsWith("*.")) {
                host = "https://" + o.substring(2);
            }
            if (!isHttpOrigin(host)) {
                throw EmbedError.badRequest("invalid allowed origin: \"" + o + "\"");
            }
        }
    }

    /** 来源必须是绝对 http/https URL（scheme http/https 且 host 非空）。 */
    private static boolean isHttpOrigin(String raw) {
        java.net.URI u;
        try {
            u = java.net.URI.create(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        return ("http".equals(scheme) || "https".equals(scheme)) && u.getHost() != null;
    }

    /** webhook URL 校验（scheme/host 形态 + 私网段拒绝）。 */
    public static void validateWebhookUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        java.net.URI parsed;
        try {
            parsed = java.net.URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw EmbedError.webhookInvalid("webhook URL must be a valid http(s) URL");
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw EmbedError.webhookInvalid("webhook URL must be a valid http(s) URL");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw EmbedError.webhookInvalid("webhook URL must use http or https");
        }
        // SSRF：host 落私网/本机/非法 IP 即拒绝（不白名单且私网 →
        // "host is not allowed"）。dev 环境 公开域名不落 SSRF 分支，
        // golden 的 ftp:// 用例在 scheme 检查已被拒。
        if (isPrivateHost(parsed.getHost())) {
            throw EmbedError.webhookInvalid("host resolves to a private or reserved address");
        }
    }

    private static boolean isPrivateHost(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(h) || h.equals("127.0.0.1") || h.equals("::1") || h.endsWith(".local")) {
            return true;
        }
        if (h.startsWith("10.") || h.startsWith("192.168.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            int dot = h.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(h.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        if (h.startsWith("169.254.") || h.startsWith("0.")) {
            return true;
        }
        return false;
    }

    /** launcher 图标校验（base64 data URL，png/jpeg/svg/webp，大小上限）。 */
    public static void validateLauncherIcon(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String prefixPattern = "data:image/(png|jpeg|svg+xml|webp);base64,";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^" + prefixPattern).matcher(value);
        if (!m.find()) {
            throw EmbedError.iconInvalid("must be a base64 data URL of png/jpeg/svg/webp");
        }
        int headerEnd = m.end();
        if (value.length() - headerEnd > (MAX_LAUNCHER_ICON_BYTES / 3 + 1) * 4) {
            throw EmbedError.iconInvalid("image exceeds " + MAX_LAUNCHER_ICON_BYTES + " bytes");
        }
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(value.substring(headerEnd));
            if (decoded.length > MAX_LAUNCHER_ICON_BYTES) {
                throw EmbedError.iconInvalid("image exceeds " + MAX_LAUNCHER_ICON_BYTES + " bytes");
            }
        } catch (IllegalArgumentException e) {
            throw EmbedError.iconInvalid("invalid base64 payload");
        }
    }

    // ═══════════════════ agent 归属 ═══════════════════

    /**
     * agent 归属校验：未知/跨租户 agent 一律归一为 500 "operation failed"
     * （不区分 404/400，golden 钉死）。空 agent_id 同样落 500。
     */
    public CustomAgentEntity ensureAgentOwned(long tenantId, String agentId) {
        String id = agentId == null ? "" : agentId.trim();
        if (id.isEmpty()) {
            throw EmbedError.operationFailed();
        }
        CustomAgentEntity row;
        try {
            row = agentMapper.getByIDAndTenant(id, tenantId);
        } catch (RuntimeException e) {
            throw EmbedError.operationFailed();
        }
        if (row == null && com.ragagent.agent.management.service.BuiltinAgentRegistry.isBuiltinAgentID(id)) {
            // 内建注册表兜底：内建 agent 视为存在（租户内）
            return virtualBuiltin(id, tenantId);
        }
        if (row == null) {
            throw EmbedError.operationFailed();
        }
        return row;
    }

    private static CustomAgentEntity virtualBuiltin(String id, long tenantId) {
        CustomAgentEntity virtual = new CustomAgentEntity();
        virtual.setId(id);
        virtual.setTenantId(tenantId);
        return virtual;
    }

    // ═══════════════════ CRUD ═══════════════════

    /** 创建渠道：校验归属与图标、生成发布令牌、写入默认值。 */
    public EmbedChannelEntity create(long tenantId, String agentId, EmbedChannelEntity req) {
        String trimmedAgent = agentId == null ? "" : agentId.trim();
        ensureAgentOwned(tenantId, trimmedAgent);
        validateLauncherIcon(trim(req.getLauncherIcon()));
        String token = EmbedTokens.generatePublishToken();

        EmbedChannelEntity ch = new EmbedChannelEntity();
        ch.setTenantId(tenantId);
        ch.setAgentId(trimmedAgent.isEmpty() ? "builtin-quick-answer" : trimmedAgent);
        ch.setName(trim(req.getName()));
        ch.setEnabled(req.isEnabled());
        ch.setPublishToken(token);
        ch.setAllowedOrigins(originsColumn(req.getAllowedOrigins(), "[]"));
        ch.setWelcomeMessage(req.getWelcomeMessage());
        ch.setRateLimitPerMinute(req.getRateLimitPerMinute());
        ch.setRateLimitPerDay(req.getRateLimitPerDay());
        ch.setPrimaryColor(trim(req.getPrimaryColor()));
        ch.setPageTitle(trim(req.getPageTitle()));
        ch.setHeaderTitleMode(normalizeHeaderTitleMode(req.getHeaderTitleMode()));
        ch.setShowSuggestedQuestions(req.isShowSuggestedQuestions());
        ch.setShowThinking(req.isShowThinking());
        ch.setWidgetPosition(normalizeWidgetPosition(req.getWidgetPosition()));
        ch.setAllowWebSearch(req.isAllowWebSearch());
        ch.setAllowFileUpload(req.isAllowFileUpload());
        ch.setDefaultLocale(normalizeDefaultLocale(req.getDefaultLocale()));
        ch.setLauncherIcon(trim(req.getLauncherIcon()));
        // create 恒置空 webhook 两列（新建渠道不带 webhook）
        ch.setWebhookUrl("");
        ch.setWebhookSecret("");
        if (ch.getRateLimitPerMinute() <= 0) {
            ch.setRateLimitPerMinute(DEFAULT_RATE_PER_MINUTE);
        }
        if (ch.getRateLimitPerDay() <= 0) {
            ch.setRateLimitPerDay(DEFAULT_RATE_PER_DAY);
        }
        // ⚠️ golden 钉死：default:true 的零值布尔归一为 true——
        // 请求 false 无效，必须走 Update 才能禁用。
        if (!ch.isEnabled()) {
            ch.setEnabled(true);
        }
        if (!ch.isShowSuggestedQuestions()) {
            ch.setShowSuggestedQuestions(true);
        }
        ch.setId(java.util.UUID.randomUUID().toString());
        ch.setCreatedAt(OffsetDateTime.now());
        ch.setUpdatedAt(OffsetDateTime.now());
        repo.insertChannel(ch);
        return ch;
    }

    /** allowed_origins 列值：空 → 默认空数组文本；否则原样。 */
    private static String originsColumn(String raw, String ifEmpty) {
        return raw == null || raw.isEmpty() ? ifEmpty : raw;
    }

    public List<EmbedChannelEntity> listByAgent(long tenantId, String agentId) {
        String trimmed = agentId == null ? "" : agentId.trim();
        ensureAgentOwned(tenantId, trimmed);
        return repo.listByAgent(tenantId, trimmed);
    }

    public List<EmbedChannelEntity> listByTenant(long tenantId) {
        return repo.listByTenant(tenantId);
    }

    /**
     * 更新渠道。update 字段用 Boolean/String 包装表达可缺省。
     *
     * @param allowedOriginsColumn 请求体里 allowed_origins 的 raw JSON 文本（**缺键 = "null"**，
     *                             整列覆写为 "null" 文本即清空 allowlist；null 表示不改动）
     */
    public EmbedChannelEntity update(long tenantId, String id, UpdateCommand cmd) {
        EmbedChannelEntity ch = getOwned(tenantId, id);
        if (cmd.name != null && !cmd.name.isEmpty()) {
            ch.setName(trim(cmd.name));
        }
        ch.setWelcomeMessage(orEmpty(cmd.welcomeMessage));
        ch.setPrimaryColor(trim(cmd.primaryColor));
        ch.setPageTitle(trim(cmd.pageTitle));
        ch.setHeaderTitleMode(normalizeHeaderTitleMode(cmd.headerTitleMode));
        if (cmd.showSuggested != null) {
            ch.setShowSuggestedQuestions(cmd.showSuggested);
        }
        if (cmd.showThinking != null) {
            ch.setShowThinking(cmd.showThinking);
        }
        if (cmd.allowWebSearch != null) {
            ch.setAllowWebSearch(cmd.allowWebSearch);
        }
        if (cmd.allowFileUpload != null) {
            ch.setAllowFileUpload(cmd.allowFileUpload);
        }
        if (cmd.defaultLocale != null) {
            ch.setDefaultLocale(normalizeDefaultLocale(cmd.defaultLocale));
        }
        if (cmd.webhookUrl != null) {
            String trimmed = cmd.webhookUrl.trim();
            validateWebhookUrl(trimmed);
            ch.setWebhookUrl(trimmed);
        }
        if (cmd.webhookSecret != null) {
            ch.setWebhookSecret(cmd.webhookSecret.trim());
        }
        if (cmd.launcherIcon != null) {
            String trimmed = cmd.launcherIcon.trim();
            validateLauncherIcon(trimmed);
            ch.setLauncherIcon(trimmed);
        }
        if (cmd.widgetPosition != null && !cmd.widgetPosition.isEmpty()) {
            ch.setWidgetPosition(normalizeWidgetPosition(cmd.widgetPosition));
        }
        if (cmd.enabled != null) {
            ch.setEnabled(cmd.enabled);
        }
        if (cmd.rateLimitPerMinute > 0) {
            ch.setRateLimitPerMinute(cmd.rateLimitPerMinute);
        }
        if (cmd.rateLimitPerDay > 0) {
            ch.setRateLimitPerDay(cmd.rateLimitPerDay);
        }
        // ⚠️ golden 钉死：AllowedOrigins 列的 null 语义。缺键序列化成
        // "null" → 整列覆写为 null；
        // 显式 [] 会被校验拒成 400（"at least one allowed origin is required"），
        // 所以这里只可能收到 "null"（缺键）或合法数组文本。
        if (cmd.allowedOriginsColumn != null) {
            if (cmd.allowedOriginsColumn.isEmpty()) {
                ch.setAllowedOrigins("[]");
            } else {
                ch.setAllowedOrigins(cmd.allowedOriginsColumn);
            }
        }
        String trimmedAgent = trim(cmd.agentId);
        if (!trimmedAgent.isEmpty() && !trimmedAgent.equals(ch.getAgentId())) {
            ensureAgentOwned(tenantId, trimmedAgent);
            ch.setAgentId(trimmedAgent);
        }
        ch.setUpdatedAt(OffsetDateTime.now());
        repo.saveChannel(ch);
        return ch;
    }

    /** Update 的入参束。 */
    public static final class UpdateCommand {
        public String name;
        public String welcomeMessage;
        public String primaryColor;
        public String pageTitle;
        public String headerTitleMode;
        public String widgetPosition;
        public String agentId;
        public Boolean enabled;
        public Boolean showSuggested;
        public Boolean showThinking;
        public Boolean allowWebSearch;
        public Boolean allowFileUpload;
        public String defaultLocale;
        public String webhookUrl;
        public String webhookSecret;
        public String launcherIcon;
        public int rateLimitPerMinute;
        public int rateLimitPerDay;
        /** 缺键 = "null"（整列覆写语义）；只有 handler 明确传 null 才是"不改动"。 */
        public String allowedOriginsColumn;
    }

    public void delete(long tenantId, String id) {
        getOwned(tenantId, id);
        repo.softDelete(tenantId, id, OffsetDateTime.now());
    }

    public RotateResult rotateToken(long tenantId, String id) {
        EmbedChannelEntity ch = getOwned(tenantId, id);
        String token = EmbedTokens.generatePublishToken();
        ch.setPublishToken(token);
        ch.setUpdatedAt(OffsetDateTime.now());
        repo.saveChannel(ch);
        return new RotateResult(ch, token);
    }

    public record RotateResult(EmbedChannelEntity channel, String token) {}

    public EmbedChannelEntity getOwnedChannel(long tenantId, String id) {
        return getOwned(tenantId, id);
    }

    /** 校验渠道归属：不存在或跨租户 → 404。 */
    private EmbedChannelEntity getOwned(long tenantId, String id) {
        EmbedChannelEntity ch = repo.getById(id);
        if (ch == null || ch.getTenantId() == null || ch.getTenantId() != tenantId) {
            throw EmbedError.channelNotFound();
        }
        return ch;
    }

    // ═══════════════════ token / 签名 ═══════════════════

    /** 签发 session token：无可用 store → 503。 */
    public IssueResult issueSessionToken(String channelId) {
        String cid = channelId == null ? "" : channelId.trim();
        if (cid.isEmpty()) {
            throw EmbedError.channelNotFound();
        }
        String token = EmbedTokens.generateSessionToken();
        try {
            tokenStore.put(token, cid, java.time.Duration.ofSeconds(EmbedTokens.SESSION_TTL_SECONDS));
        } catch (RuntimeException e) {
            throw EmbedError.sessionUnavailable();
        }
        return new IssueResult(token, EmbedTokens.SESSION_TTL_SECONDS);
    }

    public record IssueResult(String token, int expiresIn) {}

    /** 解析会话令牌：非 ems_ 前缀 / 键不存在 → token invalid。 */
    public String resolveSessionToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (!EmbedTokens.isSessionToken(trimmed)) {
            return null;
        }
        String channelId;
        try {
            channelId = tokenStore.get(trimmed);
        } catch (RuntimeException e) {
            throw EmbedError.sessionUnavailable();
        }
        channelId = channelId == null ? "" : channelId.trim();
        return channelId.isEmpty() ? null : channelId;
    }

    /** 按渠道 id 查启用渠道：未知/禁用 → 401/403。 */
    public EmbedChannelEntity lookupEnabledChannel(String channelId) {
        String cid = channelId == null ? "" : channelId.trim();
        if (cid.isEmpty()) {
            throw EmbedError.tokenInvalid();
        }
        EmbedChannelEntity ch = repo.getById(cid);
        if (ch == null) {
            throw EmbedError.tokenInvalid();
        }
        if (!ch.isEnabled()) {
            throw EmbedError.channelDisabled();
        }
        return ch;
    }

    /** 渠道 + 令牌双重校验（embed 公开面门禁）。 */
    public EmbedChannelEntity lookupForEmbed(String channelId, String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            throw EmbedError.tokenInvalid();
        }
        EmbedChannelEntity ch = repo.getById(channelId);
        if (ch == null) {
            throw EmbedError.tokenInvalid();
        }
        if (!ch.isEnabled()) {
            throw EmbedError.channelDisabled();
        }
        if (!constantTimeEquals(nullSafe(ch.getPublishToken()), trimmed)) {
            throw EmbedError.tokenInvalid();
        }
        return ch;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** 预览会话：渠道须存在且启用。 */
    public IssueResult issuePreviewSession(long tenantId, String channelId) {
        EmbedChannelEntity ch = getOwned(tenantId, channelId);
        if (!ch.isEnabled()) {
            throw EmbedError.channelDisabled();
        }
        return issueSessionToken(ch.getId());
    }

    // ═══════════════════ 公开 config / chunk / 推荐问题 ═══════════════════

    /** 公开 config：键按声明序输出，零值键省略。 */
    public ObjectNode publicConfig(EmbedChannelEntity ch) {
        List<String> kbIds = resolveKnowledgeBaseIDs(ch);
        String[] meta = resolveDisplayMeta(ch);
        String displayTitle = meta[0];
        String agentName = meta[1];
        String agentAvatar = meta[2];
        boolean agentWebSearch = false;
        boolean agentImageUpload = false;
        CustomAgentEntity agent = tryAgent(ch.getAgentId());
        if (agent != null) {
            JsonNode cfg = parseConfig(agent);
            agentWebSearch = cfg.path("webSearchEnabled").asBoolean(false);
            agentImageUpload = cfg.path("imageUploadEnabled").asBoolean(false);
        }
        // 键名＝实体字段名（camelCase）；且**全部键恒输出**——
        // 空集合写 []、空串照写，widget 侧不必再猜"这个键这次在不在"。
        ObjectNode n = MAPPER.createObjectNode();
        n.put("channelId", ch.getId());
        n.put("name", ch.getName());
        n.put("displayTitle", displayTitle);
        ArrayNode kbArr = n.putArray("knowledgeBaseIds");
        kbIds.forEach(kbArr::add);
        n.put("agentId", ch.getAgentId());
        n.put("agentName", agentName);
        n.put("agentAvatar", agentAvatar);
        n.put("welcomeMessage", ch.getWelcomeMessage());
        n.put("primaryColor", ch.getPrimaryColor());
        n.put("pageTitle", ch.getPageTitle());
        n.put("headerTitleMode", normalizeHeaderTitleMode(ch.getHeaderTitleMode()));
        n.put("showSuggestedQuestions", ch.isShowSuggestedQuestions());
        n.put("showThinking", ch.isShowThinking());
        ArrayNode originArr = n.putArray("allowedOrigins");
        allowedOriginsList(ch).forEach(originArr::add);
        n.put("widgetPosition", normalizeWidgetPosition(ch.getWidgetPosition()));
        n.put("allowWebSearch", ch.isAllowWebSearch());
        n.put("allowFileUpload", ch.isAllowFileUpload());
        n.put("agentWebSearchEnabled", agentWebSearch);
        n.put("agentImageUploadEnabled", agentImageUpload);
        n.put("defaultLocale", normalizeDefaultLocale(ch.getDefaultLocale()));
        n.put("launcherIcon", ch.getLauncherIcon() == null ? "" : ch.getLauncherIcon());
        return n;
    }

    /** 公开分块读取：404 优先、跨租户/不在白名单 → forbidden。 */
    public Chunk embedChunk(EmbedChannelEntity ch, String chunkId) {
        String cid = chunkId == null ? "" : chunkId.trim();
        if (cid.isEmpty()) {
            throw new ChunkNotFoundError();
        }
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkByIdOnly(cid);
        } catch (RuntimeException e) {
            throw new ChunkNotFoundError();
        }
        if (chunk == null) {
            throw new ChunkNotFoundError();
        }
        if (!chunkAllowedForEmbed(ch, chunk)) {
            throw new ChunkForbiddenError();
        }
        return chunk;
    }

    public static final class ChunkNotFoundError extends RuntimeException {}
    public static final class ChunkForbiddenError extends RuntimeException {}

    /** 分块可见性：无 KB 约束时按 agent 的选择模式放行。 */
    private boolean chunkAllowedForEmbed(EmbedChannelEntity ch, Chunk chunk) {
        if (chunk == null || chunk.getKnowledgeBaseId() == null || chunk.getKnowledgeBaseId().isEmpty()) {
            return false;
        }
        if (ch == null || chunk.getTenantId() == null || ch.getTenantId() == null
                || chunk.getTenantId().longValue() != ch.getTenantId().longValue()) {
            return false;
        }
        List<String> allowedKbs = resolveKnowledgeBaseIDs(ch);
        if (!allowedKbs.isEmpty()) {
            return allowedKbs.contains(chunk.getKnowledgeBaseId());
        }
        CustomAgentEntity agent = tryAgent(ch.getAgentId());
        if (agent == null) {
            return false;
        }
        String mode = parseConfig(agent).path("kbSelectionMode").asText("");
        return switch (mode) {
            case "none" -> false;
            case "selected" -> false;
            default -> true;
        };
    }

    /** 推荐问题：委托 agent.management 的同语义实现。 */
    public ArrayNode suggestedQuestions(EmbedChannelEntity ch, int limit) {
        if (ch == null || !ch.isShowSuggestedQuestions()) {
            return null;
        }
        List<String> kbIds = resolveKnowledgeBaseIDs(ch);
        return agentService.getSuggestedQuestions(ch.getAgentId(), kbIds, null, null, limit, null);
    }

    /** 展示标题与 agent 元信息：pageTitle → 名称 → 空串。 */
    private String[] resolveDisplayMeta(EmbedChannelEntity ch) {
        String displayTitle = "";
        String pageTitle = trim(ch.getPageTitle());
        if (!pageTitle.isEmpty()) {
            displayTitle = pageTitle;
        } else {
            String name = trim(ch.getName());
            if (!name.isEmpty()) {
                displayTitle = name;
            }
        }
        String agentName = "";
        String agentAvatar = "";
        CustomAgentEntity agent = tryAgent(ch.getAgentId());
        if (agent != null) {
            agentName = trim(agent.getName());
            agentAvatar = trim(agent.getAvatar());
            if (displayTitle.isEmpty() && !agentName.isEmpty()) {
                displayTitle = agentName;
            }
        }
        if (displayTitle.isEmpty()) {
            displayTitle = "AI Assistant";
        }
        return new String[] {displayTitle, agentName, agentAvatar};
    }

    /** KB 约束回填：仅 agent 的 selected 模式回填所选 KB。 */
    private List<String> resolveKnowledgeBaseIDs(EmbedChannelEntity ch) {
        CustomAgentEntity agent = tryAgent(ch.getAgentId());
        if (agent == null) {
            return List.of();
        }
        JsonNode cfg = parseConfig(agent);
        if (!"selected".equals(cfg.path("kbSelectionMode").asText(""))) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        JsonNode kbs = cfg.get("knowledgeBases");
        if (kbs != null && kbs.isArray()) {
            for (JsonNode k : kbs) {
                ids.add(k.asText(""));
            }
        }
        return ids;
    }

    /** agent 查询主路径（配置已归一化的 Result）；失败返回 null。 */
    public CustomAgentEntity tryAgent(String agentId) {
        try {
            return agentService.getAgentByID(agentId, null).row();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode parseConfig(CustomAgentEntity agent) {
        try {
            return MAPPER.readTree(agent.getConfig() == null ? "{}" : agent.getConfig());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** allowed_origins 列值解析：列值 "null"/空/坏 JSON → 空列表（响应 null 的来源）。 */
    public static List<String> allowedOriginsList(EmbedChannelEntity ch) {
        String raw = ch.getAllowedOrigins();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> origins = new ArrayList<>();
            for (JsonNode n : node) {
                origins.add(n.asText(""));
            }
            return origins;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** embed 会话描述标记：前缀 + 渠道 id。 */
    public static String embedSessionDescription(String channelId) {
        return EMBED_SESSION_MARKER_PREFIX + channelId;
    }

    /** embed 来源的会话计数（source=embed:cid；渠道来源丢弃按人裁剪）。 */
    public long countEmbedSessions(long tenantId, String channelId,
                                   SessionRepository sessionRepository) {
        SessionListQuery query = SessionListQuery.of(null, "embed:" + channelId, null, 1, 1);
        SessionPage items = sessionRepository.queryPaged(query.withScope(tenantId, ""));
        return items.total();
    }

    /** 建 embed 会话（空标题 + 描述标记）。 */
    public static Session newEmbedSession(long tenantId, String channelId) {
        Session session = new Session();
        session.setTenantId(tenantId);
        session.setTitle("");
        session.setDescription(embedSessionDescription(channelId));
        return session;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 占位：TenantContext 出口的 tenant 归一（供 filter 使用）。 */
    public static long currentTenantOrZero() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0L : tid;
    }
}
