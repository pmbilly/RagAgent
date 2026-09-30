package com.ragagent.retrieval;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.knowledge.ChunkFacts;
import com.ragagent.common.knowledge.KnowledgeDocumentFacts;
import com.ragagent.common.knowledge.KnowledgeDocumentFacts;
import com.ragagent.common.knowledge.KnowledgeDocumentFacts;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HybridSearch 的结果装配簇：命中 → SearchResult（chunk 元数据补全 + FAQ 问题回填）。
 */
final class HybridResultOps {

    private static final Logger log = LoggerFactory.getLogger(HybridResultOps.class);

    private final HybridSearchService service;

    HybridResultOps(HybridSearchService service) {
        this.service = service;
    }

    // ── 结果装配（knowledgebase_search_results.go 全文） ──────────────────

    List<SearchResult> processSearchResults(
            List<PgVectorRetrieveRepository.IndexHit> chunks, boolean skipEnrichment) {
        if (chunks.isEmpty()) {
            return null;
        }
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();

        Set<String> knowledgeIds = new LinkedHashSet<>();
        List<String> chunkIds = new ArrayList<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> matchTypes = new HashMap<>();
        Map<String, String> matchedContents = new HashMap<>();
        for (PgVectorRetrieveRepository.IndexHit c : chunks) {
            knowledgeIds.add(c.knowledgeId);
            chunkIds.add(c.chunkId);
            scores.put(c.chunkId, c.score);
            matchTypes.put(c.chunkId, c.matchType);
            matchedContents.put(c.chunkId, c.content);
        }

        List<KnowledgeDocumentFacts> knowledgeList = service.documentGateway.findAccessibleDocuments(
                tenantId == null ? 0 : tenantId, new ArrayList<>(knowledgeIds));
        Map<String, KnowledgeDocumentFacts> knowledgeMap = new HashMap<>();
        for (KnowledgeDocumentFacts k : knowledgeList) {
            knowledgeMap.put(k.id(), k);
        }

        List<ChunkFacts> allChunks = tenantId == null ? List.of()
                : service.chunkGateway.findChunks(tenantId, chunkIds);
        Map<String, ChunkFacts> chunkMap = new HashMap<>();
        for (ChunkFacts c : allChunks) {
            chunkMap.put(c.id(), c);
        }

        if (!skipEnrichment) {
            Set<String> processed = new HashSet<>();
            List<String> additional = new ArrayList<>();
            for (ChunkFacts c : allChunks) {
                processed.add(c.id());
            }
            for (ChunkFacts c : allChunks) {
                if (!c.parentChunkId().isEmpty() && !processed.contains(c.parentChunkId())) {
                    additional.add(c.parentChunkId());
                    processed.add(c.parentChunkId());
                    scores.put(c.parentChunkId(), scores.getOrDefault(c.id(), 0.0));
                    matchTypes.put(c.parentChunkId(), HybridSearchService.MATCH_PARENT_CHUNK);
                }
                for (String rel : relatedChunkIds(c, processed)) {
                    additional.add(rel);
                    matchTypes.put(rel, HybridSearchService.MATCH_RELATION_CHUNK);
                }
                if ("text".equals(c.chunkType())) {
                    if (!c.nextChunkId().isEmpty() && !processed.contains(c.nextChunkId())) {
                        additional.add(c.nextChunkId());
                        processed.add(c.nextChunkId());
                        matchTypes.put(c.nextChunkId(), HybridSearchService.MATCH_NEAR_BY_CHUNK);
                    }
                    if (!c.preChunkId().isEmpty() && !processed.contains(c.preChunkId())) {
                        additional.add(c.preChunkId());
                        processed.add(c.preChunkId());
                        matchTypes.put(c.preChunkId(), HybridSearchService.MATCH_NEAR_BY_CHUNK);
                    }
                }
            }
            for (String aid : additional) {
                ChunkFacts extra = fetchChunk(tenantId, aid);
                if (extra != null) {
                    chunkMap.put(extra.id(), extra);
                }
            }
        }

        // 首轮：按输入顺序装配。
        List<SearchResult> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (PgVectorRetrieveRepository.IndexHit input : chunks) {
            ChunkFacts chunk = chunkMap.get(input.chunkId);
            if (chunk == null || !isSearchableChunk(chunk) || added.contains(chunk.id())) {
                continue;
            }
            KnowledgeDocumentFacts knowledge = knowledgeMap.get(chunk.knowledgeId());
            if (knowledge == null) {
                continue;
            }
            out.add(buildSearchResult(chunk, knowledge,
                    scores.getOrDefault(chunk.id(), 0.0),
                    matchTypes.getOrDefault(chunk.id(), HybridSearchService.MATCH_EMBEDDING),
                    matchedContents.getOrDefault(chunk.id(), "")));
            added.add(chunk.id());
        }
        return out;
    }


    ChunkFacts fetchChunk(Long tenantId, String id) {
        try {
            List<ChunkFacts> rows = service.chunkGateway.findChunks(tenantId, List.of(id));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 对照 isSearchableChunk（knowledgebase_search_results.go L337-353）。 */
    static boolean isSearchableChunk(ChunkFacts chunk) {
        if (chunk == null || !chunk.enabled()) {
            return false;
        }
        String status = chunk.indexStatus();
        if ("processing".equals(status) || "failed".equals(status)) {
            return false;
        }
        return List.of("text", "summary", "table_column", "table_summary",
                "faq", "image_ocr", "image_caption").contains(chunk.chunkType());
    }

    /** 对照 buildSearchResult（knowledgebase_search_results.go L303-334）。 */
    static SearchResult buildSearchResult(ChunkFacts chunk, KnowledgeDocumentFacts knowledge,
            double score, int matchType, String matchedContent) {
        SearchResult r = new SearchResult();
        r.setId(chunk.id());
        r.setContent(chunk.content());
        r.setContentRevision(chunk.contentRevision());
        r.setKnowledgeId(chunk.knowledgeId());
        r.setChunkIndex(chunk.chunkIndex());
        r.setKnowledgeTitle(knowledge.title());
        r.setStartAt(chunk.startAt());
        r.setEndAt(chunk.endAt());
        r.setSeq(chunk.chunkIndex());
        r.setScore(score);
        r.setMatchType(matchType);
        // 对照 KnowledgeDocumentFacts.GetMetadata（types/knowledge.go L227-240）：nil → 空 map
        // （序列化 "{}"）；不可解析 → null。SearchResult.metadata 是 map[string]string
        // （Go 同型），只挑字符串值（Go 的 types.JSON.Map() 对非字符串值报错 → nil）。
        JsonNode meta = knowledge.metadata();
        if (meta == null || meta.isNull()) {
            r.setMetadata(new LinkedHashMap<>());
        } else if (meta.isObject()) {
            Map<String, String> m = new LinkedHashMap<>();
            boolean allStrings = true;
            for (var it = meta.fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() == null || !e.getValue().isTextual()) {
                    allStrings = false;
                    break;
                }
                m.put(e.getKey(), e.getValue().asText());
            }
            r.setMetadata(allStrings ? m : null);
        } else {
            r.setMetadata(null);
        }
        r.setChunkType(chunk.chunkType());
        r.setParentChunkId(chunk.parentChunkId());
        r.setKnowledgeFilename(knowledge.fileName());
        r.setKnowledgeSource(knowledge.source());
        r.setKnowledgeChannel(knowledge.channel());
        r.setKnowledgeDescription(knowledge.description());
        r.setKnowledgeBaseId(knowledge.knowledgeBaseId());
        r.setMatchedContent(matchedContent);
        return r;
    }


    static List<String> relatedChunkIds(ChunkFacts chunk, Set<String> processed) {
        List<String> related = new ArrayList<>();
        JsonNode rel = chunk.relationChunks();
        if (rel != null && rel.isArray()) {
            for (JsonNode n : rel) {
                String id = n.asText("");
                if (!id.isEmpty() && !processed.contains(id)) {
                    related.add(id);
                    processed.add(id);
                }
            }
        }
        return related;
    }
}
