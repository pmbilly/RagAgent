package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.chatpipeline.ChatManage.GraphData;
import com.ragagent.chatpipeline.ChatManage.GraphNode;
import com.ragagent.chatpipeline.ChatManage.NameSpace;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * ENTITY_SEARCH 阶段插件（对照 Go chat_pipeline/search_entity.go）：
 * 按实体查图（逐知识文件或逐知识库，Go 侧并发 goroutine），把新命中的 chunk
 * 转成 SearchResult（分数恒 1.0、MatchTypeGraph）并合入 SearchResult。
 *
 * <p>Go 的 per-KB 并发结果合并进共享切片（mu 保护），顺序随机；Java 侧用
 * LinkedHashSet 保出现序的确定性备案（实录组 search_parallel 用稳定段比较）。
 * chunk2SearchResult 的 metadata 取 Knowledge.GetMetadata()（jsonb → map[string]string，
 * 值 %v 字符串化，解析失败 nil）。</p>
 */
public final class PluginSearchEntity implements Plugin {

    private final PipelinePorts.RetrieveGraphRepository graphRepo;
    private final PipelinePorts.ChunkRepository chunkRepo;
    private final PipelinePorts.KnowledgeRepository knowledgeRepo;

    public PluginSearchEntity(PipelinePorts.RetrieveGraphRepository graphRepository,
                              PipelinePorts.ChunkRepository chunkRepository,
                              PipelinePorts.KnowledgeRepository knowledgeRepository) {
        this.graphRepo = graphRepository;
        this.chunkRepo = chunkRepository;
        this.knowledgeRepo = knowledgeRepository;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.ENTITY_SEARCH};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        List<String> entity = chatManage.getEntity();
        if (entity == null || entity.isEmpty()) {
            PipelineLog.info("search_entity", "no_entity", new LinkedHashMap<>());
            return next.next();
        }

        List<String> knowledgeBaseIDs = chatManage.getEntityKbIds();
        Map<String, String> entityKnowledge = chatManage.getEntityKnowledge();

        if ((knowledgeBaseIDs == null || knowledgeBaseIDs.isEmpty())
                && (entityKnowledge == null || entityKnowledge.isEmpty())) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("msg", "No knowledge base IDs or knowledge IDs with ExtractConfig enabled for entity search");
            PipelineLog.warn("search_entity", "no_scope", f);
            return next.next();
        }

        List<GraphNode> allNodes = new ArrayList<>();
        List<ChatManage.GraphRelation> allRelations = new ArrayList<>();

        if (entityKnowledge != null && !entityKnowledge.isEmpty()) {
            for (Map.Entry<String, String> e : entityKnowledge.entrySet()) {
                searchInto(e.getValue(), e.getKey(), entity, allNodes, allRelations);
            }
        } else {
            for (String kbId : knowledgeBaseIDs) {
                searchInto(kbId, "", entity, allNodes, allRelations);
            }
        }

        chatManage.setGraphResult(new GraphData(allNodes, allRelations));

        long tenantId = currentTenantId(chatManage);
        List<String> chunkIDs = filterSeenChunk(chatManage.getGraphResult(), chatManage.getSearchResult());
        if (chunkIDs.isEmpty()) {
            PipelineLog.info("search_entity", "no_new_chunk", new LinkedHashMap<>());
            return next.next();
        }
        List<Chunk> chunks;
        try {
            chunks = chunkRepo.listChunksById(tenantId, chunkIDs);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "list_chunks", f);
            return next.next();
        }
        List<String> knowledgeIDs = new ArrayList<>();
        for (Chunk chunk : chunks) {
            knowledgeIDs.add(chunk.getKnowledgeId());
        }
        List<Knowledge> knowledges;
        try {
            knowledges = knowledgeRepo.getKnowledgeBatch(tenantId, knowledgeIDs);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "list_knowledge", f);
            return next.next();
        }

        Map<String, Knowledge> knowledgeMap = new LinkedHashMap<>();
        for (Knowledge knowledge : knowledges) {
            knowledgeMap.put(knowledge.getId(), knowledge);
        }
        List<SearchResult> entityResults = new ArrayList<>();
        for (Chunk chunk : chunks) {
            entityResults.add(chunk2SearchResult(chunk, knowledgeMap.get(chunk.getKnowledgeId())));
        }
        enrichSearchResultsImageInfo(tenantId, entityResults);
        if (chatManage.getSearchResult() == null) {
            chatManage.setSearchResult(new ArrayList<>());
        }
        chatManage.getSearchResult().addAll(entityResults);
        chatManage.setSearchResult(SearchSupport.removeDuplicateResults(chatManage.getSearchResult()));
        if (chatManage.getSearchResult() == null || chatManage.getSearchResult().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.info("search_entity", "no_new_result", f);
            return PluginError.SEARCH_NOTHING;
        }
        return next.next();
    }

    private void searchInto(String knowledgeBaseId, String knowledgeId, List<String> entity,
                            List<GraphNode> allNodes, List<ChatManage.GraphRelation> allRelations) {
        GraphData graph;
        try {
            graph = graphRepo.searchNode(new NameSpace(knowledgeBaseId, knowledgeId), entity);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put(knowledgeId.isEmpty() ? "kb" : "knowledge",
                    knowledgeId.isEmpty() ? knowledgeBaseId : knowledgeId);
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "search_node", f);
            return;
        }
        if (graph != null && graph.node() != null) {
            allNodes.addAll(graph.node());
        }
        if (graph != null && graph.relation() != null) {
            allRelations.addAll(graph.relation());
        }
    }

    /** 对照 types.MustTenantIDFromContext：上下文无租户 → chatManage 兜底（Java 无 panic 语义）。 */
    private long currentTenantId(ChatManage chatManage) {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tid != null && tid != 0) {
            return tid;
        }
        return chatManage.getTenantId();
    }

    /** 对照 filterSeenChunk。 */
    static List<String> filterSeenChunk(GraphData graph, List<SearchResult> searchResult) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (searchResult != null) {
            for (SearchResult chunk : searchResult) {
                seen.put(chunk.getId(), Boolean.TRUE);
            }
        }
        List<String> chunkIDs = new ArrayList<>();
        if (graph != null && graph.node() != null) {
            for (GraphNode node : graph.node()) {
                if (node.getChunks() == null) {
                    continue;
                }
                for (String chunkID : node.getChunks()) {
                    if (seen.containsKey(chunkID)) {
                        continue;
                    }
                    seen.put(chunkID, Boolean.TRUE);
                    chunkIDs.add(chunkID);
                }
            }
        }
        return chunkIDs;
    }

    /** 对照 chunk2SearchResult。 */
    static SearchResult chunk2SearchResult(Chunk chunk, Knowledge knowledge) {
        SearchResult r = new SearchResult();
        r.setId(chunk.getId());
        r.setContent(chunk.getContent());
        r.setContentRevision(chunk.getContentRevision());
        r.setKnowledgeId(chunk.getKnowledgeId());
        r.setChunkIndex(chunk.getChunkIndex());
        r.setKnowledgeTitle(knowledge == null ? "" : knowledge.getTitle());
        r.setStartAt(chunk.getStartAt());
        r.setEndAt(chunk.getEndAt());
        r.setSeq(chunk.getChunkIndex());
        r.setScore(1.0);
        r.setMatchType(MatchTypes.GRAPH);
        r.setMetadata(knowledgeMetadata(knowledge));
        r.setChunkType(chunk.getChunkType());
        r.setParentChunkId(chunk.getParentChunkId());
        r.setImageInfo(chunk.getImageInfo());
        r.setKnowledgeFilename(knowledge == null ? "" : knowledge.getFileName());
        r.setKnowledgeSource(knowledge == null ? "" : knowledge.getSource());
        r.setKnowledgeChannel(knowledge == null ? "" : knowledge.getChannel());
        r.setChunkMetadata(chunk.getMetadata());
        r.setKnowledgeBaseId(knowledge == null ? "" : knowledge.getKnowledgeBaseId());
        return r;
    }

    /** 对照 Knowledge.GetMetadata：jsonb → 全字符串 map（%v 值形态），空表无键、解析失败 null。 */
    static Map<String, String> knowledgeMetadata(Knowledge knowledge) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (knowledge == null || knowledge.getMetadata() == null
                || !knowledge.getMetadata().isObject()
                || knowledge.getMetadata().isEmpty()) {
            return metadata;
        }
        var fields = knowledge.getMetadata().fields();
        while (fields.hasNext()) {
            var e = fields.next();
            metadata.put(e.getKey(), GoValueStr.goStringify(e.getValue()));
        }
        return metadata;
    }

    /** 图像信息富化（对照 searchutil.EnrichSearchResultsImageInfo）。 */
    private void enrichSearchResultsImageInfo(long tenantId, List<SearchResult> results) {
        ImageInfoCollector.enrichSearchResultsImageInfo(chunkRepo, tenantId, results);
    }
}
