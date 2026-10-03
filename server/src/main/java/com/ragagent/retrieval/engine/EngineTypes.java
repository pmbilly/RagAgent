package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

/**
 * 检索引擎共享类型（IndexInfo/MatchType/RetrieveParams/IndexWithScore/RetrieveResult）。
 *
 * <p>pg 引擎（{@link PgVectorRetrieveRepository}）
 * 以"窄口 + 嵌套类型"独立落地（IndexHit/RetrieveResult 嵌在类里，契约已锁定），
 * 本类供<b>新增引擎</b>（ES/Qdrant/…）共用，不动既有 pg 件。</p>
 */
public final class EngineTypes {

    private EngineTypes() {
    }

    // ── 引擎与检索类型常量 ──────────────────────────────────────────────────

    public static final String ENGINE_ELASTICSEARCH = "elasticsearch";
    public static final String ENGINE_QDRANT = "qdrant";
    public static final String ENGINE_MILVUS = "milvus";
    public static final String ENGINE_WEAVIATE = "weaviate";
    public static final String ENGINE_DORIS = "doris";
    public static final String ENGINE_TENCENT_VECTORDB = "tencent_vectordb";
    public static final String ENGINE_OPENSEARCH = "opensearch";
    public static final String ENGINE_POSTGRES = "postgres";
    public static final String ENGINE_SQLITE = "sqlite";

    public static final String RETRIEVER_VECTOR = "vector";
    public static final String RETRIEVER_KEYWORDS = "keywords";

    /** MatchType 常量（0 起连续编号）。 */
    public static final int MATCH_EMBEDDING = 0;
    public static final int MATCH_KEYWORDS = 1;

    /** 索引名解析用的 env 键与缺省值。 */
    public static final String ENV_ELASTICSEARCH_INDEX = "ELASTICSEARCH_INDEX";
    public static final String ENV_OPENSEARCH_INDEX = "OPENSEARCH_INDEX";
    public static final String DEFAULT_INDEX = "xwrag_default";
    public static final String DEFAULT_OPENSEARCH_INDEX = "weknora";

    /**
     * 索引名解析：indexCfg.IndexName &gt; env &gt; defaultVal。
     */
    public static String resolveIndexName(String indexName, String envKey, String defaultVal) {
        if (indexName != null && !indexName.isEmpty()) {
            return indexName;
        }
        String env = envKey == null ? null : com.ragagent.retrieval.config.RetrievalEnvLookup.get(envKey);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return defaultVal;
    }

    /** 内容来源类型。 */
    public static final int SOURCE_TYPE_FILE = 0;
    public static final int SOURCE_TYPE_FAQ = 1;

    /** 待索引条目（各引擎共用的写入形状）。 */
    public static final class IndexInfo {
        public String id = "";
        public String content = "";
        public String sourceId = "";
        public int sourceType;
        public String chunkId = "";
        public String knowledgeId = "";
        public String knowledgeBaseId = "";
        public String knowledgeType = "";
        public String tagId = "";
        public boolean isEnabled;
        public boolean isRecommended;
    }

    /** 一次检索的参数。 */
    public static final class RetrieveParams {
        public String query = "";
        public float[] embedding;
        public List<String> knowledgeBaseIds = List.of();
        public List<String> knowledgeIds = List.of();
        public List<String> tagIds = List.of();
        public List<String> excludeKnowledgeIds = List.of();
        public List<String> excludeChunkIds = List.of();
        public int topK;
        public double threshold;
        public String knowledgeType = "";
        public Map<String, Object> additionalParams;
        public String retrieverType = "";
    }

    /** 带分数的检索命中。 */
    public static final class IndexWithScore {
        public String id = "";
        public String content = "";
        public String sourceId = "";
        public int sourceType;
        public String chunkId = "";
        public String knowledgeId = "";
        public String knowledgeBaseId = "";
        public String tagId = "";
        public double score;
        public int matchType;
        public boolean isEnabled;

        /** 分数读取口（供排序比较用）。 */
        public double getScore() {
            return score;
        }
    }

    /** 单引擎的一次检索结果（命中列表 + 引擎/检索类型标注）。 */
    public record RetrieveResult(List<IndexWithScore> results, String retrieverEngineType,
                                 String retrieverType) {
    }
}
