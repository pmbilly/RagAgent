package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.domain.tenantconfig.ParserEngineConfig;
import com.ragagent.auth.domain.tenantconfig.RetrievalConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.auth.domain.tenantconfig.TenantConfigRedaction;
import com.ragagent.auth.domain.tenantconfig.WebSearchConfig;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.common.tenant.TenantProperties;
import com.ragagent.common.knowledge.KnowledgeBaseProvisioner;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.storage.StorageAllowList;
import com.ragagent.common.settings.SystemSettingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go handler/tenant.go 的跨空间租户目录 + KV 配置分发器（波 2 扫尾批 3，
 * routes_auth_tenant.go L53-76 五条路由）：
 *
 * <ul>
 *   <li>GET  /tenants/all     —— ListAllTenants（L1192-1216），viewer 形态裁剪</li>
 *   <li>GET  /tenants/search  —— SearchTenants（L1218-1280），分页 + keyword/tenant_id</li>
 *   <li>POST /tenants         —— CreateTenant（L226-513）：自助/超管双路径、配额、
 *       owner 引导、tenantless 回填、auto_create_api_key 兼容</li>
 *   <li>GET/PUT /tenants/kv/{key} —— KV 分发器（L1304-1395），6 个 DB-backed key
 *       + GET prompt-templates（走查补翻：PromptTemplateCatalog 装载 vendored
 *       yaml + LocalizeTemplates 本地化；PUT 分发器 Go 本来就没有它 → 400）</li>
 * </ul>
 *
 * <p>跨空间守卫（all/search）在 {@code RbacInterceptor.addCrossTenantRule}；
 * 角色下限（kv GET=Viewer+、PUT=Admin+）在 {@code WebConfig}；
 * 三条敏感 key 的 admin 门在本类 {@link #canViewIntegrationSecrets()}。</p>
 */
@RestController
public class TenantCatalogController {

    private static final Logger log = LoggerFactory.getLogger(TenantCatalogController.class);

    final TenantService tenantService;
    final TenantMemberService memberService;
    final UserService userService;
    final SystemSettingGateway systemSettingService;
    final TenantAPIKeyService apiKeyService;
    private final KnowledgeBaseProvisioner knowledgeProvisioner;
    final TenantProperties tenantProperties;
    private final SsrfGuard ssrfGuard;
    private final StorageAllowList storageAllowList;
    /** Spring 全局 mapper（带 JacksonConfig 的 OffsetDateTime→本地时区序列化），
     *  仅供 tenantWithApiKey 把实体转成与 Go 字节同形态的时间串 */
    final ObjectMapper springMapper;

    /** 创建租户协作者（对照 Go CreateTenant 段）。 */
    final TenantCreateOps createOps;

    public TenantCatalogController(TenantService tenantService,
                                   TenantMemberService memberService,
                                   UserService userService,
                                   SystemSettingGateway systemSettingService,
                                   TenantAPIKeyService apiKeyService,
                                   KnowledgeBaseProvisioner knowledgeProvisioner,
                                   TenantProperties tenantProperties,
                                   SsrfGuard ssrfGuard,
                                   StorageAllowList storageAllowList,
                                   ObjectMapper springMapper) {
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.userService = userService;
        this.systemSettingService = systemSettingService;
        this.apiKeyService = apiKeyService;
        this.knowledgeProvisioner = knowledgeProvisioner;
        this.tenantProperties = tenantProperties;
        this.ssrfGuard = ssrfGuard;
        this.storageAllowList = storageAllowList;
        this.springMapper = springMapper;
        this.createOps = new TenantCreateOps(this);
    }


    @PostMapping("/api/v1/tenants")
    public ResponseEntity<Map<String, Object>> createTenant(
            @RequestBody(required = false) String rawBody) {
        return createOps.createTenant(rawBody);
    }


    // ── W5a：tenants CRUD 4 条（对照 handler/tenant.go L541-659 / L1123-1181） ──

    /**
     * GET /tenants（对照 ListTenants，L1165-1181）：返回**调用者活动空间**的单元素
     * 列表（不是全量目录——全量在 /tenants/all）。上下文无租户 → 401
     * "Authentication required"。Go 路由**无角色门**（只有全局 Auth），Java 侧
     * 同样不登记 RBAC 规则。
     */
    @GetMapping("/api/v1/tenants")
    public Map<String, Object> listTenants() {
        Long tenantId = TenantContext.currentTenantId();
        Tenant tenant = tenantId == null || tenantId == 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.unauthorized("Authentication required"));
        }
        List<TenantResponse> items = new ArrayList<>();
        items.add(TenantResponse.from(tenant, contextRoleHasAdmin()));
        return TenantMemberController.envelope(Map.of("items", items));
    }

    /**
     * GET /tenants/{id}（对照 GetTenant，L541-578）。URL :id 的合法性由
     * PathTenantMatch 在中间件层先行校验/拒绝（handler 里的 "Invalid workspace ID"
     * 400 是不可达死代码，约定 §9）；租户缺失 → 500 "Failed to retrieve workspace"
     * details "record not found"（Go 的 repo 错误不是 AppError）。
     */
    @GetMapping("/api/v1/tenants/{id}")
    public Map<String, Object> getTenant(@PathVariable("id") String id) {
        Tenant tenant = loadTenantOr500(Long.parseLong(id.trim()), "Failed to retrieve workspace");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", TenantResponse.from(tenant, contextRoleHasAdmin()));
        body.put("success", true);
        return body;
    }

    /**
     * PUT /tenants/{id}（对照 UpdateTenant，L581-657）：白名单只开 name/description
     *（指针区分"未携带"与"显式空串"）。绑定失败 400 "Invalid request data"+details；
     * name trim 后空 → 400 "name cannot be blank"；其余复用 kv 分发器的 500 形态。
     */
    @PutMapping("/api/v1/tenants/{id}")
    public Map<String, Object> updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        UpdateTenantRequest req = bindUpdateTenantRequest(rawBody);
        Tenant existing = loadTenantOr500(Long.parseLong(id.trim()), "Failed to load workspace");
        // 注意：绑定（400 语义层）先于租户加载——Go handler 同序

        if (req.name != null) {
            String trimmed = TenantBindSupport.trimGo(req.name);
            if (trimmed.isEmpty()) {
                throw new BizException(AppError.validation("name cannot be blank"));
            }
            existing.setName(trimmed);
        }
        if (req.description != null) {
            existing.setDescription(TenantBindSupport.trimGo(req.description));
        }

        try {
            tenantService.updateTenant(existing);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to update workspace")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", TenantResponse.from(existing, contextRoleHasAdmin()));
        body.put("success", true);
        return body;
    }

    /** 对照 updateTenantRequest（tenant.go L102-105）：name omitempty,min=1,max=128；description omitempty,max=512。 */
    static final class UpdateTenantRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("name")
        String name;
        @com.fasterxml.jackson.annotation.JsonProperty("description")
        String description;
    }

    /**
     * 绑定 + validator（rune 计长；失败字段按 struct 序 join("\n")）。
     * 类型错给 Go UnmarshalTypeError 原文（具名 struct →
     * "updateTenantRequest.name of type string"——golden w5a-tenant-put-badjson 钉住）。
     */
    private UpdateTenantRequest bindUpdateTenantRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw TenantBindSupport.invalidParams("Invalid request data", "EOF");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = TenantBindSupport.MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw TenantBindSupport.invalidParams("Invalid request data",
                    GoJsonBindError.message(rawBody, e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            if (root == null || root.isNull()) {
                return new UpdateTenantRequest();
            }
            throw TenantBindSupport.invalidParams("Invalid request data",
                    "json: cannot unmarshal " + TenantBindSupport.goJsonKind(root) + " into Go value of type "
                            + "struct { Name *string \"json:\\\"name\\\" binding:\\\"omitempty,min=1,max=128\\\"\"; "
                            + "Description *string \"json:\\\"description\\\" binding:\\\"omitempty,max=512\\\"\" }");
        }
        String typeError = TenantBindSupport.goStringField(root, "name");
        if (typeError == null) {
            typeError = TenantBindSupport.goStringField(root, "description");
        }
        if (typeError != null) {
            throw TenantBindSupport.invalidParams("Invalid request data", typeError);
        }
        UpdateTenantRequest req = new UpdateTenantRequest();
        req.name = root.get("name") == null || root.get("name").isNull() ? null : root.get("name").asText();
        req.description = root.get("description") == null || root.get("description").isNull()
                ? null : root.get("description").asText();
        List<String> errors = new ArrayList<>();
        if (req.name != null) {
            int len = req.name.codePointCount(0, req.name.length());
            if (len < 1) {
                errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Name", "min"));
            } else if (len > 128) {
                errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Name", "max"));
            }
        }
        if (req.description != null && req.description.codePointCount(0, req.description.length()) > 512) {
            errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Description", "max"));
        }
        if (!errors.isEmpty()) {
            throw TenantBindSupport.invalidParams("Invalid request data", String.join("\n", errors));
        }
        return req;
    }


    /**
     * DELETE /tenants/{id}（对照 DeleteTenant，L1123-1162）：repo 层软删成员+租户、
     * 删不存在的 id 同样成功 → 恒 200 {"message","success"}。
     */
    @DeleteMapping("/api/v1/tenants/{id}")
    public Map<String, Object> deleteTenant(@PathVariable("id") String id) {
        try {
            tenantService.deleteTenant(Long.parseLong(id.trim()));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to delete workspace")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "Workspace deleted successfully");
        body.put("success", true);
        return body;
    }

    /** GET/PUT 共用：租户缺失 → 500 + details "record not found"（对照 GetTenantByID 的 gorm 原文透传）。 */
    private Tenant loadTenantOr500(long id, String message) {
        Tenant tenant = tenantService.getTenantById(id);
        if (tenant == null) {
            throw new BizException(AppError.internal(message).withDetails("record not found"));
        }
        return tenant;
    }

    /** 对照 dto.NewTenantResponse 的 includeSecrets = RoleFromContext ≥ admin。 */
    private static boolean contextRoleHasAdmin() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }

    // ── KV 分发器（对照 GetTenantKV / UpdateTenantKV，L1304-1395） ──────────

    @GetMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> getTenantKV(@PathVariable String key,
                                           jakarta.servlet.http.HttpServletRequest request) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> TenantMemberController.envelope(getWebSearch());
            // 对照 GetPromptTemplates：config.yaml 模板 + Accept-Language 本地化
            // （locale 解析 = middleware/language.go：env → Accept-Language 首 tag → zh-CN）
            case "prompt-templates" -> TenantMemberController.envelope(
                    com.ragagent.agent.PromptTemplateCatalog.toJson(
                            com.ragagent.agent.PromptTemplateCatalog.load(),
                            com.ragagent.agentm.service.BuiltinAgentRegistry
                                    .localeFromRequest(request.getHeader("Accept-Language"))));
            case "parser-engine-config" -> TenantMemberController.envelope(getParserEngine());
            case "storage-engine-config" -> TenantMemberController.envelope(getStorageEngine());
            case "chat-history-config" -> TenantMemberController.envelope(getChatHistory());
            case "retrieval-config" -> TenantMemberController.envelope(getRetrieval());
            case "memory-config" -> TenantMemberController.envelope(getMemory());
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    @PutMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> updateTenantKV(@PathVariable String key,
                                              @RequestBody(required = false) String rawBody) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> putWebSearch(rawBody);
            case "parser-engine-config" -> putParserEngine(rawBody);
            case "storage-engine-config" -> putStorageEngine(rawBody);
            case "chat-history-config" -> putChatHistory(rawBody);
            case "retrieval-config" -> putRetrieval(rawBody);
            case "memory-config" -> putMemory(rawBody);
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    /**
     * 对照分发器前置的敏感 key 门（L1309-1315 / L1363-1369）+
     * dto.CanViewIntegrationSecrets：角色 ≥ admin，或 API Key 具备
     * full-access / manage_tenant_settings。
     */
    private void requireIntegrationSecretsIfSensitive(String key) {
        if (!"web-search-config".equals(key)
                && !"parser-engine-config".equals(key)
                && !"storage-engine-config".equals(key)) {
            return;
        }
        if (canViewIntegrationSecrets()) {
            return;
        }
        throw new BizException(AppError.forbidden("integration configuration requires admin access"));
    }

    private boolean canViewIntegrationSecrets() {
        TenantRole role = TenantRole.fromString(TenantContext.currentRole());
        if (role != null && role.hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return false;
        }
        return scope.fullAccess() || scope.hasCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS);
    }

    /** 对照各子 handler 的 TenantInfoFromContext 检查：上下文无租户 → 400 */
    private Tenant requireContextTenant() {
        Long tenantId = TenantContext.currentTenantId();
        Tenant tenant = tenantId == null || tenantId == 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.badRequest("Workspace is empty"));
        }
        return tenant;
    }

    /**
     * Jsonb 列 → 强类型（对照 GORM Scan 语义）：SQL NULL → null；
     * jsonb 'null' → 已分配的零值对象（Go 对非 NULL 列先分配再 Unmarshal，
     * "null" 落在零值 struct 上）；对象 → 按字段绑定。
     */
    private static <T> T parseConfig(JsonNode node, Class<T> type) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isNull()) {
                return type.getDeclaredConstructor().newInstance();
            }
            return TenantBindSupport.MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode tenant config jsonb: " + e.getMessage(), e);
        }
    }

    /** PUT 失败分支统一形态：BizException(AppError) 透传，其余 → 500 + details */
    private RuntimeException updateFailed(String message, RuntimeException e) {
        if (e instanceof BizException be) {
            return be;
        }
        return new BizException(AppError.internal(message).withDetails(e.getMessage()));
    }

    /** 对照 gin.H{"success","data","message"}：字母序 data, message, success */
    private static Map<String, Object> envelopeWithMessage(Object data, String message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", data);
        body.put("message", message);
        body.put("success", true);
        return body;
    }

    // ── web-search-config（对照 L1398-1472） ────────────────────────────────

    private WebSearchConfig getWebSearch() {
        Tenant tenant = requireContextTenant();
        return TenantConfigRedaction.webSearchForResponse(
                parseConfig(tenant.getWebSearchConfig(), WebSearchConfig.class));
    }

    private Map<String, Object> putWebSearch(String rawBody) {
        WebSearchConfig cfg = TenantBindSupport.bindBody(rawBody, WebSearchConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new WebSearchConfig();
        }
        Tenant tenant = requireContextTenant();
        WebSearchConfig merged = TenantConfigRedaction.mergeWebSearch(
                cfg, parseConfig(tenant.getWebSearchConfig(), WebSearchConfig.class));
        if (merged.getMaxResults() < 1 || merged.getMaxResults() > 50) {
            throw new BizException(AppError.badRequest("max_results must be between 1 and 50"));
        }
        tenant.setWebSearchConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace web search config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.webSearchForResponse(merged),
                "Web search configuration updated successfully");
    }

    // ── parser-engine-config（对照 L1474-1533） ─────────────────────────────

    private ParserEngineConfig getParserEngine() {
        Tenant tenant = requireContextTenant();
        ParserEngineConfig data = TenantConfigRedaction.parserEngineForResponse(
                parseConfig(tenant.getParserEngineConfig(), ParserEngineConfig.class));
        return data == null ? new ParserEngineConfig() : data;
    }

    private Map<String, Object> putParserEngine(String rawBody) {
        ParserEngineConfig cfg = TenantBindSupport.bindBody(rawBody, ParserEngineConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new ParserEngineConfig();
        }
        Tenant tenant = requireContextTenant();
        ParserEngineConfig merged = TenantConfigRedaction.mergeParserEngine(
                cfg, parseConfig(tenant.getParserEngineConfig(), ParserEngineConfig.class));
        validateParserEngineOutboundUrls(merged);
        tenant.setParserEngineConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace parser engine config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.parserEngineForResponse(merged), "解析引擎配置已更新");
    }

    /** 对照 validateParserEngineOutboundURLs（L1904-1930）：四处 URL 过 SSRF */
    private void validateParserEngineOutboundUrls(ParserEngineConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (!cfg.getMineruEndpoint().trim().isEmpty()) {
            ssrfCheck("mineru_endpoint", cfg.getMineruEndpoint().trim());
        }
        if (!cfg.getMineruVlmServerUrl().trim().isEmpty()) {
            ssrfCheck("mineru_vlm_server_url", cfg.getMineruVlmServerUrl().trim());
        }
        if (!cfg.getOdlHybridUrl().trim().isEmpty()) {
            ssrfCheck("odl_hybrid_url", cfg.getOdlHybridUrl().trim());
        }
        if (!cfg.getPaddleOcrVlEndpoint().trim().isEmpty()) {
            ssrfCheck("paddleocr_vl_endpoint", cfg.getPaddleOcrVlEndpoint().trim());
        }
    }

    private void ssrfCheck(String field, String url) {
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (RuntimeException e) {
            throw new BizException(AppError.validation(
                    field + " failed SSRF validation: " + e.getMessage()));
        }
    }

    // ── storage-engine-config（对照 L1536-1598） ────────────────────────────

    private StorageEngineConfig getStorageEngine() {
        Tenant tenant = requireContextTenant();
        StorageEngineConfig data = TenantConfigRedaction.storageEngineForResponse(
                parseConfig(tenant.getStorageEngineConfig(), StorageEngineConfig.class));
        return data == null ? new StorageEngineConfig() : data;
    }

    private Map<String, Object> putStorageEngine(String rawBody) {
        StorageEngineConfig cfg = TenantBindSupport.bindBody(rawBody, StorageEngineConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new StorageEngineConfig();
        }
        // 对照 L1556-1570：provider 归一 → 缺省取 firstAllowed → 白名单校验（在租户检查之前）
        String provider = cfg.getDefaultProvider().trim().toLowerCase(java.util.Locale.ROOT);
        if (provider.isEmpty()) {
            provider = firstAllowedStorageProvider();
        }
        if (provider.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "No storage provider is allowed by STORAGE_ALLOW_LIST"));
        }
        if (!storageAllowList.isAllowed(provider)) {
            throw new BizException(AppError.badRequest(
                    "Storage provider is not allowed by STORAGE_ALLOW_LIST"));
        }
        cfg.setDefaultProvider(provider);
        Tenant tenant = requireContextTenant();
        StorageEngineConfig merged = TenantConfigRedaction.mergeStorageEngine(
                cfg, parseConfig(tenant.getStorageEngineConfig(), StorageEngineConfig.class));
        tenant.setStorageEngineConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace storage engine config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.storageEngineForResponse(merged), "存储引擎配置已更新");
    }

    /** 对照 firstAllowedStorageProvider：白名单序的第一个允许项（全允许时为 local） */
    private String firstAllowedStorageProvider() {
        List<String> allowed = storageAllowList.allowedList();
        return allowed.isEmpty() ? "" : allowed.get(0);
    }

    // ── chat-history-config（对照 L1634-1729） ──────────────────────────────

    private ChatHistoryConfig getChatHistory() {
        Tenant tenant = requireContextTenant();
        ChatHistoryConfig data = parseConfig(tenant.getChatHistoryConfig(), ChatHistoryConfig.class);
        return data == null ? new ChatHistoryConfig() : data;
    }

    private Map<String, Object> putChatHistory(String rawBody) {
        ChatHistoryConfig req = TenantBindSupport.bindBody(rawBody, ChatHistoryConfig.class, "Invalid request data");
        if (req == null) {
            req = new ChatHistoryConfig();
        }
        Tenant tenant = requireContextTenant();
        ChatHistoryConfig existing = parseConfig(tenant.getChatHistoryConfig(), ChatHistoryConfig.class);

        // 对照 L1680-1693：重建对象（knowledge_base_id 不受客户端控制），
        // 嵌入模型未变时沿用存量 KB
        ChatHistoryConfig cfg = new ChatHistoryConfig();
        cfg.setEnabled(req.isEnabled());
        cfg.setEmbeddingModelId(req.getEmbeddingModelId());
        if (existing != null && !existing.getKnowledgeBaseId().isEmpty()
                && existing.getEmbeddingModelId().equals(req.getEmbeddingModelId())) {
            cfg.setKnowledgeBaseId(existing.getKnowledgeBaseId());
        }

        // 对照 L1696-1716：enabled + 有模型 + 无 KB → 自动建隐藏 KB
        if (cfg.isEnabled() && !cfg.getEmbeddingModelId().isEmpty()
                && cfg.getKnowledgeBaseId().isEmpty()) {
            // 实体语义（名字/类型/临时标记/描述）归知识域，本域只传模型 id、只消费 id
            String kbId;
            try {
                kbId = knowledgeProvisioner.provisionChatHistoryKnowledgeBase(cfg.getEmbeddingModelId());
            } catch (RuntimeException e) {
                throw new BizException(AppError.internal("Failed to create chat history knowledge base")
                        .withDetails(e.getMessage()));
            }
            cfg.setKnowledgeBaseId(kbId);
        }

        tenant.setChatHistoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update chat history config", e);
        }
        return envelopeWithMessage(cfg, "Chat history configuration updated successfully");
    }

    // ── retrieval-config（对照 L1731-1806） ─────────────────────────────────

    private RetrievalConfig getRetrieval() {
        Tenant tenant = requireContextTenant();
        RetrievalConfig data = parseConfig(tenant.getRetrievalConfig(), RetrievalConfig.class);
        return data == null ? new RetrievalConfig() : data;
    }

    private Map<String, Object> putRetrieval(String rawBody) {
        RetrievalConfig cfg = TenantBindSupport.bindBody(rawBody, RetrievalConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new RetrievalConfig();
        }
        // 对照 L1759-1784：五段范围校验（在租户检查之前）
        if (cfg.getVectorThreshold() < 0 || cfg.getVectorThreshold() > 1) {
            throw new BizException(AppError.badRequest("vector_threshold must be between 0 and 1"));
        }
        if (cfg.getKeywordThreshold() < 0 || cfg.getKeywordThreshold() > 1) {
            throw new BizException(AppError.badRequest("keyword_threshold must be between 0 and 1"));
        }
        if (cfg.getRerankThreshold() < -10 || cfg.getRerankThreshold() > 10) {
            throw new BizException(AppError.badRequest("rerank_threshold must be between -10 and 10"));
        }
        if (cfg.getEmbeddingTopK() < 0 || cfg.getEmbeddingTopK() > 200) {
            throw new BizException(AppError.badRequest("embedding_top_k must be between 0 and 200"));
        }
        if (cfg.getRerankTopK() < 0 || cfg.getRerankTopK() > 200) {
            throw new BizException(AppError.badRequest("rerank_top_k must be between 0 and 200"));
        }
        Tenant tenant = requireContextTenant();
        tenant.setRetrievalConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update retrieval config", e);
        }
        return envelopeWithMessage(cfg, "Retrieval configuration updated successfully");
    }

    // ── memory-config（对照 L1808-1901；MemoryConfig 本体在 memory 模块） ────

    private MemoryConfig getMemory() {
        Tenant tenant = requireContextTenant();
        MemoryConfig data = parseConfig(tenant.getMemoryConfig(), MemoryConfig.class);
        if (data == null) {
            data = new MemoryConfig();
        }
        data.normalize();
        return data;
    }

    private Map<String, Object> putMemory(String rawBody) {
        MemoryConfig cfg = TenantBindSupport.bindBody(rawBody, MemoryConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new MemoryConfig();
        }
        // 对照 L1839-1879：七段校验（在 Normalize 与租户检查之前）
        String writeMode = cfg.getWriteMode();
        if (!writeMode.isEmpty()
                && !MemoryConfig.WRITE_MODE_EXPLICIT_ONLY.equals(writeMode)
                && !MemoryConfig.WRITE_MODE_AUTO.equals(writeMode)) {
            throw new BizException(AppError.badRequest("write_mode must be explicit_only or auto"));
        }
        if (cfg.getMaxItems() < 0 || cfg.getMaxItems() > MemoryConfig.MAX_ITEMS_CAP) {
            throw new BizException(AppError.badRequest("max_items must be between 0 and 2000"));
        }
        if (cfg.getExtractDelaySeconds() < 0
                || cfg.getExtractDelaySeconds() > MemoryConfig.MAX_EXTRACT_DELAY_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extract_delay_seconds must be between 0 and " + MemoryConfig.MAX_EXTRACT_DELAY_SECONDS));
        }
        if (cfg.getExtractMinIntervalSeconds() < 0
                || cfg.getExtractMinIntervalSeconds() > MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extract_min_interval_seconds must be between 0 and "
                            + MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS));
        }
        if (cfg.getEmbeddingModelId().length() > 64) {
            throw new BizException(AppError.badRequest("embedding_model_id is too long"));
        }
        if (cfg.getInterestThreshold() < 0
                || cfg.getInterestThreshold() > MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD) {
            // Go 原文就是 "between 1 and ..."（下界写死 1，尽管校验允许 0）——照抄
            throw new BizException(AppError.badRequest(
                    "interest_threshold must be between 1 and " + MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD));
        }
        if (cfg.getExtractInstructions().codePointCount(0, cfg.getExtractInstructions().length())
                > MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES) {
            throw new BizException(AppError.badRequest(
                    "extract_instructions must be at most " + MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES
                            + " characters"));
        }
        cfg.normalize();
        Tenant tenant = requireContextTenant();
        tenant.setMemoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update memory config", e);
        }
        return envelopeWithMessage(cfg, "Memory configuration updated successfully");
    }

}
