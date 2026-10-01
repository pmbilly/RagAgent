package com.ragagent.auth.controller;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.domain.tenantconfig.ParserEngineConfig;
import com.ragagent.auth.domain.tenantconfig.RetrievalConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.auth.domain.tenantconfig.TenantConfigRedaction;
import com.ragagent.auth.domain.tenantconfig.WebSearchConfig;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.tenant.TenantRole;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * KV 配置分发协作者（对照 Go GetTenantKV/UpdateTenantKV L1304-1901，
 * 自 {@link TenantCatalogController} 机械搬出）：6 个 DB-backed key 的
 * get/put、敏感 key 的 admin 门、jsonb 强类型解析与各配置段校验。
 * 持门面回引取 tenantService/ssrfGuard/storageAllowList/knowledgeProvisioner。
 */
final class TenantConfigOps {

    private final TenantCatalogController service;

    TenantConfigOps(TenantCatalogController service) {
        this.service = service;
    }

    // ── KV 分发器（对照 GetTenantKV / UpdateTenantKV，L1304-1395） ──────────

    public Object getTenantKV(@PathVariable String key,
                              jakarta.servlet.http.HttpServletRequest request) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> {
                WebSearchConfig ws = getWebSearch();
                yield ws == null
                        ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                        : ws;
            }
            // 对照 GetPromptTemplates：config.yaml 模板 + Accept-Language 本地化
            // （locale 解析 = middleware/language.go：env → Accept-Language 首 tag → zh-CN）
            case "prompt-templates" ->
                    com.ragagent.agent.PromptTemplateCatalog.toJson(
                            com.ragagent.agent.PromptTemplateCatalog.load(),
                            com.ragagent.agentm.service.BuiltinAgentRegistry
                                    .localeFromRequest(request.getHeader("Accept-Language")));
            case "parser-engine-config" -> getParserEngine();
            case "storage-engine-config" -> getStorageEngine();
            case "chat-history-config" -> getChatHistory();
            case "retrieval-config" -> getRetrieval();
            case "memory-config" -> getMemory();
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    public Object updateTenantKV(@PathVariable String key,
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
        Tenant tenant = tenantId == null || tenantId == 0 ? null : service.tenantService.getTenantById(tenantId);
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

    // ── web-search-config（对照 L1398-1472） ────────────────────────────────

    private WebSearchConfig getWebSearch() {
        Tenant tenant = requireContextTenant();
        return TenantConfigRedaction.webSearchForResponse(
                parseConfig(tenant.getWebSearchConfig(), WebSearchConfig.class));
    }

    private Object putWebSearch(String rawBody) {
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
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace web search config", e);
        }
        return TenantConfigRedaction.webSearchForResponse(merged);
    }

    // ── parser-engine-config（对照 L1474-1533） ─────────────────────────────

    private ParserEngineConfig getParserEngine() {
        Tenant tenant = requireContextTenant();
        ParserEngineConfig data = TenantConfigRedaction.parserEngineForResponse(
                parseConfig(tenant.getParserEngineConfig(), ParserEngineConfig.class));
        return data == null ? new ParserEngineConfig() : data;
    }

    private Object putParserEngine(String rawBody) {
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
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace parser engine config", e);
        }
        return TenantConfigRedaction.parserEngineForResponse(merged);
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
            service.ssrfGuard.validateURLForSSRF(url);
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

    private Object putStorageEngine(String rawBody) {
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
        if (!service.storageAllowList.isAllowed(provider)) {
            throw new BizException(AppError.badRequest(
                    "Storage provider is not allowed by STORAGE_ALLOW_LIST"));
        }
        cfg.setDefaultProvider(provider);
        Tenant tenant = requireContextTenant();
        StorageEngineConfig merged = TenantConfigRedaction.mergeStorageEngine(
                cfg, parseConfig(tenant.getStorageEngineConfig(), StorageEngineConfig.class));
        tenant.setStorageEngineConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace storage engine config", e);
        }
        return TenantConfigRedaction.storageEngineForResponse(merged);
    }

    /** 对照 firstAllowedStorageProvider：白名单序的第一个允许项（全允许时为 local） */
    private String firstAllowedStorageProvider() {
        List<String> allowed = service.storageAllowList.allowedList();
        return allowed.isEmpty() ? "" : allowed.get(0);
    }

    // ── chat-history-config（对照 L1634-1729） ──────────────────────────────

    private ChatHistoryConfig getChatHistory() {
        Tenant tenant = requireContextTenant();
        ChatHistoryConfig data = parseConfig(tenant.getChatHistoryConfig(), ChatHistoryConfig.class);
        return data == null ? new ChatHistoryConfig() : data;
    }

    private Object putChatHistory(String rawBody) {
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
                kbId = service.knowledgeProvisioner.provisionChatHistoryKnowledgeBase(cfg.getEmbeddingModelId());
            } catch (RuntimeException e) {
                throw new BizException(AppError.internal("Failed to create chat history knowledge base")
                        .withDetails(e.getMessage()));
            }
            cfg.setKnowledgeBaseId(kbId);
        }

        tenant.setChatHistoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update chat history config", e);
        }
        return cfg;
    }

    // ── retrieval-config（对照 L1731-1806） ─────────────────────────────────

    private RetrievalConfig getRetrieval() {
        Tenant tenant = requireContextTenant();
        RetrievalConfig data = parseConfig(tenant.getRetrievalConfig(), RetrievalConfig.class);
        return data == null ? new RetrievalConfig() : data;
    }

    private Object putRetrieval(String rawBody) {
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
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update retrieval config", e);
        }
        return cfg;
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

    private Object putMemory(String rawBody) {
        MemoryConfig cfg = TenantBindSupport.bindBody(rawBody, MemoryConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new MemoryConfig();
        }
        // 对照 L1839-1879：七段校验（在 Normalize 与租户检查之前）
        String writeMode = cfg.getWriteMode();
        if (!writeMode.isEmpty()
                && !MemoryConfig.WRITE_MODE_EXPLICIT_ONLY.equals(writeMode)
                && !MemoryConfig.WRITE_MODE_AUTO.equals(writeMode)) {
            throw new BizException(AppError.badRequest("writeMode must be explicit_only or auto"));
        }
        if (cfg.getMaxItems() < 0 || cfg.getMaxItems() > MemoryConfig.MAX_ITEMS_CAP) {
            throw new BizException(AppError.badRequest("maxItems must be between 0 and 2000"));
        }
        if (cfg.getExtractDelaySeconds() < 0
                || cfg.getExtractDelaySeconds() > MemoryConfig.MAX_EXTRACT_DELAY_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extractDelaySeconds must be between 0 and " + MemoryConfig.MAX_EXTRACT_DELAY_SECONDS));
        }
        if (cfg.getExtractMinIntervalSeconds() < 0
                || cfg.getExtractMinIntervalSeconds() > MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extractMinIntervalSeconds must be between 0 and "
                            + MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS));
        }
        if (cfg.getEmbeddingModelId().length() > 64) {
            throw new BizException(AppError.badRequest("embeddingModelId is too long"));
        }
        if (cfg.getInterestThreshold() < 0
                || cfg.getInterestThreshold() > MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD) {
            // 下界文案写死 1（尽管校验允许 0）——Go 原文如此，换锚只改字段名
            throw new BizException(AppError.badRequest(
                    "interestThreshold must be between 1 and " + MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD));
        }
        if (cfg.getExtractInstructions().codePointCount(0, cfg.getExtractInstructions().length())
                > MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES) {
            throw new BizException(AppError.badRequest(
                    "extractInstructions must be at most " + MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES
                            + " characters"));
        }
        cfg.normalize();
        Tenant tenant = requireContextTenant();
        tenant.setMemoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update memory config", e);
        }
        return cfg;
    }
}
