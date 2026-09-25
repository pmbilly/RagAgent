package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

/**
 * 检索引擎共享类型——对照 Go {@code internal/types/embedding.go}（IndexInfo/MatchType）与
 * {@code internal/types/retriever.go}（RetrieveParams/IndexWithScore/RetrieveResult）。
 *
 * <p>Go 里这些类型被所有引擎仓库共用；Java 侧 pg 引擎（{@link PgVectorRetrieveRepository}）
 * 当年以"窄口 + 嵌套类型"落地（IndexHit/RetrieveResult 嵌在类里，已 golden/A-B 锁定），
 * 本类供<b>新增引擎</b>（ES/Qdrant/…）共用，不动既有 pg 件。</p>
 */
public final class EngineTypes {

    private EngineTypes() {
    }

    // ── 引擎与检索类型常量（照 types/retriever.go L5-37） ──────────────────────

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

    /** 对照 types.MatchType（iota 序，embedding.go L16-17）。 */
    public static final int MATCH_EMBEDDING = 0;
    public static final int MATCH_KEYWORDS = 1;

    /** 索引名解析用的 env 键与缺省值（照各店的 {@code ResolveIndexName} 调用点）。 */
    public static final String ENV_ELASTICSEARCH_INDEX = "ELASTICSEARCH_INDEX";
    public static final String ENV_OPENSEARCH_INDEX = "OPENSEARCH_INDEX";
    public static final String DEFAULT_INDEX = "xwrag_default";
    public static final String DEFAULT_OPENSEARCH_INDEX = "weknora";

    /**
     * 对照 {@code types.ResolveIndexName}（vectorstore.go L418-426）：
     * indexCfg.IndexName &gt; env &gt; defaultVal。
     */
    public static String resolveIndexName(String indexName, String envKey, String defaultVal) {
        if (indexName != null && !indexName.isEmpty()) {
            return indexName;
        }
        String env = envKey == null ? null : System.getenv(envKey);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return defaultVal;
    }

    /** 对照 types.SourceType（embedding.go L5-14）。 */
    public static final int SOURCE_TYPE_FILE = 0;
    public static final int SOURCE_TYPE_FAQ = 1;

    /** 对照 types.IndexInfo（embedding.go L29-41）。 */
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

    /** 对照 types.RetrieveParams（retriever.go L40-65）。 */
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

    /** 对照 types.IndexWithScore（retriever.go L76-99）。 */
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

        /** 对照 {@code GetScore}（ScoreComparable）。 */
        public double getScore() {
            return score;
        }
    }

    /** 对照 types.RetrieveResult（retriever.go L107+）。 */
    public record RetrieveResult(List<IndexWithScore> results, String retrieverEngineType,
                                 String retrieverType) {
    }
}
