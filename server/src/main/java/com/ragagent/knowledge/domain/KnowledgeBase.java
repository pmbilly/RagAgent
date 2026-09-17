package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * knowledge_bases 表实体（对照 Go types/knowledgebase.go KnowledgeBase L59-149）。
 *
 * GORM 隐式行为清单（约定 §3）：
 * - 软删除 → 显式 isNull("deleted_at")
 * - 钩子：无 BeforeCreate（UUID 由 service 生成，对照 service/knowledgebase.go L122）
 * - jsonb 配置列：chunking/image/vlm/asr/indexing/cos_config 为**值类型**（Scan NULL → 零值结构，
 *   indexing_strategy Scan NULL → DefaultIndexingStrategy()，见 KbIndexingStrategy 注释）；
 *   storage_provider_config/extract_config/faq_config/wiki_config/question_generation_config/auto_tag_config
 *   为指针（NULL → null）
 * - vector_store_id：空串归一化为 NULL（对照 Normalize L877）
 * - gorm:"-" 瞬态字段：is_pinned/pinned_at/knowledge_count/chunk_count/is_processing/processing_count/
 *   share_count/creator_name——查询侧计算回填，不落库
 * - storage_config 的 gorm 列是 cos_config（JSON 键 storage_config）
 *
 * JSON 输出不走本实体：响应由 KnowledgeBaseResponseBuilder 按「Go map 字母序」构造（MarshalJSON→map 合并）。
 */
@TableName(value = "knowledge_bases", autoResultMap = true)
public class KnowledgeBase {

    @TableId(type = IdType.INPUT)
    private String id;
    /** Go string 零值：缺省 ""（H2/DB 列 NOT NULL；请求缺省字段不经过 setter） */
    private String name = "";
    /** document/faq/wiki，默认 document（对照 EnsureDefaults）；Go 零值 ""（请求缺省时非 null） */
    private String type = "";
    private boolean isTemporary;
    private String description;
    private Long tenantId;
    private String creatorId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbChunkingConfig chunkingConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbImageProcessingConfig imageProcessingConfig;
    private String embeddingModelId;
    private String summaryModelId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbVlmConfig vlmConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbAsrConfig asrConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private KbStorageProviderConfig storageProviderConfig;
    private String storageBackendId;
    /** gorm 列 cos_config（JSON 键 storage_config） */
    @TableField(value = "cos_config", typeHandler = PgJsonTypeHandler.class)
    private KbStorageConfig storageConfig;
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
    private KbIndexingStrategy indexingStrategy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    // ── gorm:"-" 瞬态（查询回填） ──
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
    public String getCreatorId() { return creatorId; }
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
        if (indexingStrategy == null) indexingStrategy = KbIndexingStrategy.defaultStrategy();
        return indexingStrategy;
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

    // ── 对照 GetStorageProvider：新配置优先，回退 legacy cos_config.provider ──
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

    /** 对照 Normalize：空串 vector_store_id 折成 null */
    public void normalizeVectorStoreId() {
        if (vectorStoreId != null && vectorStoreId.isEmpty()) {
            vectorStoreId = null;
        }
    }

    public boolean hasVectorStore() {
        return vectorStoreId != null && !vectorStoreId.isEmpty();
    }

    // ── 对照 Capabilities（字段序 vector/keyword/wiki/graph/faq） ──
    public Capabilities capabilities() {
        KbIndexingStrategy s = getIndexingStrategy();
        return new Capabilities(s.isVectorEnabled(), s.isKeywordEnabled(), s.isWikiEnabled(),
                s.isGraphEnabled() && extractConfig != null && extractConfig.path("enabled").asBoolean(false),
                "faq".equals(type));
    }

    public record Capabilities(boolean vector, boolean keyword, boolean wiki, boolean graph, boolean faq) {}
}
