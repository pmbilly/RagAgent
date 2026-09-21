package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * KB 响应构造器（对照 Go handler/knowledgebase.go buildKBResponse L109-141）。
 *
 * Go 路径：json.Marshal(KnowledgeBase)（MarshalJSON 追加 capabilities）→ 反序列化为
 * map[string]interface{} → 条件合并 vector_store_* → gin.H 输出。
 * **两次经 map，全部键（含嵌套配置对象）按字母序输出**——Java 用 TreeMap 复刻。
 *
 * 字段来源（对照 golden kb-create.json 锁定）：
 * - 实体全部字段（deleted_at 恒输出 null；description 非 omitempty 恒输出）
 * - capabilities（vector/keyword/wiki/graph/faq，嵌套同样字母序：faq/graph/keyword/vector/wiki）
 * - vector_store_name/source/engine_type/status：env 回退显示（System default / env / <driver> / available）
 * - 共享视图删除 vector_store_id（阶段 3 不支持跨租户共享，恒保留）
 */
public final class KnowledgeBaseResponseBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeBaseResponseBuilder() {}

    /** 对照 buildKBResponse：env 回退 store view（无 vector_stores 绑定的 KB） */
    public static Map<String, Object> build(KnowledgeBase kb, String retrieveDriver) {
        return build(kb, retrieveDriver, true);
    }

    /**
     * {@code includeEngineType=false} 对照 Go 的 storeView.EngineType=="" 分支：
     * {@code m["vector_store_engine_type"] = storeView.EngineType} 只在**非空**时写入键。
     * duplicate 走 resolveKBStoreView → envDefaultStoreView，其 EngineType 取
     * envStores[0]——当前部署（Golden 实录 2026-09-19）envStores 为空 → 键整体缺席。
     * （阶段 3 的 kb-get golden 里有 "postgres"：录制当时 envStores 非空——两份 golden
     * 反映的是两种部署状态，别互相"修"。）
     */
    public static Map<String, Object> build(KnowledgeBase kb, String retrieveDriver,
                                            boolean includeEngineType) {
        Map<String, Object> m = new TreeMap<>();

        m.put("asr_config", treeSorted(kb.getAsrConfig()));
        m.put("auto_tag_config", json(kb.getAutoTagConfig()));
        m.put("capabilities", capabilities(kb));
        m.put("chunk_count", kb.getChunkCount());
        m.put("chunking_config", treeSorted(kb.getChunkingConfig()));
        m.put("created_at", kb.getCreatedAt());
        // creator_id：Go 非指针 string，空为 ""（API-key 创建的 KB 为 tenant-owned 空串）
        m.put("creator_id", kb.getCreatorId());
        m.put("deleted_at", kb.getDeletedAt());
        m.put("description", kb.getDescription() == null ? "" : kb.getDescription());
        m.put("embedding_model_id", nullToEmpty(kb.getEmbeddingModelId()));
        m.put("extract_config", json(kb.getExtractConfig()));
        m.put("faq_config", json(faqConfigView(kb)));
        m.put("id", kb.getId());
        m.put("image_processing_config", treeSorted(kb.getImageProcessingConfig()));
        m.put("indexing_strategy", treeSorted(kb.getIndexingStrategy()));
        m.put("is_pinned", kb.isIsPinned());
        m.put("is_processing", kb.isIsProcessing());
        m.put("is_temporary", kb.isIsTemporary());
        m.put("knowledge_count", kb.getKnowledgeCount());
        m.put("name", kb.getName());
        m.put("pinned_at", kb.getPinnedAt());
        m.put("processing_count", kb.getProcessingCount());
        m.put("question_generation_config", json(kb.getQuestionGenerationConfig()));
        m.put("share_count", kb.getShareCount());
        // storage_backend_id：Go 实体 json 带 omitempty（buildKBResponse 走 json.Marshal
        // 实体 → map），空值键整体缺席；非空才输出（kb-list.json 等旧 golden 有值场景不变）。
        if (kb.getStorageBackendId() != null && !kb.getStorageBackendId().isEmpty()) {
            m.put("storage_backend_id", kb.getStorageBackendId());
        }
        m.put("storage_config", treeSorted(kb.getStorageConfig()));
        m.put("storage_provider_config",
                kb.getStorageProviderConfig() == null ? null : treeSorted(kb.getStorageProviderConfig()));
        m.put("summary_model_id", nullToEmpty(kb.getSummaryModelId()));
        m.put("tenant_id", kb.getTenantId() == null ? 0 : kb.getTenantId());
        m.put("type", kb.getType());
        m.put("updated_at", kb.getUpdatedAt());
        // vector_store_id：有绑定才输出（阶段 3 env 回退：无绑定 → 键不出现？golden 无该键 → 不输出）
        if (kb.hasVectorStore()) {
            m.put("vector_store_id", kb.getVectorStoreId());
        }
        if (includeEngineType) {
            m.put("vector_store_engine_type", engineType(retrieveDriver));
        }
        m.put("vector_store_name", "System default");
        m.put("vector_store_source", "env");
        m.put("vector_store_status", "available");
        m.put("vlm_config", treeSorted(kb.getVlmConfig()));
        m.put("wiki_config", json(kb.getWikiConfig()));
        return m;
    }

    private static Map<String, Object> capabilities(KnowledgeBase kb) {
        KnowledgeBase.Capabilities c = kb.capabilities();
        Map<String, Object> m = new TreeMap<>();
        m.put("faq", c.faq());
        m.put("graph", c.graph());
        m.put("keyword", c.keyword());
        m.put("vector", c.vector());
        m.put("wiki", c.wiki());
        return m;
    }

    /** RETRIEVE_DRIVER 首段 → 引擎类型显示（对照 golden "postgres"） */
    static String engineType(String retrieveDriver) {
        if (retrieveDriver == null || retrieveDriver.isBlank()) {
            return "postgres";
        }
        String first = retrieveDriver.split(",")[0].trim().toLowerCase();
        return first.isEmpty() ? "postgres" : first;
    }

    /**
     * 对照 ListMoveTargets 的**原始实体序列化**（struct 声明序，无 map 排序、无 vector_store_* 增强）。
     * omitempty：storage_backend_id / vector_store_id / creator_name。
     */
    public static Map<String, Object> buildRaw(KnowledgeBase kb) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("type", kb.getType());
        m.put("is_temporary", kb.isIsTemporary());
        m.put("description", kb.getDescription() == null ? "" : kb.getDescription());
        m.put("tenant_id", kb.getTenantId() == null ? 0 : kb.getTenantId());
        m.put("creator_id", kb.getCreatorId());
        m.put("chunking_config", tree(kb.getChunkingConfig()));
        m.put("image_processing_config", tree(kb.getImageProcessingConfig()));
        m.put("embedding_model_id", nullToEmpty(kb.getEmbeddingModelId()));
        m.put("summary_model_id", nullToEmpty(kb.getSummaryModelId()));
        m.put("vlm_config", tree(kb.getVlmConfig()));
        m.put("asr_config", tree(kb.getAsrConfig()));
        m.put("storage_provider_config",
                kb.getStorageProviderConfig() == null ? null : tree(kb.getStorageProviderConfig()));
        if (kb.getStorageBackendId() != null && !kb.getStorageBackendId().isEmpty()) {
            m.put("storage_backend_id", kb.getStorageBackendId());
        }
        m.put("storage_config", tree(kb.getStorageConfig()));
        if (kb.hasVectorStore()) {
            m.put("vector_store_id", kb.getVectorStoreId());
        }
        m.put("extract_config", json(kb.getExtractConfig()));
        m.put("faq_config", json(faqConfigView(kb)));
        m.put("question_generation_config", json(kb.getQuestionGenerationConfig()));
        m.put("auto_tag_config", json(kb.getAutoTagConfig()));
        m.put("wiki_config", json(kb.getWikiConfig()));
        m.put("indexing_strategy", tree(kb.getIndexingStrategy()));
        m.put("is_pinned", kb.isIsPinned());
        m.put("pinned_at", kb.getPinnedAt());
        m.put("created_at", kb.getCreatedAt());
        m.put("updated_at", kb.getUpdatedAt());
        m.put("deleted_at", kb.getDeletedAt());
        m.put("knowledge_count", kb.getKnowledgeCount());
        m.put("chunk_count", kb.getChunkCount());
        m.put("is_processing", kb.isIsProcessing());
        m.put("processing_count", kb.getProcessingCount());
        m.put("share_count", kb.getShareCount());
        if (kb.getCreatorName() != null && !kb.getCreatorName().isEmpty()) {
            m.put("creator_name", kb.getCreatorName());
        }
        // raw：Go Capabilities struct 声明序 vector,keyword,wiki,graph,faq
        var c = kb.capabilities();
        Map<String, Object> caps = new java.util.LinkedHashMap<>();
        caps.put("vector", c.vector());
        caps.put("keyword", c.keyword());
        caps.put("wiki", c.wiki());
        caps.put("graph", c.graph());
        caps.put("faq", c.faq());
        m.put("capabilities", caps);
        return m;
    }

    /** Jackson 树 → 键排序的 Map（TreeMap），null 原样 */
    private static Object json(JsonNode node) {
        return node == null || node.isNull() ? null : tree(node);
    }

    /**
     * 对照 EnsureDefaults 的 FAQ 段（types/knowledgebase.go L744-758）：type=faq 时
     * faq_config 物化默认值（NULL → 全默认；缺字段补默认，FAQConfig 仅这两个字段）；
     * type!=faq → FAQConfig 清空（JSON null）。字段序 = Go struct 声明序（亦字母序）。
     */
    private static JsonNode faqConfigView(KnowledgeBase kb) {
        if (!"faq".equals(kb.getType())) {
            return null;
        }
        JsonNode raw = kb.getFaqConfig();
        String indexMode = raw != null && raw.isObject() ? raw.path("index_mode").asText("") : "";
        String questionIndexMode =
                raw != null && raw.isObject() ? raw.path("question_index_mode").asText("") : "";
        com.fasterxml.jackson.databind.node.ObjectNode out = MAPPER.createObjectNode();
        out.put("index_mode", indexMode.isEmpty() ? "question_answer" : indexMode);
        out.put("question_index_mode", questionIndexMode.isEmpty() ? "combined" : questionIndexMode);
        return out;
    }

    /** raw 序列化：嵌套对象保序（Go struct 声明序 = valueToTree + @JsonPropertyOrder），不排序 */
    private static Object tree(Object pojo) {
        if (pojo == null) {
            return null;
        }
        return MAPPER.valueToTree(pojo);
    }



    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** list 专用：Go buildKBListResponse 比单个 buildKBResponse 多 creator_name */
    public static java.util.Map<String, Object> buildListItem(KnowledgeBase kb, String retrieveDriver) {
        java.util.Map<String, Object> m = build(kb, retrieveDriver);
        m.put("creator_name", nullToEmpty(kb.getCreatorName()));
        return m;
    }

    /**
     * 共享 agent 分支的 KB 列表项（W5α，对照 buildKBListResponse 的 view 三态）：
     * <ul>
     *   <li>KB 无 vector_stores 绑定 → envDefaultStoreView（本部署 engine_type 缺席，
     *       与 2026-09-21 w5s 实录一致）；</li>
     *   <li>有绑定且跨租户（agent 分支恒然——share 排除同空间）→ SharedStoreDisplay：
     *       vector_store_id/vector_store_name 删除、source="shared"、status="available"；</li>
     *   <li>Go 该分支不回填 creator_name，实体 {@code creator_name} 带 omitempty →
     *       键整体缺席（勿用 buildListItem）。</li>
     * </ul>
     */
    public static java.util.Map<String, Object> buildSharedListItem(KnowledgeBase kb) {
        java.util.Map<String, Object> m = build(kb, null, false);
        if (kb.hasVectorStore()) {
            m.remove("vector_store_id");
            m.remove("vector_store_name");
            m.put("vector_store_source", "shared");
        }
        return m;
    }

    /** build() 专用：Go map 序列化嵌套全字母序 */
    private static Object treeSorted(Object pojo) {
        if (pojo == null) {
            return null;
        }
        return toSorted(MAPPER.valueToTree(pojo));
    }

    private static Object toSorted(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> m = new TreeMap<>();
            node.fields().forEachRemaining(e -> m.put(e.getKey(), toSorted(e.getValue())));
            return m;
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>(node.size());
            node.forEach(el -> list.add(toSorted(el)));
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            return node.asDouble();
        }
        return node.asText();
    }

    /** Go string 零值语义：NULL → ""（恒输出键不允许 null） */
    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
