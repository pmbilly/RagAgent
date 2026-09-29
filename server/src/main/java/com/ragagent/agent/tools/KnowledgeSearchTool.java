package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.knowledge.domain.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * knowledge_search 工具（对照 Go {@code knowledge_search.go}，逐字移植）。
 *
 * <p>seam：{@link KnowledgeSearchBackend} 对照 interfaces.KnowledgeBaseService 被用子集
 * （GetKnowledgeBaseByID / GetKnowledgeBasesByIDsOnly / ResolveEmbeddingModelKeys /
 * GetQueryEmbedding / HybridSearch）；{@link ChunkInfoBackend} 对照 ChunkService 被用子集
 * （GetChunkByID 读 FAQ 元数据 / ListPagedChunksByKnowledgeID 取文档总块数）；
 * {@link ImageEnricher} 对照 searchutil.EnrichSearchResultsImageInfo（4.5c 接真实现，
 * null=跳过富化，对照 Go chunkService==nil 分支）；{@link RerankerModel} 对照
 * rerank.Reranker.Rerank。Go 的 goroutine 并发在 Java 顺序执行——final sort 在
 * （score, knowledgeID）唯一时完全确定，MMR/dedup 的输入序差异不可见（已知差异：
 * Go 的 allResults 追加序与 seenByID map 序随机，完全并列时 Java 结果可能不同）。</p>
 */
public class KnowledgeSearchTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    /** 键序对照 Go GenerateSchema 输出（字母序：properties < required < type）。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "knowledge_base_ids": {
                  "description": "Optional: bound knowledge-base IDs (the short bN values shown in runtime context)",
                  "items": {
                    "type": "string"
                  },
                  "maxItems": 10,
                  "minItems": 0,
                  "type": "array"
                },
                "queries": {
                  "description": "REQUIRED: 1-5 semantic questions/topics (e.g., ['What is RAG?', 'RAG benefits'])",
                  "items": {
                    "type": "string"
                  },
                  "maxItems": 5,
                  "minItems": 1,
                  "type": "array"
                }
              },
              "required": ["queries"],
              "type": "object"
            }""";

    private static final String DESCRIPTION = "Semantic/vector search tool for retrieving knowledge by meaning, intent, and conceptual relevance.\n"
            + "\n"
            + "This tool uses embeddings to understand the user's query and find semantically similar content across knowledge base chunks.\n"
            + "\n"
            + "## Purpose\n"
            + "Designed for high-level understanding tasks, such as:\n"
            + "- conceptual explanations\n"
            + "- topic overviews\n"
            + "- reasoning-based information needs\n"
            + "- contextual or intent-driven retrieval\n"
            + "- queries that cannot be answered with literal keyword matching\n"
            + "\n"
            + "The tool searches by MEANING rather than exact text. It identifies chunks that are conceptually relevant even when the wording differs.\n"
            + "\n"
            + "## What the Tool Does NOT Do\n"
            + "- Does NOT perform exact keyword matching\n"
            + "- Does NOT search for specific named entities\n"
            + "- Should NOT be used for literal lookup tasks\n"
            + "- Should NOT receive long raw text or user messages as queries\n"
            + "- Should NOT be used to locate specific strings or error codes\n"
            + "\n"
            + "For literal/keyword/entity search, another tool should be used.\n"
            + "\n"
            + "## Required Input Behavior\n"
            + "\"queries\" must contain **1–5 short, well-formed semantic questions or conceptual statements** that clearly express the meaning the model is trying to retrieve.\n"
            + "\n"
            + "Each query should represent a **concept, idea, topic, explanation, or intent**, such as:\n"
            + "- abstract topics\n"
            + "- definitions\n"
            + "- mechanisms\n"
            + "- best practices\n"
            + "- comparisons\n"
            + "- how/why questions\n"
            + "\n"
            + "Avoid:\n"
            + "- keyword lists\n"
            + "- raw text from user messages\n"
            + "- full paragraphs\n"
            + "- unprocessed input\n"
            + "\n"
            + "## Examples of valid query shapes (not content):\n"
            + "- \"What is the main idea of...\"\n"
            + "- \"How does X work in general?\"\n"
            + "- \"Explain the purpose of...\"\n"
            + "- \"What are the key principles behind...\"\n"
            + "- \"Overview of ...\"\n"
            + "\n"
            + "## Parameters\n"
            + "- queries (required): 1–5 semantic questions or conceptual statements.\n"
            + "  These should reflect the meaning or topic you want embeddings to capture.\n"
            + "- knowledge_base_ids (optional): limit the search scope.\n"
            + "\n"
            + "## Output\n"
            + "Returns chunks ranked by semantic similarity, reranked when applicable.  \n"
            + "Each chunk has a short cN source ID and belongs to a dN document ID. Results represent conceptual relevance, not literal keyword overlap. Use dN for document-level follow-up tool calls.";

    /** 对照 types.KnowledgeBaseTypeFAQ。 */
    static final String KB_TYPE_FAQ = "faq";
    /** 对照 agentRerankFallbackMinScore。 */
    static final double RERANK_FALLBACK_MIN_SCORE = 0.15;
    /** 对照 MMR lambda=0.7。 */
    static final double MMR_LAMBDA = 0.7;

    /** 对照 types.SearchParams（被用子集）。 */
    public record HybridParams(String queryText, float[] queryEmbedding, List<String> knowledgeBaseIDs,
            List<String> knowledgeIDs, List<String> tagIDs, List<String> scopeTagIDs,
            int matchCount, double vectorThreshold, double keywordThreshold) {
    }

    /** 对照 types.KnowledgeBase 被用子集（type + IsVectorEnabled/IsKeywordEnabled）。 */
    public record KBView(String id, String type, boolean vectorEnabled, boolean keywordEnabled) {
    }

    /** 对照 types.SearchResult（被用子集；score 可变——rerank 改写）。 */
    public static final class SearchResultView {
        public String id;
        public String content;
        public String knowledgeId;
        public String knowledgeBaseId;
        public String knowledgeTitle;
        public int chunkIndex;
        public String chunkType;
        public String parentChunkId;
        public String imageInfo;
        public String knowledgeCustomMetadata;
        public String knowledgeSource;
        public int startAt;
        public int endAt;
        public double score;
        public int matchType;

        public SearchResultView copy() {
            SearchResultView c = new SearchResultView();
            c.id = id;
            c.content = content;
            c.knowledgeId = knowledgeId;
            c.knowledgeBaseId = knowledgeBaseId;
            c.knowledgeTitle = knowledgeTitle;
            c.chunkIndex = chunkIndex;
            c.chunkType = chunkType;
            c.parentChunkId = parentChunkId;
            c.imageInfo = imageInfo;
            c.knowledgeCustomMetadata = knowledgeCustomMetadata;
            c.knowledgeSource = knowledgeSource;
            c.startAt = startAt;
            c.endAt = endAt;
            c.score = score;
            c.matchType = matchType;
            return c;
        }
    }

    /** 对照 rerank.RankResult 被用子集。 */
    public record RankResult(int index, double relevanceScore) {
    }

    /** 对照 rerank.Reranker.Rerank。失败抛 RuntimeException（对照 Go 返回 error → 回落原序）。 */
    public interface RerankerModel {
        List<RankResult> rerank(String query, List<String> passages);
    }

    /** 对照 interfaces.KnowledgeBaseService 被用子集。 */
    public interface KnowledgeSearchBackend {
        /** 对照 GetKnowledgeBaseByID；异常/ null 视为取不到（warn 跳过）。 */
        KBView getKnowledgeBaseById(String kbId);

        /** 对照 GetKnowledgeBasesByIDsOnly；异常返回空表。 */
        List<KBView> getKnowledgeBasesByIdsOnly(List<String> ids);

        /** 对照 ResolveEmbeddingModelKeys（kbID → model key）。 */
        Map<String, String> resolveEmbeddingModelKeys(List<KBView> kbs);

        /** 对照 GetQueryEmbedding；异常返回 null（Go 侧仅 warn，queryEmbedding 为 nil）。 */
        float[] getQueryEmbedding(String kbId, String queryText);

        /**
         * 对照 {@code HybridSearch(ctx, kbID, params)}；异常对照 Go err 分支（warn 跳过该路）。
         *
         * <p>kbID 与 {@code params.knowledgeBaseIDs} 必须分开传（Go 同签名：单 id 用于
         * 主库/embedding 解析，列表用于跨库范围）——2026-09-23 接线时修正：此前 seam 只传
         * params，定向（knowledge/tag）分支的 target KB id 会丢，适配器无从路由。</p>
         */
        List<SearchResultView> hybridSearch(String kbId, HybridParams params);
    }

    /** 对照 ChunkService 被用子集。 */
    public interface ChunkInfoBackend {
        /** 对照 GetChunkByID（FAQ 元数据路径）。null=未找到。 */
        Chunk faqChunkById(String chunkId);

        /** 对照 ListPagedChunksByKnowledgeID(text+faq, enabled) 的 total。 */
        long totalChunks(long tenantId, String knowledgeId);
    }

    /** 对照 searchutil.EnrichSearchResultsImageInfo。null=跳过（对照 Go chunkService==nil）。 */
    public interface ImageEnricher {
        void enrich(long tenantId, List<SearchResultView> results);
    }

    /** 检索配置（对照 config.Conversation 被用子集；0/null 回落硬编码默认）。 */
    public record SearchConfig(int embeddingTopK, double vectorThreshold, double keywordThreshold,
            double rerankThreshold) {
        public static SearchConfig defaults() {
            return new SearchConfig(0, 0, 0, 0);
        }
    }

    /** 对照 searchResultWithMeta。 */
    static final class ResultWithMeta {
        final SearchResultView sr;
        final String sourceQuery;
        final String queryType;
        final String knowledgeBaseType;

        ResultWithMeta(SearchResultView sr, String sourceQuery, String queryType, String knowledgeBaseType) {
            this.sr = sr;
            this.sourceQuery = sourceQuery;
            this.queryType = queryType;
            this.knowledgeBaseType = knowledgeBaseType;
        }
    }

    private final KnowledgeSearchBackend backend;
    final ChunkInfoBackend chunkBackend;
    private final ImageEnricher imageEnricher;
    final RerankerModel reranker;
    final SearchTarget.SearchTargets searchTargets;
    final SearchConfig config;

    /** 排序与输出两个包内协作者（构造期装配）。 */
    private final KnowledgeSearchRanking ranking;
    private final KnowledgeSearchOutputFormatter formatter;
    /** 会话级已返回 chunk 去重（对照 seenChunks；单实例顺序使用）。 */
    final Set<String> seenChunks = new LinkedHashSet<>();

    public KnowledgeSearchTool(KnowledgeSearchBackend backend, ChunkInfoBackend chunkBackend,
            ImageEnricher imageEnricher, RerankerModel reranker,
            SearchTarget.SearchTargets searchTargets, SearchConfig config) {
        super(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, DESCRIPTION, SCHEMA_JSON);
        this.backend = backend;
        this.chunkBackend = chunkBackend;
        this.imageEnricher = imageEnricher;
        this.reranker = reranker;
        this.searchTargets = searchTargets;
        this.config = config == null ? SearchConfig.defaults() : config;
        this.ranking = new KnowledgeSearchRanking(this);
        this.formatter = new KnowledgeSearchOutputFormatter(this);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        List<String> userSpecifiedKBs = new ArrayList<>();
        JsonNode kbNode = args.get("knowledge_base_ids");
        if (kbNode != null && kbNode.isArray()) {
            for (JsonNode item : kbNode) {
                if (item.isTextual()) {
                    userSpecifiedKBs.add(item.asText());
                }
            }
        }
        if (!userSpecifiedKBs.isEmpty()) {
            try {
                SearchAuth.validateKnowledgeBaseIdsInSearchTargets(
                        searchTargets == null ? new SearchTarget.SearchTargets(null) : searchTargets,
                        userSpecifiedKBs);
            } catch (RuntimeException e) {
                return failure(e.getMessage());
            }
        }

        // 按用户指定 KB 过滤 search targets（对照 Go 的 filteredTargets）。
        List<SearchTarget> searchTargetsList = searchTargets == null ? List.of() : searchTargets.list();
        if (!userSpecifiedKBs.isEmpty()) {
            Set<String> userKBSet = new LinkedHashSet<>(userSpecifiedKBs);
            List<SearchTarget> filtered = new ArrayList<>();
            for (SearchTarget target : searchTargetsList) {
                if (target == null) {
                    continue;
                }
                if (userKBSet.contains(target.knowledgeBaseId())) {
                    filtered.add(target);
                }
            }
            searchTargetsList = filtered;
        }
        if (searchTargetsList.isEmpty()) {
            return failure("no knowledge bases specified and no search targets configured");
        }

        List<String> kbIDs = new ArrayList<>();
        Set<String> seenKB = new LinkedHashSet<>();
        for (SearchTarget t : searchTargetsList) {
            if (t != null && t.knowledgeBaseId() != null && !t.knowledgeBaseId().isEmpty()
                    && seenKB.add(t.knowledgeBaseId())) {
                kbIDs.add(t.knowledgeBaseId());
            }
        }

        List<String> queries = new ArrayList<>();
        JsonNode qNode = args.get("queries");
        if (qNode != null && qNode.isArray()) {
            for (JsonNode item : qNode) {
                if (item.isTextual()) {
                    queries.add(item.asText());
                }
            }
        }
        if (queries.isEmpty()) {
            return failure("queries parameter is required");
        }

        // 参数回落：config → 硬编码默认（对照 Go 的 ==0 判断）。
        int topK = config.embeddingTopK() == 0 ? 5 : config.embeddingTopK();
        double vectorThreshold = config.vectorThreshold() == 0 ? 0.6 : config.vectorThreshold();
        double keywordThreshold = config.keywordThreshold() == 0 ? 0.5 : config.keywordThreshold();

        log.info("[Tool][KnowledgeSearch] Using {} search targets across {} KBs",
                searchTargetsList.size(), kbIDs.size());
        log.info("[Tool][KnowledgeSearch] Starting concurrent search with {} search targets",
                searchTargetsList.size());
        Map<String, String> kbTypeMap = getKnowledgeBaseTypes(kbIDs);

        List<ResultWithMeta> allResults = concurrentSearchByTargets(queries, searchTargetsList,
                topK, vectorThreshold, keywordThreshold, kbTypeMap);

        List<ResultWithMeta> deduplicatedBeforeRerank = KnowledgeSearchRanking.deduplicateResults(allResults);

        String rerankQuery = queries.size() > 1 ? String.join(" ", queries) : queries.get(0);

        List<ResultWithMeta> filteredResults;
        if (reranker != null && !deduplicatedBeforeRerank.isEmpty() && !rerankQuery.isEmpty()) {
            filteredResults = ranking.rerankResults(rerankQuery, deduplicatedBeforeRerank);
        } else {
            filteredResults = deduplicatedBeforeRerank;
        }

        if (!filteredResults.isEmpty()) {
            int mmrK = filteredResults.size();
            if (topK > 0 && mmrK > topK) {
                mmrK = topK;
            }
            if (mmrK < 1) {
                mmrK = 1;
            }
            List<ResultWithMeta> mmrResults = KnowledgeSearchRanking.applyMMR(filteredResults, mmrK, MMR_LAMBDA);
            if (!mmrResults.isEmpty()) {
                filteredResults = mmrResults;
            }
        }

        List<ResultWithMeta> deduplicatedResults = KnowledgeSearchRanking.deduplicateResults(filteredResults);
        deduplicatedResults.sort((a, b) -> {
            if (a.sr.score != b.sr.score) {
                return Double.compare(b.sr.score, a.sr.score);
            }
            return nz(a.sr.knowledgeId).compareTo(nz(b.sr.knowledgeId));
        });

        // 图片富化（对照 EnrichSearchResultsImageInfo；null enricher 跳过）。
        if (imageEnricher != null && !deduplicatedResults.isEmpty()) {
            Map<Long, List<SearchResultView>> byTenant = new LinkedHashMap<>();
            for (ResultWithMeta r : deduplicatedResults) {
                long tid = searchTargets == null ? 0 : searchTargets.getTenantIdForKb(nz(r.sr.knowledgeBaseId));
                if (tid == 0) {
                    continue;
                }
                byTenant.computeIfAbsent(tid, k -> new ArrayList<>()).add(r.sr);
            }
            for (Map.Entry<Long, List<SearchResultView>> e : byTenant.entrySet()) {
                imageEnricher.enrich(e.getKey(), e.getValue());
            }
        }

        return formatter.formatOutput(deduplicatedResults, kbIDs, queries);
    }

    private ToolResult failure(String message) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(message);
        return r;
    }

    static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 对照 getKnowledgeBaseTypes。 */
    Map<String, String> getKnowledgeBaseTypes(List<String> kbIDs) {
        Map<String, String> kbTypeMap = new LinkedHashMap<>();
        for (String kbID : kbIDs) {
            if (kbID.isEmpty() || kbTypeMap.containsKey(kbID)) {
                continue;
            }
            KBView kb;
            try {
                kb = backend.getKnowledgeBaseById(kbID);
            } catch (RuntimeException e) {
                continue; // 对照 Go warn 跳过
            }
            if (kb == null) {
                continue;
            }
            kbTypeMap.put(kbID, nz(kb.type()));
        }
        return kbTypeMap;
    }

    /** 对照 concurrentSearchByTargets（Go 并发 → Java 顺序；输出序差异被 final sort 吸收）。 */
    List<ResultWithMeta> concurrentSearchByTargets(List<String> queries, List<SearchTarget> searchTargets,
            int topK, double vectorThreshold, double keywordThreshold, Map<String, String> kbTypeMap) {
        List<String> kbIDs = new ArrayList<>();
        Set<String> seenKB = new LinkedHashSet<>();
        for (SearchTarget t : searchTargets) {
            if (t != null && t.knowledgeBaseId() != null && !t.knowledgeBaseId().isEmpty()
                    && seenKB.add(t.knowledgeBaseId())) {
                kbIDs.add(t.knowledgeBaseId());
            }
        }

        List<KBView> kbList;
        try {
            kbList = backend.getKnowledgeBasesByIdsOnly(kbIDs);
        } catch (RuntimeException e) {
            kbList = List.of();
        }
        if (kbList == null) {
            kbList = List.of();
        }

        // 过滤不可检索 KB（wiki-only/graph-only）；取不到记录的 KB 保留以暴露真实错误。
        Set<String> searchableKBs = new LinkedHashSet<>();
        Set<String> knownKBs = new LinkedHashSet<>();
        for (KBView kb : kbList) {
            if (kb == null || kb.id() == null) {
                continue;
            }
            knownKBs.add(kb.id());
            if (kb.vectorEnabled() || kb.keywordEnabled()) {
                searchableKBs.add(kb.id());
            }
        }
        List<SearchTarget> filteredTargets = new ArrayList<>();
        for (SearchTarget st : searchTargets) {
            if (st == null || nz(st.knowledgeBaseId()).isEmpty()) {
                continue;
            }
            if (searchableKBs.contains(st.knowledgeBaseId())) {
                filteredTargets.add(st);
            } else if (knownKBs.contains(st.knowledgeBaseId())) {
                log.info("[Tool][KnowledgeSearch] Skipping non-searchable KB {} "
                        + "(no vector/keyword index, likely wiki/graph-only)", st.knowledgeBaseId());
                continue; // 非检索型 KB，跳过
            } else {
                filteredTargets.add(st); // 记录取不到，保留暴露下游错误
            }
        }
        if (filteredTargets.isEmpty()) {
            log.info("[Tool][KnowledgeSearch] No searchable KBs in scope "
                    + "(all wiki/graph-only); skipping retrieval");
            return List.of();
        }
        searchTargets = filteredTargets;

        Map<String, String> modelKeyMap;
        try {
            modelKeyMap = backend.resolveEmbeddingModelKeys(kbList);
        } catch (RuntimeException e) {
            modelKeyMap = Map.of();
        }
        if (modelKeyMap == null) {
            modelKeyMap = Map.of();
        }

        // 按 embedding model key 分组（LinkedHashMap 保序，Go map 序随机——final sort 吸收）。
        Map<String, List<SearchTarget>> groups = new LinkedHashMap<>();
        for (SearchTarget st : searchTargets) {
            if (st == null || nz(st.knowledgeBaseId()).isEmpty()) {
                continue;
            }
            String key = nz(modelKeyMap.get(st.knowledgeBaseId()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(st);
        }

        List<ResultWithMeta> allResults = new ArrayList<>();

        for (String q : queries) {
            for (Map.Entry<String, List<SearchTarget>> group : groups.entrySet()) {
                String modelKey = group.getKey();
                List<SearchTarget> targets = group.getValue();

                float[] queryEmbedding = null;
                if (!modelKey.isEmpty()) {
                    try {
                        queryEmbedding = backend.getQueryEmbedding(targets.get(0).knowledgeBaseId(), q);
                    } catch (RuntimeException e) {
                        log.warn("[Tool][KnowledgeSearch] Failed to pre-compute embedding for model {}: {}",
                                modelKey, e.toString());
                        queryEmbedding = null; // 对照 Go warn 后 nil
                    }
                }

                List<String> fullKBIDs = new ArrayList<>();
                List<SearchTarget> knowledgeTargets = new ArrayList<>();
                for (SearchTarget st : targets) {
                    if (SearchTarget.TYPE_KNOWLEDGE_BASE.equals(st.type())
                            && (st.tagIds() == null || st.tagIds().isEmpty())) {
                        fullKBIDs.add(st.knowledgeBaseId());
                    } else {
                        knowledgeTargets.add(st);
                    }
                }

                if (!fullKBIDs.isEmpty()) {
                    try {
                        // 对照 Go L543：kbID = fullKBIDs[0]，范围在 params.KnowledgeBaseIDs 里
                        List<SearchResultView> kbResults = backend.hybridSearch(fullKBIDs.get(0),
                                new HybridParams(
                                        q, queryEmbedding, fullKBIDs, null, null, null,
                                        topK, vectorThreshold, keywordThreshold));
                        if (kbResults != null) {
                            for (SearchResultView r : kbResults) {
                                allResults.add(new ResultWithMeta(r, q, "hybrid",
                                        nz(kbTypeMap.get(nz(r.knowledgeBaseId)))));
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("[Tool][KnowledgeSearch] Combined search failed for KBs {}: {}",
                                fullKBIDs, e.toString());
                    }
                }

                for (SearchTarget st : knowledgeTargets) {
                    double[] thresholds = st.recallThresholds(vectorThreshold, keywordThreshold);
                    try {
                        // 对照 Go L582：kbID = st.KnowledgeBaseID（此前 seam 丢了该 id）
                        List<SearchResultView> kbResults = backend.hybridSearch(st.knowledgeBaseId(),
                                new HybridParams(
                                        q, queryEmbedding, null, st.knowledgeIds(), st.tagIds(),
                                        st.scopeTagIds(), topK, thresholds[0], thresholds[1]));
                        if (kbResults != null) {
                            for (SearchResultView r : kbResults) {
                                allResults.add(new ResultWithMeta(r, q, "hybrid",
                                        nz(kbTypeMap.get(nz(r.knowledgeBaseId)))));
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("[Tool][KnowledgeSearch] Failed to search KB {}: {}",
                                st.knowledgeBaseId(), e.toString());
                    }
                }
            }
        }
        return allResults;
    }

    /** 对照 rerankResults：失败回落原序。 */
    static final class RecordingSupportHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
