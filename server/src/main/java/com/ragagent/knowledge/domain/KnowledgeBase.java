package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * knowledge_bases 表实体。
 * 仓储行为契约（本仓约定）：
 * - 软删除 → 显式 isNull("deleted_at")
 * - jsonb 配置列：chunking/image/vlm/asr/indexing/storage_config 为**值类型**（Scan NULL → 零值结构，
 *   indexing_strategy Scan NULL → DefaultIndexingStrategy()，见 KnowledgeBaseIndexingStrategy 注释）；
 *   storage_provider_config/extract_config/faq_config/wiki_config/question_generation_config/auto_tag_config
 *   为指针（NULL → null）
 * - vector_store_id：空串归一化为 NULL
 * - 无列映射标签 瞬态字段：is_pinned/pinned_at/knowledge_count/chunk_count/is_processing/processing_count/
 *   share_count/creator_name——查询侧计算回填，不落库
 */
@TableName(value = "knowledge_bases", autoResultMap = true)
public class KnowledgeBase {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name = "";
    private String type = "";
    private boolean isTemporary;
    private String description;
    private Long tenantId;
    private String creatorId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseChunkingConfig chunkingConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseImageProcessingConfig imageProcessingConfig;
    private String embeddingModelId;
    private String summaryModelId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseVlmConfig vlmConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseAsrConfig asrConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseStorageProviderConfig storageProviderConfig;
    private String storageBackendId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseStorageConfig storageConfig;
    private String vectorStoreId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode extractConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode faqConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode questionGenerationConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode autoTagConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode wikiConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KnowledgeBaseIndexingStrategy indexingStrategy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    // ── 无列映射标签 瞬态（查询回填） ──
    @TableField(exist = false)
    private boolean isPinned;
    @TableField(exist = false)
    private OffsetDateTime pinnedAt;
    @TableField(exist = false)
    private long knowledgeCount;
    @TableField(exist = false)
    private long chunkCount;
    @TableField(exist = false)
    private boolean isProcessing;
    @TableField(exist = false)
    private long processingCount;
    @TableField(exist = false)
    private long shareCount;
    @TableField(exist = false)
    private String creatorName;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public boolean isIsTemporary() { return isTemporary; }
    public void setIsTemporary(boolean v) { isTemporary = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    /**
     * 走查实案：dev 库 A/B 种子行 creator_id 为 NULL → 列表服务 {@code .isEmpty()} NPE 500；
     * 响应形态与全部调用点（ChunkAccessGuard/Controller 的 ownership 判定等）。
     */
    public String getCreatorId() { return creatorId == null ? "" : creatorId; }
    public void setCreatorId(String v) { creatorId = v == null ? "" : v; }
    public KnowledgeBaseChunkingConfig getChunkingConfig() {
        if (chunkingConfig == null) chunkingConfig = new KnowledgeBaseChunkingConfig();
        return chunkingConfig;
    }
    public void setChunkingConfig(KnowledgeBaseChunkingConfig v) { chunkingConfig = v; }
    public KnowledgeBaseImageProcessingConfig getImageProcessingConfig() {
        if (imageProcessingConfig == null) imageProcessingConfig = new KnowledgeBaseImageProcessingConfig();
        return imageProcessingConfig;
    }
    public void setImageProcessingConfig(KnowledgeBaseImageProcessingConfig v) { imageProcessingConfig = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getSummaryModelId() { return summaryModelId; }
    public void setSummaryModelId(String v) { summaryModelId = v == null ? "" : v; }
    public KnowledgeBaseVlmConfig getVlmConfig() {
        if (vlmConfig == null) vlmConfig = new KnowledgeBaseVlmConfig();
        return vlmConfig;
    }
    public void setVlmConfig(KnowledgeBaseVlmConfig v) { vlmConfig = v; }
    public KnowledgeBaseAsrConfig getAsrConfig() {
        if (asrConfig == null) asrConfig = new KnowledgeBaseAsrConfig();
        return asrConfig;
    }
    public void setAsrConfig(KnowledgeBaseAsrConfig v) { asrConfig = v; }
    public KnowledgeBaseStorageProviderConfig getStorageProviderConfig() { return storageProviderConfig; }
    public void setStorageProviderConfig(KnowledgeBaseStorageProviderConfig v) { storageProviderConfig = v; }
    public String getStorageBackendId() { return storageBackendId; }
    public void setStorageBackendId(String v) { storageBackendId = v; }
    public KnowledgeBaseStorageConfig getStorageConfig() {
        if (storageConfig == null) storageConfig = new KnowledgeBaseStorageConfig();
        return storageConfig;
    }
    public void setStorageConfig(KnowledgeBaseStorageConfig v) { storageConfig = v; }
    public String getVectorStoreId() { return vectorStoreId; }
    public void setVectorStoreId(String v) { vectorStoreId = v; }
    public JsonNode getExtractConfig() { return extractConfig; }
    public void setExtractConfig(JsonNode v) { extractConfig = v; }
    public JsonNode getFaqConfig() { return faqConfig; }
    public void setFaqConfig(JsonNode v) { faqConfig = v; }
    public JsonNode getQuestionGenerationConfig() { return questionGenerationConfig; }
    public void setQuestionGenerationConfig(JsonNode v) { questionGenerationConfig = v; }
    public JsonNode getAutoTagConfig() { return autoTagConfig; }
    public void setAutoTagConfig(JsonNode v) { autoTagConfig = v; }
    public JsonNode getWikiConfig() { return wikiConfig; }
    public void setWikiConfig(JsonNode v) { wikiConfig = v; }
    public KnowledgeBaseIndexingStrategy getIndexingStrategy() {
        // NULL→Default 分支实际到不了）；IsZero→Default 只发生在 service 读路径的
        // EnsureDefaults 调用点（KB list/get），chunk 等路径不做此默认。
        // 历史近似（null→Default）与既有 契约样例 全兼容，仅补 w5s 实录钉住的 carve-out：
        // faq 且 faq_config 为 NULL → EnsureDefaults 提前 return，策略保持零值。
        if (indexingStrategy != null) {
            return indexingStrategy;
        }
        return "faq".equals(type) && faqConfig == null
                ? new KnowledgeBaseIndexingStrategy() : KnowledgeBaseIndexingStrategy.defaultStrategy();
    }
    public void setIndexingStrategy(KnowledgeBaseIndexingStrategy v) { indexingStrategy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }

    public boolean isIsPinned() { return isPinned; }
    public void setIsPinned(boolean v) { isPinned = v; }
    public OffsetDateTime getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(OffsetDateTime v) { pinnedAt = v; }
    public long getKnowledgeCount() { return knowledgeCount; }
    public void setKnowledgeCount(long v) { knowledgeCount = v; }
    public long getChunkCount() { return chunkCount; }
    public void setChunkCount(long v) { chunkCount = v; }
    public boolean isIsProcessing() { return isProcessing; }
    public void setIsProcessing(boolean v) { isProcessing = v; }
    public long getProcessingCount() { return processingCount; }
    public void setProcessingCount(long v) { processingCount = v; }
    public long getShareCount() { return shareCount; }
    public void setShareCount(long v) { shareCount = v; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String v) { creatorName = v; }

    public String getStorageProvider() {
        if (storageProviderConfig != null) {
            String p = storageProviderConfig.getProvider().toLowerCase().trim();
            if (!p.isEmpty() && !p.equals("__pending_env__")) {
                return p;
            }
        }
        return getStorageConfig().getProvider().toLowerCase().trim();
    }

    public void setStorageProvider(String provider) {
        KnowledgeBaseStorageProviderConfig c = new KnowledgeBaseStorageProviderConfig();
        c.setProvider(provider);
        this.storageProviderConfig = c;
    }

    /** 空串 vector_store_id 折成 null */
    public void normalizeVectorStoreId() {
        if (vectorStoreId != null && vectorStoreId.isEmpty()) {
            vectorStoreId = null;
        }
    }

    public boolean hasVectorStore() {
        return vectorStoreId != null && !vectorStoreId.isEmpty();
    }

    public Capabilities capabilities() {
        KnowledgeBaseIndexingStrategy s = getIndexingStrategy();
        return new Capabilities(s.isVectorEnabled(), s.isKeywordEnabled(), s.isWikiEnabled(),
                s.isGraphEnabled() && extractConfig != null && extractConfig.path("enabled").asBoolean(false),
                "faq".equals(type));
    }

    /** 知识库的检索能力开关快照：由各检索开关列派生，供响应直接输出（不落库）。 */
    public record Capabilities(boolean vector, boolean keyword, boolean wiki, boolean graph, boolean faq) {}
}
