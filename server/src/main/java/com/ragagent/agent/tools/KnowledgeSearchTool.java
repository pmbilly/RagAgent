package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.DocChunkSupport.ImageInfoView;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Chunk;

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
    private final ChunkInfoBackend chunkBackend;
    private final ImageEnricher imageEnricher;
    private final RerankerModel reranker;
    private final SearchTarget.SearchTargets searchTargets;
    private final SearchConfig config;
    /** 会话级已返回 chunk 去重（对照 seenChunks；单实例顺序使用）。 */
    private final Set<String> seenChunks = new LinkedHashSet<>();

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

        Map<String, String> kbTypeMap = getKnowledgeBaseTypes(kbIDs);

        List<ResultWithMeta> allResults = concurrentSearchByTargets(queries, searchTargetsList,
                topK, vectorThreshold, keywordThreshold, kbTypeMap);

        List<ResultWithMeta> deduplicatedBeforeRerank = deduplicateResults(allResults);

        String rerankQuery = queries.size() > 1 ? String.join(" ", queries) : queries.get(0);

        List<ResultWithMeta> filteredResults;
        if (reranker != null && !deduplicatedBeforeRerank.isEmpty() && !rerankQuery.isEmpty()) {
            filteredResults = rerankResults(rerankQuery, deduplicatedBeforeRerank);
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
            List<ResultWithMeta> mmrResults = applyMMR(filteredResults, mmrK, MMR_LAMBDA);
            if (!mmrResults.isEmpty()) {
                filteredResults = mmrResults;
            }
        }

        List<ResultWithMeta> deduplicatedResults = deduplicateResults(filteredResults);
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

        return formatOutput(deduplicatedResults, kbIDs, queries);
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
                continue; // 非检索型 KB，跳过
            } else {
                filteredTargets.add(st); // 记录取不到，保留暴露下游错误
            }
        }
        if (filteredTargets.isEmpty()) {
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
                        // 对照 Go warn 跳过
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
                        // 对照 Go warn 跳过
                    }
                }
            }
        }
        return allResults;
    }

    /** 对照 rerankResults：失败回落原序。 */
    List<ResultWithMeta> rerankResults(String query, List<ResultWithMeta> results) {
        if (results.isEmpty() || reranker == null) {
            return results;
        }
        List<RankResult> rankResults;
        try {
            rankResults = rerankScores(query, results);
        } catch (RuntimeException e) {
            return results; // 对照 Go：rerank 失败用原始结果
        }
        double threshold = rerankThreshold();
        boolean preserveTop = searchTargets != null && searchTargets.hasRecallThresholdOverride();
        return applyModelRerankScores(results, rankResults, threshold, preserveTop);
    }

    /** 对照 rerankScores。 */
    List<RankResult> rerankScores(String query, List<ResultWithMeta> results) {
        List<String> passages = new ArrayList<>(results.size());
        for (ResultWithMeta result : results) {
            passages.add(getEnrichedPassage(result.sr));
        }
        return reranker.rerank(query, passages);
    }

    /** 对照 rerankThreshold：config>0 用之，否则 0.3。 */
    double rerankThreshold() {
        return config.rerankThreshold() > 0 ? config.rerankThreshold() : 0.3;
    }

    /** 对照 filterRerankRankResults。 */
    static List<RankResult> filterRerankRankResults(List<RankResult> rankResults, double threshold,
            boolean preserveTop) {
        if (rankResults == null || rankResults.isEmpty()) {
            return null;
        }
        List<RankResult> filtered = new ArrayList<>(rankResults.size());
        for (RankResult r : rankResults) {
            if (r.relevanceScore() >= threshold) {
                filtered.add(r);
            }
        }
        if (filtered.isEmpty()) {
            RankResult top = rankResults.get(0);
            for (RankResult r : rankResults.subList(1, rankResults.size())) {
                if (r.relevanceScore() > top.relevanceScore()) {
                    top = r;
                }
            }
            if (preserveTop || top.relevanceScore() >= RERANK_FALLBACK_MIN_SCORE) {
                return List.of(top);
            }
        }
        return filtered;
    }

    /** 对照 applyModelRerankScores：composite 打分 + 按分降序（非稳定）。 */
    List<ResultWithMeta> applyModelRerankScores(List<ResultWithMeta> originals, List<RankResult> rankResults,
            double threshold, boolean preserveTop) {
        List<RankResult> filtered = filterRerankRankResults(rankResults, threshold, preserveTop);
        List<ResultWithMeta> out = new ArrayList<>();
        if (filtered != null) {
            for (RankResult rr : filtered) {
                if (rr.index() < 0 || rr.index() >= originals.size()) {
                    continue;
                }
                SearchResultView copy = originals.get(rr.index()).sr.copy();
                double baseScore = copy.score;
                double modelScore = rr.relevanceScore();
                copy.score = compositeScore(copy, modelScore, baseScore);
                out.add(new ResultWithMeta(copy, originals.get(rr.index()).sourceQuery,
                        originals.get(rr.index()).queryType, originals.get(rr.index()).knowledgeBaseType));
            }
        }
        out.sort((a, b) -> Double.compare(b.sr.score, a.sr.score));
        return out;
    }

    /** 对照 deduplicateResults：多键 + 内容签名；第二轮同 ID 保留最高分（与 grep 的留先不同）。 */
    static List<ResultWithMeta> deduplicateResults(List<ResultWithMeta> results) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> contentSig = new LinkedHashSet<>();
        List<ResultWithMeta> uniqueResults = new ArrayList<>();

        for (ResultWithMeta r : results) {
            List<String> keys = new ArrayList<>();
            keys.add(nz(r.sr.id));
            if (!nz(r.sr.parentChunkId).isEmpty()) {
                keys.add("parent:" + nz(r.sr.parentChunkId));
            }
            if (!nz(r.sr.knowledgeId).isEmpty()) {
                keys.add("kb:" + nz(r.sr.knowledgeId) + "#" + r.sr.chunkIndex);
            }

            boolean dup = false;
            for (String k : keys) {
                if (seen.contains(k)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }

            String sig = GrepChunksTool.buildContentSignature(nz(r.sr.content));
            if (!sig.isEmpty() && !contentSig.add(sig)) {
                continue;
            }

            seen.addAll(keys);
            uniqueResults.add(r);
        }

        // 同 ID 不同分：留最高分。
        Map<String, ResultWithMeta> seenByID = new LinkedHashMap<>();
        for (ResultWithMeta r : uniqueResults) {
            ResultWithMeta existing = seenByID.get(nz(r.sr.id));
            if (existing != null) {
                if (r.sr.score > existing.sr.score) {
                    seenByID.put(nz(r.sr.id), r);
                }
            } else {
                seenByID.put(nz(r.sr.id), r);
            }
        }
        return new ArrayList<>(seenByID.values());
    }

    /** 对照 compositeScore。 */
    static double compositeScore(SearchResultView result, double modelScore, double baseScore) {
        double sourceWeight = 1.0;
        if ("web_search".equalsIgnoreCase(nz(result.knowledgeSource))) {
            sourceWeight = 0.95;
        }
        double positionPrior = 1.0;
        if (result.startAt >= 0 && result.endAt > result.startAt) {
            double positionRatio = 1.0 - result.startAt / (double) (result.endAt + 1);
            positionPrior += clampFloat(positionRatio, -0.05, 0.05);
        }
        double composite = 0.6 * modelScore + 0.3 * baseScore + 0.1 * sourceWeight;
        composite *= positionPrior;
        if (composite < 0) {
            composite = 0;
        }
        if (composite > 1) {
            composite = 1;
        }
        return composite;
    }

    /** 对照 searchutil.ClampFloat。 */
    static double clampFloat(double v, double minV, double maxV) {
        if (v < minV) {
            return minV;
        }
        if (v > maxV) {
            return maxV;
        }
        return v;
    }

    /**
     * 对照 applyMMR：增量版（maxRedundancy 缓存），与朴素版逐位一致；
     * 删除用保序 remove（对照 Go append(slice[:i], slice[i+1:]...)——与 grep 的 swap-remove 不同！）。
     */
    static List<ResultWithMeta> applyMMR(List<ResultWithMeta> results, int k, double lambda) {
        if (k <= 0 || results.isEmpty()) {
            return null;
        }

        List<ResultWithMeta> selected = new ArrayList<>(k);
        List<ResultWithMeta> candidates = new ArrayList<>(results);

        List<Map<String, Boolean>> tokenSets = new ArrayList<>(candidates.size());
        for (ResultWithMeta r : candidates) {
            tokenSets.add(GrepChunksTool.tokenizeSimple(getEnrichedPassage(r.sr)));
        }

        List<Double> maxRedundancy = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            maxRedundancy.add(0.0);
        }

        while (selected.size() < k && !candidates.isEmpty()) {
            int bestIdx = 0;
            double bestScore = -1.0;

            for (int i = 0; i < candidates.size(); i++) {
                double mmr = lambda * candidates.get(i).sr.score - (1.0 - lambda) * maxRedundancy.get(i);
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }

            ResultWithMeta chosen = candidates.get(bestIdx);
            Map<String, Boolean> chosenTokens = tokenSets.get(bestIdx);
            selected.add(chosen);
            candidates.remove(bestIdx);
            tokenSets.remove(bestIdx);
            maxRedundancy.remove(bestIdx);

            for (int i = 0; i < candidates.size(); i++) {
                maxRedundancy.set(i,
                        Math.max(maxRedundancy.get(i), GrepChunksTool.jaccard(tokenSets.get(i), chosenTokens)));
            }
        }

        return selected;
    }

    /** 对照 getEnrichedPassage：拼接图片 caption/ocr 文本。 */
    static String getEnrichedPassage(SearchResultView result) {
        if (nz(result.imageInfo).isEmpty()) {
            return nz(result.content);
        }
        JsonNode arr;
        try {
            arr = RecordingSupportHolder.MAPPER.readTree(result.imageInfo);
        } catch (java.io.IOException e) {
            return nz(result.content);
        }
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return nz(result.content);
        }
        List<String> imageTexts = new ArrayList<>();
        for (JsonNode img : arr) {
            String caption = img.path("caption").asText("");
            if (!caption.isEmpty()) {
                imageTexts.add("Image Caption: " + caption);
            }
            String ocr = img.path("ocr_text").asText("");
            if (!ocr.isEmpty()) {
                imageTexts.add("Image Text: " + ocr);
            }
        }
        if (imageTexts.isEmpty()) {
            return nz(result.content);
        }
        String combined = nz(result.content);
        if (!combined.isEmpty()) {
            combined += "\n\n";
        }
        return combined + String.join("\n", imageTexts);
    }

    /** 对照 getFAQMetadata（cache 命中/未找到都缓存）。 */
    FaqChunkMetadata getFAQMetadata(String chunkID, Map<String, FaqChunkMetadata> cache) {
        if (chunkID.isEmpty() || chunkBackend == null) {
            return null;
        }
        if (cache.containsKey(chunkID)) {
            return cache.get(chunkID);
        }
        Chunk chunk;
        try {
            chunk = chunkBackend.faqChunkById(chunkID);
        } catch (RuntimeException e) {
            cache.put(chunkID, null);
            return null;
        }
        if (chunk == null) {
            cache.put(chunkID, null);
            return null;
        }
        FaqChunkMetadata meta = FaqSnippet.faqMetadata(chunk);
        cache.put(chunkID, meta);
        return meta;
    }

    /** 对照 writeKnowledgeMetadataHeader（每文档一次，仅当 custom metadata 非空）。 */
    static void writeKnowledgeMetadataHeader(StringBuilder ob, List<ResultWithMeta> results) {
        Set<String> seen = new LinkedHashSet<>();
        boolean hasMetadata = false;
        StringBuilder documents = new StringBuilder();
        for (ResultWithMeta result : results) {
            if (result == null || result.sr == null || nz(result.sr.knowledgeId).isEmpty()
                    || nz(result.sr.knowledgeCustomMetadata).isEmpty()) {
                continue;
            }
            if (!seen.add(result.sr.knowledgeId)) {
                continue;
            }
            hasMetadata = true;
            documents.append(String.format(Locale.ROOT,
                    "<document knowledge_id=\"%s\" knowledge_base_id=\"%s\" title=\"%s\">\n",
                    FaqSnippet.xmlEscape(result.sr.knowledgeId),
                    FaqSnippet.xmlEscape(result.sr.knowledgeBaseId),
                    FaqSnippet.xmlEscape(result.sr.knowledgeTitle)));
            documents.append(String.format(Locale.ROOT, "<metadata>%s</metadata>\n",
                    FaqSnippet.xmlEscape(result.sr.knowledgeCustomMetadata)));
            documents.append("</document>\n");
        }
        if (!hasMetadata) {
            return;
        }
        ob.append("<documents>\n");
        ob.append(documents);
        ob.append("</documents>\n");
    }

    /** 对照 formatOutput。 */
    ToolResult formatOutput(List<ResultWithMeta> results, List<String> kbsToSearch, List<String> queries) {
        if (results.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("knowledge_base_ids", kbsToSearch);
            data.put("results", List.of());
            data.put("count", 0);
            if (!queries.isEmpty()) {
                data.put("queries", queries);
            }
            String output = String.format(Locale.ROOT,
                    "No relevant content found in %d knowledge base(s).\n\n", kbsToSearch.size())
                    + "=== ⚠️ CRITICAL - Next Steps ===\n"
                    + "- ❌ DO NOT use training data or general knowledge to answer\n"
                    + "- ✅ If web_search is enabled: You MUST use web_search to find information\n"
                    + "- ✅ If web_search is disabled: State 'I couldn't find relevant information in the knowledge base'\n"
                    + "- NEVER fabricate or infer answers - ONLY use retrieved content\n";
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput(output);
            r.setData(data);
            return r;
        }

        Map<String, Integer> kbCounts = new LinkedHashMap<>();
        for (ResultWithMeta r : results) {
            kbCounts.merge(nz(r.sr.knowledgeBaseId), 1, Integer::sum);
        }

        StringBuilder ob = new StringBuilder();
        ob.append(String.format(Locale.ROOT, "<search_results count=\"%d\">\n", results.size()));
        for (String q : queries) {
            ob.append(String.format(Locale.ROOT, "<query>%s</query>\n", FaqSnippet.xmlEscape(q)));
        }
        writeKnowledgeMetadataHeader(ob, results);

        List<Map<String, Object>> formattedResults = new ArrayList<>(results.size());
        Map<String, FaqChunkMetadata> faqMetadataCache = new LinkedHashMap<>();
        Map<String, Set<Integer>> knowledgeChunkMap = new LinkedHashMap<>();
        Map<String, Long> knowledgeTotalMap = new LinkedHashMap<>();
        Map<String, String> knowledgeTitleMap = new LinkedHashMap<>();

        for (int i = 0; i < results.size(); i++) {
            ResultWithMeta result = results.get(i);
            FaqChunkMetadata faqMeta = null;
            if (KB_TYPE_FAQ.equals(result.knowledgeBaseType)) {
                faqMeta = getFAQMetadata(nz(result.sr.id), faqMetadataCache);
            }

            knowledgeChunkMap.computeIfAbsent(nz(result.sr.knowledgeId), k -> new LinkedHashSet<>())
                    .add(result.sr.chunkIndex);
            knowledgeTitleMap.put(nz(result.sr.knowledgeId), nz(result.sr.knowledgeTitle));

            if (!knowledgeTotalMap.containsKey(nz(result.sr.knowledgeId))) {
                long effectiveTenantID = searchTargets == null ? 0
                        : searchTargets.getTenantIdForKb(nz(result.sr.knowledgeBaseId));
                if (effectiveTenantID == 0) {
                    knowledgeTotalMap.put(nz(result.sr.knowledgeId), 0L);
                } else {
                    long total;
                    try {
                        total = chunkBackend.totalChunks(effectiveTenantID, nz(result.sr.knowledgeId));
                    } catch (RuntimeException e) {
                        total = 0;
                    }
                    knowledgeTotalMap.put(nz(result.sr.knowledgeId), total);
                }
            }

            boolean seen = !seenChunks.add(nz(result.sr.id));
            boolean isFAQ = faqMeta != null;

            String sourceQuery = nz(result.sourceQuery);
            if (seen) {
                if (isFAQ) {
                    ob.append(String.format(Locale.ROOT,
                            "<faq rank=\"%d\" faq_id=\"%s\" index=\"%d\" knowledge_base_id=\"%s\" knowledge_title=\"%s\" score=\"%.3f\" source_query=\"%s\" already_seen=\"true\">\n",
                            i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                            result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
                } else {
                    ob.append(String.format(Locale.ROOT,
                            "<chunk rank=\"%d\" chunk_id=\"%s\" chunk_index=\"%d\" knowledge_id=\"%s\" knowledge_base_id=\"%s\" knowledge_title=\"%s\" score=\"%.3f\" source_query=\"%s\" already_seen=\"true\">\n",
                            i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                            result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
                }
                ob.append("<note>(content omitted, already returned in a previous knowledge_search call this session)</note>\n");
                ob.append(isFAQ ? "</faq>\n" : "</chunk>\n");
            } else {
                if (isFAQ) {
                    ob.append(String.format(Locale.ROOT,
                            "<faq rank=\"%d\" faq_id=\"%s\" index=\"%d\" knowledge_base_id=\"%s\" knowledge_title=\"%s\" score=\"%.3f\" source_query=\"%s\">\n",
                            i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                            result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
                } else {
                    ob.append(String.format(Locale.ROOT,
                            "<chunk rank=\"%d\" chunk_id=\"%s\" chunk_index=\"%d\" knowledge_id=\"%s\" knowledge_base_id=\"%s\" knowledge_title=\"%s\" score=\"%.3f\" source_query=\"%s\">\n",
                            i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                            FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                            result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
                }
                String snippet = "";
                if (faqMeta != null) {
                    snippet = FaqSnippet.faqMatchSnippetFromQueries(faqMeta, queries);
                }
                if (snippet.isEmpty()) {
                    snippet = extractSnippetForQueries(nz(result.sr.content), queries);
                }
                if (!snippet.isEmpty()) {
                    ob.append(String.format(Locale.ROOT, "<match_snippet>%s</match_snippet>\n",
                            FaqSnippet.xmlEscape(snippet)));
                }
                // 对照 Go：content 不做 xmlEscape。
                ob.append(String.format(Locale.ROOT, "<content>%s</content>\n", nz(result.sr.content)));

                if (!nz(result.sr.imageInfo).isEmpty()) {
                    List<ImageInfoView> imageInfos = parseImageInfoList(result.sr.imageInfo);
                    for (ImageInfoView img : imageInfos) {
                        String md = DocChunkSupport.buildImageInfoMarkdownWithURL(img.url(), img);
                        if (!md.isEmpty()) {
                            ob.append(md).append('\n');
                        }
                    }
                }

                if (isFAQ) {
                    FaqSnippet.writeFaqFieldsXml(ob, faqMeta);
                    ob.append("</faq>\n");
                } else {
                    ob.append("</chunk>\n");
                }
            }

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("result_index", i + 1);
            formatted.put("content", nz(result.sr.content));
            formatted.put("knowledge_id", nz(result.sr.knowledgeId));
            formatted.put("knowledge_base_id", nz(result.sr.knowledgeBaseId));
            formatted.put("knowledge_title", nz(result.sr.knowledgeTitle));
            formatted.put("knowledge_metadata", nz(result.sr.knowledgeCustomMetadata));
            formatted.put("match_type", result.sr.matchType);
            formatted.put("source_query", sourceQuery);
            formatted.put("query_type", nz(result.queryType));
            formatted.put("knowledge_base_type", nz(result.knowledgeBaseType));
            formattedResults.add(formatted);

            Map<String, Object> last = formatted;

            if (!nz(result.sr.imageInfo).isEmpty()) {
                List<Map<String, String>> imageList = new ArrayList<>();
                for (ImageInfoView img : parseImageInfoList(result.sr.imageInfo)) {
                    Map<String, String> imgData = new LinkedHashMap<>();
                    if (!nz(img.url()).isEmpty()) {
                        imgData.put("url", img.url());
                    }
                    if (!nz(img.caption()).isEmpty()) {
                        imgData.put("caption", img.caption());
                    }
                    if (!nz(img.ocrText()).isEmpty()) {
                        imgData.put("ocr_text", img.ocrText());
                    }
                    if (!imgData.isEmpty()) {
                        imageList.add(imgData);
                    }
                }
                if (!imageList.isEmpty()) {
                    last.put("images", imageList);
                }
            }

            if (faqMeta != null) {
                last.put("faq_id", nz(result.sr.id));
                last.put("index", result.sr.chunkIndex);
                if (!nz(faqMeta.standardQuestion).isEmpty()) {
                    last.put("faq_standard_question", faqMeta.standardQuestion);
                }
                FaqSnippet.appendSimilarQuestionsToChunkData(last, faqMeta.similarQuestions);
                if (faqMeta.answers != null && !faqMeta.answers.isEmpty()) {
                    last.put("faq_answers", faqMeta.answers);
                }
            } else {
                last.put("chunk_id", nz(result.sr.id));
                last.put("chunk_index", result.sr.chunkIndex);
            }
        }

        ob.append("<retrieval_statistics>\n");
        for (Map.Entry<String, Set<Integer>> e : knowledgeChunkMap.entrySet()) {
            String knowledgeID = e.getKey();
            long totalChunks = knowledgeTotalMap.getOrDefault(knowledgeID, 0L);
            int retrievedCount = e.getValue().size();
            String title = knowledgeTitleMap.getOrDefault(knowledgeID, "");
            if (totalChunks > 0) {
                long remaining = totalChunks - retrievedCount;
                double percentage = retrievedCount / (double) totalChunks * 100;
                ob.append(String.format(Locale.ROOT,
                        "<document_stat knowledge_id=\"%s\" title=\"%s\" total_chunks=\"%d\" retrieved=\"%d\" remaining=\"%d\" coverage=\"%.1f%%\" />\n",
                        FaqSnippet.xmlEscape(knowledgeID), FaqSnippet.xmlEscape(title), totalChunks,
                        retrievedCount, remaining, percentage));
            }
        }
        ob.append("</retrieval_statistics>\n");
        ob.append("</search_results>");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("knowledge_base_ids", kbsToSearch);
        data.put("results", formattedResults);
        data.put("count", formattedResults.size());
        data.put("kb_counts", kbCounts);
        data.put("display_type", "search_results");
        if (!queries.isEmpty()) {
            data.put("queries", queries);
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(ob.toString());
        r.setData(data);
        return r;
    }

    /** 对照 knowledge_search 的 ImageInfo JSON 解析（types.ImageInfo 数组）。 */
    static List<ImageInfoView> parseImageInfoList(String imageInfoJson) {
        List<ImageInfoView> out = new ArrayList<>();
        JsonNode arr;
        try {
            arr = RecordingSupportHolder.MAPPER.readTree(imageInfoJson);
        } catch (java.io.IOException e) {
            return out;
        }
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode img : arr) {
            out.add(new ImageInfoView(img.path("url").asText(""),
                    img.path("caption").asText(""), img.path("ocr_text").asText("")));
        }
        return out;
    }

    /**
     * 对照 extractSnippetForQueries：query token 最早命中上下文（各 200 runes），
     * 无命中回落前 400 runes + " ..."；单线折叠空格，"... x ..." 包裹。
     */
    static String extractSnippetForQueries(String content, List<String> queries) {
        content = nz(content).trim();
        if (content.isEmpty()) {
            return "";
        }

        List<String> tokens = FaqSnippet.searchQueryTokens(queries);

        String lowered = content.toLowerCase(Locale.ROOT);
        int earliest = -1;
        int earliestEnd = -1;
        for (String tok : tokens) {
            int idx = lowered.indexOf(tok);
            if (idx < 0) {
                continue;
            }
            int end = idx + tok.length();
            if (earliest < 0 || idx < earliest) {
                earliest = idx;
                earliestEnd = end;
            }
        }

        if (earliest < 0) {
            if (content.codePointCount(0, content.length()) > 400) {
                return content.substring(0, content.offsetByCodePoints(0, 400)).trim() + " ...";
            }
            return content;
        }

        String matchStr = content.substring(earliest, earliestEnd);
        String before = content.substring(0, earliest);
        String after = content.substring(earliestEnd);

        String beforeRunes = GrepChunksTool.lastRunes(before, 200);
        String afterRunes = GrepChunksTool.firstRunes(after, 200);

        String snippet = beforeRunes + matchStr + afterRunes;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        return "... " + snippet.trim() + " ...";
    }

    /** 主源码不可引用测试侧 RecordingSupport，经此 holders 取 mapper（对照 Go encoding/json）。 */
    static final class RecordingSupportHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
