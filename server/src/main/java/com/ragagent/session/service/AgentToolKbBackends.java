package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ragagent.agent.tools.DocChunkSupport;
import com.ragagent.agent.tools.GrepChunksTool;
import com.ragagent.agent.tools.KnowledgeSearchTool;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.settings.ConversationProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.support.ImageInfoEnricher;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;

/**
 * {@code AgentToolBackends} 的**知识库检索簇**（§14 步骤 2）：知识库检索 / chunk 列举 /
 * grep / 知识图谱 / 图片富化 / rerank 模型适配等工具后端的装配与实现。
 *
 * <p>为什么单独一类：这一簇自成一条读链（KB 解析 → 混合检索 → chunk 视图 / 图谱搜索 →
 * 工具端口实现），与 wiki / web / datasource / SQL 各簇无交集；门面保留同名薄委托
 * （装配点 {@code createTool} 与契约测试直调面）。共享项按类名引用：
 * {@code AgentToolBackends.readJson}/{@code JSON}（wiki 簇也在用），故门面放宽为包内可见。</p>
 */
final class AgentToolKbBackends {

    /** 对照 ListPagedChunksByKnowledgeID 的 text+faq 类型过滤。 */
    private static final List<String> TEXT_FAQ_TYPES = List.of(
            com.ragagent.common.pipeline.ChunkTypes.TEXT, com.ragagent.common.pipeline.ChunkTypes.FAQ);

    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final ChunkRepository chunkRepository;
    private final HybridSearchService hybridSearchService;
    /** 检索阈值配置（与门面共用同一份租户配置对象）。 */
    private final ConversationProperties conversation;
    /** 门面的 JdbcTemplate（grep/图谱/回填都走它）。 */
    private final JdbcTemplate jdbc;

    AgentToolKbBackends(KnowledgeBaseService kbService,
                        KnowledgeService knowledgeService,
                        ChunkRepository chunkRepository,
                        HybridSearchService hybridSearchService,
                        ConversationProperties conversation,
                        JdbcTemplate jdbc) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.chunkRepository = chunkRepository;
        this.hybridSearchService = hybridSearchService;
        this.conversation = conversation;
        this.jdbc = jdbc;
    }

    /** 对照 NewKnowledgeSearchTool 的 cfg 参数（Conversation 检索段）。 */
    public KnowledgeSearchTool.SearchConfig searchConfig() {
        return new KnowledgeSearchTool.SearchConfig(
                conversation.getEmbeddingTopK(), conversation.getVectorThreshold(),
                conversation.getKeywordThreshold(), conversation.getRerankThreshold());
    }
    public KnowledgeSearchTool.KnowledgeSearchBackend knowledgeSearchBackend() {
        return new KnowledgeSearchTool.KnowledgeSearchBackend() {
            @Override
            public KnowledgeSearchTool.KBView getKnowledgeBaseById(String kbId) {
                KnowledgeBase kb = kbService.getAllTenantById(kbId);
                if (kb == null) {
                    throw new RuntimeException("knowledge base not found");
                }
                return toKbView(kb);
            }

            @Override
            public List<KnowledgeSearchTool.KBView> getKnowledgeBasesByIdsOnly(List<String> ids) {
                List<KnowledgeSearchTool.KBView> out = new ArrayList<>();
                if (ids == null) {
                    return out;
                }
                for (String id : ids) {
                    try {
                        KnowledgeBase kb = kbService.getAllTenantById(id);
                        if (kb != null) {
                            out.add(toKbView(kb));
                        }
                    } catch (RuntimeException ignored) {
                        // 对照 Go GetKnowledgeBasesByIDsOnly：异常返回空表
                    }
                }
                return out;
            }

            @Override
            public Map<String, String> resolveEmbeddingModelKeys(
                    List<KnowledgeSearchTool.KBView> kbs) {
                List<String> kbIds = new ArrayList<>();
                if (kbs != null) {
                    for (KnowledgeSearchTool.KBView v : kbs) {
                        kbIds.add(v.id());
                    }
                }
                return hybridSearchService.resolveEmbeddingModelKeys(kbIds);
            }

            @Override
            public float[] getQueryEmbedding(String kbId, String queryText) {
                return hybridSearchService.getQueryEmbedding(kbId, queryText);
            }

            @Override
            public List<KnowledgeSearchTool.SearchResultView> hybridSearch(
                    String kbId, KnowledgeSearchTool.HybridParams params) {
                SearchParams sp = new SearchParams();
                sp.setQueryText(params.queryText());
                sp.setQueryEmbedding(params.queryEmbedding());
                sp.setMatchCount(params.matchCount());
                sp.setVectorThreshold(params.vectorThreshold());
                sp.setKeywordThreshold(params.keywordThreshold());
                sp.setKnowledgeIds(params.knowledgeIDs());
                sp.setTagIds(params.tagIDs());
                sp.setScopeTagIds(params.scopeTagIDs());
                sp.setKnowledgeBaseIds(params.knowledgeBaseIDs());
                List<SearchResult> rows = hybridSearchService.hybridSearch(kbId, sp);
                List<KnowledgeSearchTool.SearchResultView> out = new ArrayList<>();
                if (rows != null) {
                    for (SearchResult r : rows) {
                        out.add(toSearchResultView(r));
                    }
                }
                return out;
            }
        };
    }
    public KnowledgeSearchTool.ChunkInfoBackend chunkInfoBackend() {
        return new KnowledgeSearchTool.ChunkInfoBackend() {
            @Override
            public com.ragagent.knowledge.domain.Chunk faqChunkById(String chunkId) {
                return chunkRepository.getChunkByIdOnly(chunkId);
            }

            @Override
            public long totalChunks(long tenantId, String knowledgeId) {
                // 对照 ListPagedChunksByKnowledgeID(text+faq, enabled) 的 Count 段
                return pagedChunkCount(tenantId, knowledgeId);
            }
        };
    }
    /** 对照 searchutil.EnrichSearchResultsImageInfo（工具侧 enrich 回调）。 */
    public KnowledgeSearchTool.ImageEnricher imageEnricher() {
        return (tenantId, results) -> {
            if (results == null || results.isEmpty()) {
                return;
            }
            List<String> chunkIDs = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (KnowledgeSearchTool.SearchResultView r : results) {
                if (r.imageInfo != null && !r.imageInfo.isEmpty()) {
                    continue;
                }
                if (!seen.containsKey(r.id)) {
                    seen.put(r.id, Boolean.TRUE);
                    chunkIDs.add(r.id);
                }
            }
            if (chunkIDs.isEmpty()) {
                return;
            }
            Map<String, String> infoMap = ImageInfoEnricher.collectImageInfoByChunkIds(
                    chunkRepository::listChunksByParentIDs, tenantId, chunkIDs);
            if (infoMap == null || infoMap.isEmpty()) {
                return;
            }
            for (KnowledgeSearchTool.SearchResultView r : results) {
                if (r.imageInfo != null && !r.imageInfo.isEmpty()) {
                    continue;
                }
                String merged = infoMap.get(r.id);
                if (merged != null) {
                    r.imageInfo = merged;
                }
            }
        };
    }
    /** 对照 rerank.Reranker.Rerank → tool 的 RankResult（失败上抛，工具侧回落原序）。 */
    public static KnowledgeSearchTool.RerankerModel rerankerModel(Reranker reranker) {
        if (reranker == null) {
            return null;
        }
        return (query, passages) -> {
            List<RankResult> ranked = reranker.rerank(query, passages);
            List<KnowledgeSearchTool.RankResult> out = new ArrayList<>();
            if (ranked != null) {
                for (RankResult r : ranked) {
                    out.add(new KnowledgeSearchTool.RankResult(r.getIndex(), r.getRelevanceScore()));
                }
            }
            return out;
        };
    }
    public GrepChunksTool.GrepChunkSearch grepChunkSearch() {
        return (queries, fullKbIDs, knowledgeIDs, tagTargets, kbTenantMap) -> {
            if ((fullKbIDs == null || fullKbIDs.isEmpty())
                    && (knowledgeIDs == null || knowledgeIDs.isEmpty())
                    && (tagTargets == null || tagTargets.isEmpty())) {
                return List.of();
            }
            List<Object> args = new ArrayList<>();
            String scope = grepScopeClause(fullKbIDs, knowledgeIDs, tagTargets, kbTenantMap, args);
            if (scope.isEmpty()) {
                return List.of();
            }
            RegexDialect dialect = regexDialect();
            List<String> regexParts = new ArrayList<>();
            for (String q : queries) {
                regexParts.add("(" + dialect.condition("chunks.content") + " OR "
                        + dialect.condition("knowledges.title") + ")");
                args.add(q);
                args.add(q);
            }
            String sql = "SELECT chunks.id, chunks.content, chunks.chunk_index, chunks.knowledge_id, "
                    + "chunks.knowledge_base_id, chunks.chunk_type, chunks.metadata, "
                    + "knowledges.title AS knowledge_title "
                    + "FROM chunks JOIN knowledges ON chunks.knowledge_id = knowledges.id "
                    + "WHERE chunks.is_enabled = TRUE AND chunks.deleted_at IS NULL "
                    + "AND knowledges.deleted_at IS NULL "
                    + "AND (" + scope + ") AND (" + String.join(" OR ", regexParts) + ") "
                    + "ORDER BY chunks.created_at DESC LIMIT 500";

            List<GrepChunksTool.GrepChunkView> results = jdbc.query(sql, (rs, i) -> {
                GrepChunksTool.GrepChunkView v = new GrepChunksTool.GrepChunkView();
                v.id = rs.getString("id");
                v.content = rs.getString("content");
                v.chunkIndex = rs.getInt("chunk_index");
                v.knowledgeId = rs.getString("knowledge_id");
                v.knowledgeBaseId = rs.getString("knowledge_base_id");
                v.chunkType = rs.getString("chunk_type");
                String meta = rs.getString("metadata");
                v.metadata = AgentToolBackends.readJson(meta);
                v.knowledgeTitle = rs.getString("knowledge_title");
                return v;
            }, args.toArray());

            if (!results.isEmpty()) {
                backfillTotalChunkCounts(results);
            }
            return results;
        };
    }
    /** 对照 grep_chunks.go 的 scopeClause（OR 组合：knowledge_id IN / 标签 EXISTS / kb+tenant 对）。 */
    private String grepScopeClause(List<String> kbIDs, List<String> knowledgeIDs,
                                   List<SearchTarget> tagTargets, Map<String, Long> kbTenantMap,
                                   List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (knowledgeIDs != null && !knowledgeIDs.isEmpty()) {
            clauses.add("chunks.knowledge_id IN (" + placeholders(knowledgeIDs.size()) + ")");
            args.addAll(knowledgeIDs);
        }
        if (tagTargets != null) {
            for (SearchTarget target : tagTargets) {
                if (target == null || target.knowledgeBaseId() == null
                        || target.knowledgeBaseId().isEmpty()
                        || target.tagIds() == null || target.tagIds().isEmpty()) {
                    continue;
                }
                long tenantID = target.tenantId();
                if (tenantID == 0) {
                    Long mapped = kbTenantMap == null ? null : kbTenantMap.get(target.knowledgeBaseId());
                    tenantID = mapped == null ? 0 : mapped;
                }
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ? AND EXISTS ("
                        + "SELECT 1 FROM knowledge_tag_relations ktr "
                        + "WHERE ktr.knowledge_id = chunks.knowledge_id AND ktr.tag_id IN ("
                        + placeholders(target.tagIds().size()) + ")))");
                args.add(target.knowledgeBaseId());
                args.add(tenantID);
                args.addAll(target.tagIds());
            }
        }
        if (kbIDs != null) {
            for (String kbID : kbIDs) {
                Long mapped = kbTenantMap == null ? null : kbTenantMap.get(kbID);
                long tenantID = mapped == null ? 0 : mapped;
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ?)");
                args.add(kbID);
                args.add(tenantID);
            }
        }
        if (clauses.isEmpty()) {
            return "";
        }
        return String.join(" OR ", clauses);
    }
    /** 对照 count 回填：knowledge_id → 该 knowledge 的 enabled chunk 数。 */
    private void backfillTotalChunkCounts(List<GrepChunksTool.GrepChunkView> results) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (GrepChunksTool.GrepChunkView r : results) {
            if (r.knowledgeId != null && !r.knowledgeId.isEmpty()) {
                seen.put(r.knowledgeId, Boolean.TRUE);
            }
        }
        if (seen.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(seen.keySet());
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            jdbc.query("SELECT knowledge_id, COUNT(*) AS cnt FROM chunks "
                            + "WHERE knowledge_id IN (" + placeholders(ids.size()) + ") "
                            + "AND is_enabled = TRUE AND deleted_at IS NULL "
                            + "GROUP BY knowledge_id",
                    rs -> {
                        counts.put(rs.getString("knowledge_id"), rs.getInt("cnt"));
                    }, ids.toArray());
            for (GrepChunksTool.GrepChunkView r : results) {
                Integer c = counts.get(r.knowledgeId);
                r.totalChunkCount = c == null ? 0 : c;
            }
        } catch (RuntimeException ignored) {
            // 对照 Go：count 失败只 warn，跳过回填
        }
    }
    /**
     * 对照 regexOperatorForDialect：PostgreSQL {@code ~*}，其余 {@code REGEXP}；
     * H2（仅测试内存库）用 {@code REGEXP_LIKE(...,'i')} 等价表达大小写不敏感。
     */
    private RegexDialect regexDialect() {
        try (java.sql.Connection conn = jdbc.getDataSource().getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            String lower = name == null ? "" : name.toLowerCase();
            if (lower.contains("postgres")) {
                return RegexDialect.PG_OPERATOR;
            }
            if (lower.contains("h2")) {
                return RegexDialect.H2_FUNCTION;
            }
            return RegexDialect.GENERIC_OPERATOR;
        } catch (java.sql.SQLException e) {
            return RegexDialect.GENERIC_OPERATOR;
        }
    }
    /** 正则条件的三方言表达（含/不含列名的完整布尔片段）。 */
    private enum RegexDialect {
        PG_OPERATOR {
            @Override
            String condition(String column) {
                return column + " ~* ?";
            }
        },
        GENERIC_OPERATOR {
            @Override
            String condition(String column) {
                return column + " REGEXP ?";
            }
        },
        H2_FUNCTION {
            @Override
            String condition(String column) {
                return "REGEXP_LIKE(" + column + ", ?, 'i')";
            }
        };

        abstract String condition(String column);
    }
    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }
    /** Knowledge.metadata（jsonb JsonNode）→ 工具视图的 Map 形态；非对象/null → null。 */
    private static Map<String, Object> metadataMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            return AgentToolBackends.JSON.convertValue(node,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return null;
        }
    }
    public DocChunkSupport.KnowledgeInfoReader knowledgeInfoReader() {
        return new DocChunkSupport.KnowledgeInfoReader() {
            @Override
            public DocChunkSupport.KnowledgeInfoView byIdOnly(String knowledgeId) {
                Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
                if (k == null) {
                    return null;
                }
                return new DocChunkSupport.KnowledgeInfoView(
                        k.getId(), k.getTenantId() == null ? 0L : k.getTenantId(),
                        AgentToolBackends.nz(k.getKnowledgeBaseId()), AgentToolBackends.nz(k.getTitle()), AgentToolBackends.nz(k.getDescription()),
                        AgentToolBackends.nz(k.getType()), AgentToolBackends.nz(k.getSource()), AgentToolBackends.nz(k.getFileName()), AgentToolBackends.nz(k.getFileType()),
                        k.getFileSize() == null ? 0L : k.getFileSize(), AgentToolBackends.nz(k.getParseStatus()),
                        metadataMap(k.getMetadata()));
            }

            @Override
            public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
                Map<String, List<SearchAuth.TagView>> out = new LinkedHashMap<>();
                if (knowledgeIds == null || knowledgeIds.isEmpty()) {
                    return out;
                }
                // 对照 GetKnowledgeTags：knowledge_tag_relations 按 knowledge 聚合
                jdbc.query("SELECT knowledge_id, tag_id FROM knowledge_tag_relations "
                                + "WHERE knowledge_id IN (" + placeholders(knowledgeIds.size()) + ") "
                                + "ORDER BY knowledge_id, tag_id",
                        rs -> {
                            String kid = rs.getString("knowledge_id");
                            out.computeIfAbsent(kid, x -> new ArrayList<>())
                                    .add(new SearchAuth.TagView(rs.getString("tag_id")));
                        }, knowledgeIds.toArray());
                return out;
            }
        };
    }
    /** 对照 chunkService.GetChunkByID（工具侧的 chunkById 回调）。 */
    public java.util.function.Function<String, com.ragagent.knowledge.domain.Chunk> chunkById() {
        return chunkRepository::getChunkByIdOnly;
    }
    public DocChunkSupport.PagedChunks pagedChunks() {
        return (tenantId, knowledgeId, page, pageSize) -> {
            int offset = Math.max(page - 1, 0) * Math.max(pageSize, 0);
            ChunkRepository.ChunkPage page1 = chunkRepository.listPagedChunksByKnowledgeId(
                    tenantId, knowledgeId, offset, pageSize,
                    TEXT_FAQ_TYPES, null, "", "", "", "", Boolean.TRUE);
            return new DocChunkSupport.ChunkPage(page1.items(), page1.total());
        };
    }
    /** 对照 ListPagedChunksByKnowledgeID 的 text+faq + enabled 过滤（count 段）。 */
    private long pagedChunkCount(long tenantId, String knowledgeId) {
        return chunkRepository.listPagedChunksByKnowledgeId(
                tenantId, knowledgeId, 0, 1, TEXT_FAQ_TYPES, null, "", "", "", "",
                Boolean.TRUE).total();
    }
    public DocChunkSupport.ImageInfoCollector imageInfoCollector() {
        return (tenantId, chunkIds) -> ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepository::listChunksByParentIDs, tenantId, chunkIds);
    }
    public QueryKnowledgeGraphTool.GraphSearch graphSearch() {
        return new QueryKnowledgeGraphTool.GraphSearch() {
            @Override
            public QueryKnowledgeGraphTool.KnowledgeBaseView getKnowledgeBaseByIdOnly(String kbId) {
                KnowledgeBase kb = kbService.getAllTenantById(kbId);
                if (kb == null) {
                    return null;
                }
                return new QueryKnowledgeGraphTool.KnowledgeBaseView(kb.getId(),
                        extractConfigView(kb.getExtractConfig()));
            }

            @Override
            public List<QueryKnowledgeGraphTool.SearchResultView> hybridSearch(
                    String kbId, String queryText, int matchCount) {
                SearchParams sp = new SearchParams();
                sp.setQueryText(queryText);
                sp.setMatchCount(matchCount);
                List<SearchResult> rows = hybridSearchService.hybridSearch(kbId, sp);
                List<QueryKnowledgeGraphTool.SearchResultView> out = new ArrayList<>();
                if (rows != null) {
                    for (SearchResult r : rows) {
                        out.add(new QueryKnowledgeGraphTool.SearchResultView(r.getId(),
                                r.getScore(), r.getContent(), r.getKnowledgeId(),
                                r.getKnowledgeBaseId(), r.getKnowledgeTitle(), r.getChunkIndex(),
                                r.getChunkType(), r.getMatchType()));
                    }
                }
                return out;
            }
        };
    }
    /** 对照 ExtractConfig 的 nodes[].name / relations[].type。 */
    private static QueryKnowledgeGraphTool.ExtractConfigView extractConfigView(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return null;
        }
        List<QueryKnowledgeGraphTool.GraphNodeView> nodes = new ArrayList<>();
        JsonNode n = cfg.path("nodes");
        if (n.isArray()) {
            for (JsonNode item : n) {
                nodes.add(new QueryKnowledgeGraphTool.GraphNodeView(item.path("name").asText("")));
            }
        }
        List<QueryKnowledgeGraphTool.GraphRelationView> relations = new ArrayList<>();
        JsonNode r = cfg.path("relations");
        if (r.isArray()) {
            for (JsonNode item : r) {
                relations.add(new QueryKnowledgeGraphTool.GraphRelationView(item.path("type").asText("")));
            }
        }
        return new QueryKnowledgeGraphTool.ExtractConfigView(nodes, relations);
    }
    private static KnowledgeSearchTool.KBView toKbView(KnowledgeBase kb) {
        boolean vector = false;
        boolean keyword = false;
        if (kb.getIndexingStrategy() != null) {
            vector = kb.getIndexingStrategy().isVectorEnabled();
            keyword = kb.getIndexingStrategy().isKeywordEnabled();
        }
        return new KnowledgeSearchTool.KBView(kb.getId(), AgentToolBackends.nz(kb.getType()), vector, keyword);
    }
    private static KnowledgeSearchTool.SearchResultView toSearchResultView(SearchResult r) {
        KnowledgeSearchTool.SearchResultView v = new KnowledgeSearchTool.SearchResultView();
        v.id = AgentToolBackends.nz(r.getId());
        v.content = AgentToolBackends.nz(r.getContent());
        v.knowledgeId = AgentToolBackends.nz(r.getKnowledgeId());
        v.knowledgeBaseId = AgentToolBackends.nz(r.getKnowledgeBaseId());
        v.knowledgeTitle = AgentToolBackends.nz(r.getKnowledgeTitle());
        v.chunkIndex = r.getChunkIndex();
        v.chunkType = AgentToolBackends.nz(r.getChunkType());
        v.parentChunkId = AgentToolBackends.nz(r.getParentChunkId());
        v.imageInfo = AgentToolBackends.nz(r.getImageInfo());
        v.knowledgeCustomMetadata = AgentToolBackends.nz(r.getKnowledgeCustomMetadata());
        v.knowledgeSource = AgentToolBackends.nz(r.getKnowledgeSource());
        v.startAt = r.getStartAt();
        v.endAt = r.getEndAt();
        v.score = r.getScore();
        v.matchType = r.getMatchType();
        return v;
    }
}
