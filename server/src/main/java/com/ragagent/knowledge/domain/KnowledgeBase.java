package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * knowledge_bases 表实体。
 * 仓储行为契约（约定 §3）：
 * - 软删除 → 显式 isNull("deleted_at")
 * - jsonb 配置列：chunking/image/vlm/asr/indexing/cos_config 为**值类型**（Scan NULL → 零值结构，
 *   indexing_strategy Scan NULL → DefaultIndexingStrategy()，见 KbIndexingStrategy 注释）；
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
    @JsonProperty("is_temporary")
    private boolean isTemporary;
    private String description;
    @JsonProperty("tenant_id")
    private Long tenantId;
    @JsonProperty("creator_id")
    private String creatorId;
    @JsonProperty("chunking_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbChunkingConfig chunkingConfig;
    @JsonProperty("image_processing_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbImageProcessingConfig imageProcessingConfig;
    @JsonProperty("embedding_model_id")
    private String embeddingModelId;
    @JsonProperty("summary_model_id")
    private String summaryModelId;
    @JsonProperty("vlm_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbVlmConfig vlmConfig;
    @JsonProperty("asr_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbAsrConfig asrConfig;
    @JsonProperty("storage_provider_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbStorageProviderConfig storageProviderConfig;
    @JsonProperty("storage_backend_id")
    private String storageBackendId;
    @JsonProperty("storage_config")
    @TableField(value = "cos_config", typeHandler = PgJsonTypeHandler.class)
    private KbStorageConfig storageConfig;
    @JsonProperty("vector_store_id")
    private String vectorStoreId;
    @JsonProperty("extract_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode extractConfig;
    @JsonProperty("faq_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode faqConfig;
    @JsonProperty("question_generation_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode questionGenerationConfig;
    @JsonProperty("auto_tag_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode autoTagConfig;
    @JsonProperty("wiki_config")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode wikiConfig;
    @JsonProperty("indexing_strategy")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbIndexingStrategy indexingStrategy;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    // ── 无列映射标签 瞬态（查询回填） ──
    @JsonProperty("is_pinned")
    @TableField(exist = false)
    private boolean isPinned;
    @JsonProperty("pinned_at")
    @TableField(exist = false)
    private OffsetDateTime pinnedAt;
    @JsonProperty("knowledge_count")
    @TableField(exist = false)
    private long knowledgeCount;
    @JsonProperty("chunk_count")
    @TableField(exist = false)
    private long chunkCount;
    @JsonProperty("is_processing")
    @TableField(exist = false)
    private boolean isProcessing;
    @JsonProperty("processing_count")
    @TableField(exist = false)
    private long processingCount;
    @JsonProperty("share_count")
    @TableField(exist = false)
    private long shareCount;
    @JsonProperty("creator_name")
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
    public KbChunkingConfig getChunkingConfig() {
        if (chunkingConfig == null) chunkingConfig = new KbChunkingConfig();
        return chunkingConfig;
    }
    public void setChunkingConfig(KbChunkingConfig v) { chunkingConfig = v; }
    public KbImageProcessingConfig getImageProcessingConfig() {
        if (imageProcessingConfig == null) imageProcessingConfig = new KbImageProcessingConfig();
        return imageProcessingConfig;
    }
    public void setImageProcessingConfig(KbImageProcessingConfig v) { imageProcessingConfig = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getSummaryModelId() { return summaryModelId; }
    public void setSummaryModelId(String v) { summaryModelId = v == null ? "" : v; }
    public KbVlmConfig getVlmConfig() {
        if (vlmConfig == null) vlmConfig = new KbVlmConfig();
        return vlmConfig;
    }
    public void setVlmConfig(KbVlmConfig v) { vlmConfig = v; }
    public KbAsrConfig getAsrConfig() {
        if (asrConfig == null) asrConfig = new KbAsrConfig();
        return asrConfig;
    }
    public void setAsrConfig(KbAsrConfig v) { asrConfig = v; }
    public KbStorageProviderConfig getStorageProviderConfig() { return storageProviderConfig; }
    public void setStorageProviderConfig(KbStorageProviderConfig v) { storageProviderConfig = v; }
    public String getStorageBackendId() { return storageBackendId; }
    public void setStorageBackendId(String v) { storageBackendId = v; }
    public KbStorageConfig getStorageConfig() {
        if (storageConfig == null) storageConfig = new KbStorageConfig();
        return storageConfig;
    }
    public void setStorageConfig(KbStorageConfig v) { storageConfig = v; }
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
    public KbIndexingStrategy getIndexingStrategy() {
        // NULL→Default 分支实际到不了）；IsZero→Default 只发生在 service 读路径的
        // EnsureDefaults 调用点（KB list/get），chunk 等路径不做此默认。
        // 历史近似（null→Default）与既有 golden 全兼容，仅补 w5s 实录钉住的 carve-out：
        // faq 且 faq_config 为 NULL → EnsureDefaults 提前 return，策略保持零值。
        if (indexingStrategy != null) {
            return indexingStrategy;
        }
        return "faq".equals(type) && faqConfig == null
                ? new KbIndexingStrategy() : KbIndexingStrategy.defaultStrategy();
    }
    public void setIndexingStrategy(KbIndexingStrategy v) { indexingStrategy = v; }
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
        KbStorageProviderConfig c = new KbStorageProviderConfig();
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
        KbIndexingStrategy s = getIndexingStrategy();
        return new Capabilities(s.isVectorEnabled(), s.isKeywordEnabled(), s.isWikiEnabled(),
                s.isGraphEnabled() && extractConfig != null && extractConfig.path("enabled").asBoolean(false),
                "faq".equals(type));
    }

    public record Capabilities(boolean vector, boolean keyword, boolean wiki, boolean graph, boolean faq) {}
}
