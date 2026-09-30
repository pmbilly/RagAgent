package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.support.ChunkSearchUtil;
import com.ragagent.retrieval.support.ImageInfoEnricher;
import com.ragagent.retrieval.support.ImageInfoMatchUtil;
import com.ragagent.retrieval.support.SearchChunkMerge;
import com.ragagent.retrieval.support.SearchTextUtil;
import com.ragagent.common.web.JsonMappers;

/**
 * CHUNK_MERGE 阶段插件（对照 Go chat_pipeline 的 merge.go + merge_expand.go +
 * merge_faq.go + merge_history.go + merge_overlap.go 五个文件收进一类；
 * 分段注释 = Go 文件名）。
 *
 * <h2>OnEvent 八步（merge.go:44-97）</h2>
 * 输入选择 → 去重 → 历史引用注入 → 父块解析 → 分组顺序合并 → FAQ 答案回填 →
 * 短上下文邻居扩展 → 扩展后再合并 → 终去重（ID+签名+部分重叠）。
 *
 * <h2>合并分类（merge_overlap.go 的 classifyMerge，实录组 merge_classify 钉住）</h2>
 * SEPARATE / EXTEND（可信对，位置重叠裁剪）/ SUBSUME（可信包含）/ JOIN_DISTINCT /
 * JOIN_TEXT（不可信，纯文本匹配）。可信 = 未编辑 + 未被管线改写 + 坐标区间有效 +
 * runeLen(Content) == EndAt-StartAt（长度不变量）。
 */
public final class PluginMerge implements Plugin {

    private static final ObjectMapper JSON = JsonMappers.lenient();

    private final PipelinePorts.ChunkRepository chunkRepo;
    private final PipelinePorts.ChunkService chunkService; // 父块解析预留（与 Go 一致当前未用）

    public PluginMerge(PipelinePorts.ChunkRepository chunkRepo, PipelinePorts.ChunkService chunkService) {
        this.chunkRepo = chunkRepo;
        this.chunkService = chunkService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHUNK_MERGE};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!chatManage.needsRetrieval()) {
            return next.next();
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("candidate_cnt", chatManage.getRerankResult() == null ? 0 : chatManage.getRerankResult().size());
        PipelineLog.info("Merge", "input", in);

        // Step 1: 输入选择
        List<SearchResult> searchResult = selectInputResults(chatManage);

        // Step 2: 初步去重
        searchResult = dedup("dedup_summary", searchResult);

        // Step 3: 注入历史引用
        searchResult = injectHistoryResults(chatManage, searchResult);

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("chunk_cnt", searchResult == null ? 0 : searchResult.size());
        PipelineLog.info("Merge", "candidate_ready", ready);

        if (searchResult == null || searchResult.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chunk_cnt", 0);
            out.put("reason", "no_candidates");
            PipelineLog.warn("Merge", "output", out);
            return next.next();
        }

        // Step 4: 父块解析
        searchResult = resolveParentChunks(chatManage, searchResult);

        // Step 5: 分组 + 顺序合并
        List<SearchResult> mergedChunks = groupAndMergeCurrentContent(searchResult);

        // Step 6: FAQ 答案回填
        mergedChunks = populateFAQAnswers(chatManage, mergedChunks);

        // Step 7: 短上下文扩展
        mergedChunks = expandShortContextWithNeighbors(chatManage, mergedChunks);

        // Step 7.5: 扩展引入的重叠再合并
        mergedChunks = groupAndMergeCurrentContent(mergedChunks);

        // Step 8: 终去重
        mergedChunks = dedup("final_dedup", mergedChunks);
        mergedChunks = SearchSupport.removePartialOverlaps(mergedChunks);

        chatManage.setMergeResult(mergedChunks);
        return next.next();
    }

    // ------------------------------------------------------------------
    // merge.go：输入选择 / 去重 / 历史注入
    // ------------------------------------------------------------------

    /** 对照 selectInputResults：rerank 优先，回落按分数降序的检索结果。 */
    private List<SearchResult> selectInputResults(ChatManage chatManage) {
        if (chatManage.getRerankResult() != null && !chatManage.getRerankResult().isEmpty()) {
            return chatManage.getRerankResult();
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("reason", "empty_rerank_result");
        PipelineLog.warn("Merge", "fallback", f);
        List<SearchResult> result = chatManage.getSearchResult();
        result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return result;
    }

    /** 对照 dedup：带前后日志的 removeDuplicateResults。 */
    private List<SearchResult> dedup(String label, List<SearchResult> results) {
        int before = results == null ? 0 : results.size();
        List<SearchResult> out = SearchSupport.removeDuplicateResults(results);
        if (out != null && out.size() < before) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("before", before);
            f.put("after", out.size());
            PipelineLog.info("Merge", label, f);
        }
        return out;
    }

    /** 对照 injectHistoryResults：历史引用注入后重去重。 */
    private List<SearchResult> injectHistoryResults(ChatManage chatManage, List<SearchResult> current) {
        List<SearchResult> historyResults = filterHistoryResults(chatManage, current);
        if (historyResults == null || historyResults.isEmpty()) {
            return current;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("session_id", chatManage.getSessionId());
        f.put("history_hits", historyResults.size());
        PipelineLog.info("Merge", "history_inject", f);
        List<SearchResult> combined = new ArrayList<>(current);
        combined.addAll(historyResults);
        return SearchSupport.removeDuplicateResults(combined);
    }

    /** 对照 groupAndMergeCurrentContent：KnowledgeID+ChunkType 分组 → 组内顺序合并 → 全局确定性排序。 */
    List<SearchResult> groupAndMergeCurrentContent(List<SearchResult> results) {
        // KnowledgeID → ChunkType → chunks（LinkedHashMap 保插入序；全局排序还原确定性）
        Map<String, Map<String, List<SearchResult>>> knowledgeGroup = new LinkedHashMap<>();
        for (SearchResult chunk : results) {
            knowledgeGroup.computeIfAbsent(chunk.getKnowledgeId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(chunk.getChunkType(), k -> new ArrayList<>())
                    .add(chunk);
        }

        Map<String, Object> gs = new LinkedHashMap<>();
        gs.put("knowledge_cnt", knowledgeGroup.size());
        PipelineLog.info("Merge", "group_summary", gs);

        List<List<SearchResult>> units = new ArrayList<>();
        List<String> unitKnowledgeIds = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<SearchResult>>> e : knowledgeGroup.entrySet()) {
            for (List<SearchResult> chunks : e.getValue().values()) {
                units.add(chunks);
                unitKnowledgeIds.add(e.getKey());
            }
        }

        List<List<SearchResult>> groupResults = PipelineCommon.parallelMap(units, 0, (idx, u) -> {
            String knowledgeId = unitKnowledgeIds.get(idx);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("knowledge_id", knowledgeId);
            f.put("chunk_cnt", u.size());
            PipelineLog.info("Merge", "group_process", f);

            u.sort((a, b) -> {
                if (a.getChunkIndex() == b.getChunkIndex()) {
                    return a.getId().compareTo(b.getId());
                }
                return Integer.compare(a.getChunkIndex(), b.getChunkIndex());
            });

            List<SearchResult> grouped = mergeSequentialChunks(knowledgeId, u);

            Map<String, Object> o = new LinkedHashMap<>();
            o.put("knowledge_id", knowledgeId);
            o.put("merged_chunks", grouped == null ? 0 : grouped.size());
            PipelineLog.info("Merge", "group_output", o);
            return grouped;
        });

        List<SearchResult> mergedChunks = new ArrayList<>();
        for (List<SearchResult> g : groupResults) {
            if (g != null) {
                mergedChunks.addAll(g);
            }
        }

        PluginFilterTopK.sortSearchResultsDeterministically(mergedChunks);

        Map<String, Object> o2 = new LinkedHashMap<>();
        o2.put("merged_total", mergedChunks.size());
        PipelineLog.info("Merge", "output", o2);
        return mergedChunks;
    }

    // ------------------------------------------------------------------
    // merge.go：resolveParentChunks（text→parent / image→text→grandparent）
    // ------------------------------------------------------------------

    List<SearchResult> resolveParentChunks(ChatManage chatManage, List<SearchResult> results) {
        if (results.isEmpty() || chunkRepo == null) {
            return results;
        }

        long tenantId = chatManage != null ? chatManage.getTenantId() : 0;
        if (tenantId == 0) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "missing_tenant");
            PipelineLog.warn("Merge", "parent_resolve_skip", f);
            return results;
        }

        // 收集去重后的父块 ID（保出现序）
        Map<String, Boolean> parentIds = new LinkedHashMap<>();
        for (SearchResult r : results) {
            if (!r.getParentChunkId().isEmpty()) {
                parentIds.putIfAbsent(r.getParentChunkId(), Boolean.TRUE);
            }
        }
        if (parentIds.isEmpty()) {
            return results;
        }

        List<SearchResult> working = results;
        List<Chunk> parentChunks;
        try {
            parentChunks = chunkRepo.listChunksById(tenantId, new ArrayList<>(parentIds.keySet()));
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "parent_resolve_failed", f);
            return results;
        }

        Map<String, Chunk> parentMap = new LinkedHashMap<>();
        for (Chunk c : parentChunks) {
            parentMap.put(c.getId(), c);
        }

        // 图片命中走 image → text → parent_text 链：只为这些结果取祖父块
        Map<String, Boolean> imageTextParentIds = new LinkedHashMap<>();
        for (SearchResult r : working) {
            if (ChunkTypes.IMAGE_OCR.equals(r.getChunkType())
                    || ChunkTypes.IMAGE_CAPTION.equals(r.getChunkType())) {
                imageTextParentIds.putIfAbsent(r.getParentChunkId(), Boolean.TRUE);
            }
        }
        if (!imageTextParentIds.isEmpty()) {
            List<String> grandparentIds = new ArrayList<>();
            Map<String, Boolean> grandparentSeen = new LinkedHashMap<>();
            for (Chunk parent : parentChunks) {
                if (!imageTextParentIds.containsKey(parent.getId())) {
                    continue;
                }
                if (parent.getParentChunkId().isEmpty() || !ChunkTypes.TEXT.equals(parent.getChunkType())) {
                    continue;
                }
                if (parentMap.containsKey(parent.getParentChunkId())) {
                    continue;
                }
                if (grandparentSeen.containsKey(parent.getParentChunkId())) {
                    continue;
                }
                grandparentSeen.put(parent.getParentChunkId(), Boolean.TRUE);
                grandparentIds.add(parent.getParentChunkId());
            }
            if (!grandparentIds.isEmpty()) {
                List<Chunk> grandparents;
                try {
                    grandparents = chunkRepo.listChunksById(tenantId, grandparentIds);
                    for (Chunk grandparent : grandparents) {
                        parentMap.put(grandparent.getId(), grandparent);
                    }
                } catch (RuntimeException e) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("error", e.getMessage());
                    PipelineLog.warn("Merge", "grandparent_fetch_failed", f);
                }
            }
        }

        // 批量取 image_info（只取命中的 text 子块）
        List<String> textChildIds = collectScopedTextChildIds(working, parentMap);
        Map<String, String> scopedImageInfo = null;
        if (!textChildIds.isEmpty()) {
            scopedImageInfo = collectImageInfoByChunkIds(tenantId, textChildIds);
        }

        for (SearchResult r : working) {
            if (r.getParentChunkId().isEmpty()) {
                continue;
            }

            if (ChunkTypes.TEXT.equals(r.getChunkType())) {
                // text → parent_text：扩展到全父块给上下文；ImageInfo 只取本子块的
                Chunk parent = parentMap.get(r.getParentChunkId());
                if (parent == null || parent.getContent().isEmpty()
                        || !ChunkTypes.PARENT_TEXT.equals(parent.getChunkType())) {
                    continue;
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("child_id", r.getId());
                f.put("parent_id", r.getParentChunkId());
                f.put("child_len", runeLen(r.getContent()));
                f.put("parent_len", runeLen(parent.getContent()));
                f.put("scoped_img", true);
                PipelineLog.info("Merge", "parent_resolve", f);
                assignScopedImageInfo(r, scopedImageInfo, r.getId());
                String parentContent = ImageInfoMatchUtil.pruneMarkdownImagesByImageInfo(
                        parent.getContent(), r.getImageInfo());
                r.setContent(ChunkSearchUtil.joinChunkContent(parentContent, r.getContent(), "\n\n"));
                r.setContentRewritten(true);
                if (!containsId(r.getSubChunkId(), r.getId())) {
                    appendSubChunkId(r, r.getId());
                }
            } else if (ChunkTypes.IMAGE_OCR.equals(r.getChunkType())
                    || ChunkTypes.IMAGE_CAPTION.equals(r.getChunkType())) {
                Chunk textParent = parentMap.get(r.getParentChunkId());
                if (textParent == null || textParent.getContent().isEmpty()
                        || !ChunkTypes.TEXT.equals(textParent.getChunkType())) {
                    continue;
                }
                String hitImageInfo = r.getImageInfo();
                // 命中块本身携带识别文本（Content 是 OCR/描述），先存后覆写（#3052）
                String childRecognizedContent = r.getContent();
                Chunk contentSource = textParent;
                if (!textParent.getParentChunkId().isEmpty()) {
                    Chunk grandparent = parentMap.get(textParent.getParentChunkId());
                    if (grandparent != null && ChunkTypes.PARENT_TEXT.equals(grandparent.getChunkType())
                            && !grandparent.getContent().isEmpty()) {
                        contentSource = grandparent;
                    }
                }
                r.setContent(textParent.getContent());
                r.setChunkIndex(textParent.getChunkIndex());
                r.setContentRewritten(true);
                assignScopedImageInfo(r, scopedImageInfo, textParent.getId());
                if (r.getImageInfo().isEmpty() && !hitImageInfo.isEmpty()) {
                    r.setImageInfo(ImageInfoMatchUtil.filterImageInfoByContentUrls(
                            textParent.getContent(), hitImageInfo));
                }
                String textContent = ImageInfoMatchUtil.pruneMarkdownImagesByImageInfo(
                        textParent.getContent(), r.getImageInfo());
                String parentContent = ImageInfoMatchUtil.pruneMarkdownImagesByImageInfo(
                        contentSource.getContent(), r.getImageInfo());
                r.setContent(ChunkSearchUtil.joinChunkContent(parentContent, textContent, "\n\n"));
                // 父/祖父 markdown 之后重新接上识别文本（JoinChunkContent 折叠重复）
                r.setContent(ChunkSearchUtil.joinChunkContent(r.getContent(), childRecognizedContent, "\n\n"));
                r.setImageInfo(ImageInfoEnricher.clearImageInfoTextMatchingBody(
                        r.getImageInfo(), childRecognizedContent, r.getChunkType()));
                r.setContentRewritten(true);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("child_id", r.getId());
                f.put("child_type", r.getChunkType());
                f.put("text_id", textParent.getId());
                f.put("parent_id", contentSource.getId());
                f.put("match_len", runeLen(r.getContent()));
                f.put("parent_len", runeLen(contentSource.getContent()));
                f.put("scoped", true);
                PipelineLog.info("Merge", "image_parent_resolve", f);
                if (!containsId(r.getSubChunkId(), r.getId())) {
                    appendSubChunkId(r, r.getId());
                }
            }
        }

        return working;
    }

    /** 对照 collectScopedTextChildIDs。 */
    static List<String> collectScopedTextChildIds(List<SearchResult> results, Map<String, Chunk> parentMap) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (SearchResult r : results) {
            if (r.getParentChunkId().isEmpty()) {
                continue;
            }
            switch (r.getChunkType()) {
                case ChunkTypes.TEXT -> {
                    Chunk parent = parentMap.get(r.getParentChunkId());
                    if (parent == null || !ChunkTypes.PARENT_TEXT.equals(parent.getChunkType())) {
                        continue;
                    }
                    if (seen.containsKey(r.getId())) {
                        continue;
                    }
                    seen.put(r.getId(), Boolean.TRUE);
                    ids.add(r.getId());
                }
                case ChunkTypes.IMAGE_OCR, ChunkTypes.IMAGE_CAPTION -> {
                    if (seen.containsKey(r.getParentChunkId())) {
                        continue;
                    }
                    seen.put(r.getParentChunkId(), Boolean.TRUE);
                    ids.add(r.getParentChunkId());
                }
                default -> {}
            }
        }
        return ids;
    }

    /** 对照 assignScopedImageInfo：per-child image_info 优先，回落按内容 URL 过滤。 */
    static void assignScopedImageInfo(SearchResult r, Map<String, String> scoped, String textChildId) {
        if (scoped != null) {
            String info = scoped.get(textChildId);
            if (info != null && !info.isEmpty()) {
                r.setImageInfo(info);
                return;
            }
        }
        if (!r.getImageInfo().isEmpty()) {
            r.setImageInfo(ImageInfoMatchUtil.filterImageInfoByContentUrls(r.getContent(), r.getImageInfo()));
        }
    }

    /** 对照 searchutil.CollectImageInfoByChunkIDs（聚合器在本包 ImageInfoCollector）。 */
    private Map<String, String> collectImageInfoByChunkIds(long tenantId, List<String> chunkIds) {
        return ImageInfoCollector.collect(chunkRepo, tenantId, chunkIds);
    }

    // ------------------------------------------------------------------
    // merge_expand.go：短上下文邻居扩展
    // ------------------------------------------------------------------

    List<SearchResult> expandShortContextWithNeighbors(ChatManage chatManage, List<SearchResult> results) {
        final int minLen = 350;
        final int maxLen = 850;

        if (results.isEmpty() || chunkRepo == null) {
            return results;
        }

        long tenantId = chatManage != null ? chatManage.getTenantId() : 0;
        if (tenantId == 0) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "missing_tenant");
            PipelineLog.warn("Merge", "expand_skip", f);
            return results;
        }

        List<SearchResult> targets = new ArrayList<>();
        Map<String, Boolean> baseIdsSet = new LinkedHashMap<>();

        for (SearchResult r : results) {
            if (r == null || r.getId().isEmpty() || r.getContent().isEmpty()) {
                continue;
            }
            if (!ChunkTypes.TEXT.equals(r.getChunkType())) {
                continue;
            }
            if (runeLen(r.getContent()) >= minLen) {
                continue;
            }
            targets.add(r);
            baseIdsSet.put(r.getId(), Boolean.TRUE);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chunk_id", r.getId());
            f.put("content", r.getContent());
            f.put("chunk_type", r.getChunkType());
            f.put("len", runeLen(r.getContent()));
            PipelineLog.info("Merge", "need_expand", f);
        }

        if (targets.isEmpty()) {
            return results;
        }

        List<String> baseIds = new ArrayList<>(baseIdsSet.keySet());

        Map<String, Chunk> chunkMap = new LinkedHashMap<>();
        List<Chunk> chunks;
        try {
            chunks = chunkRepo.listChunksById(tenantId, baseIds);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "expand_list_base_failed", f);
            return results;
        }
        for (Chunk chunk : chunks) {
            chunkMap.put(chunk.getId(), chunk);
        }

        Map<String, Boolean> neighborIdsSet = new LinkedHashMap<>();
        for (Chunk chunk : chunkMap.values()) {
            if (chunk == null) {
                continue;
            }
            if (!chunk.getPreChunkId().isEmpty() && !chunkMap.containsKey(chunk.getPreChunkId())) {
                neighborIdsSet.put(chunk.getPreChunkId(), Boolean.TRUE);
            }
            if (!chunk.getNextChunkId().isEmpty() && !chunkMap.containsKey(chunk.getNextChunkId())) {
                neighborIdsSet.put(chunk.getNextChunkId(), Boolean.TRUE);
            }
        }

        if (!neighborIdsSet.isEmpty()) {
            List<String> neighborIDs = new ArrayList<>(neighborIdsSet.keySet());
            try {
                List<Chunk> neighbors = chunkRepo.listChunksById(tenantId, neighborIDs);
                for (Chunk chunk : neighbors) {
                    chunkMap.put(chunk.getId(), chunk);
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("neighbor_chunk_id", chunk.getId());
                    f.put("neighbor_content", chunk.getContent());
                    f.put("neighbor_chunk_type", chunk.getChunkType());
                    f.put("neighbor_len", runeLen(chunk.getContent()));
                    PipelineLog.info("Merge", "expand_list_neighbor_success", f);
                }
            } catch (RuntimeException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", e.getMessage());
                PipelineLog.warn("Merge", "expand_list_neighbor_failed", f);
            }
        }

        for (SearchResult res : targets) {
            fetchChunksIfMissing(tenantId, chunkMap, res.getId());
            Chunk baseChunk = chunkMap.get(res.getId());
            if (baseChunk == null || baseChunk.getContent().isEmpty()
                    || !ChunkTypes.TEXT.equals(baseChunk.getChunkType())) {
                continue;
            }

            StringBuilder prevContent = new StringBuilder();
            StringBuilder nextContent = new StringBuilder();
            List<String> prevIDs = new ArrayList<>();
            List<String> nextIDs = new ArrayList<>();

            String prevCursor = baseChunk.getPreChunkId();
            String nextCursor = baseChunk.getNextChunkId();

            fetchChunksIfMissing(tenantId, chunkMap, prevCursor, nextCursor);

            if (!prevCursor.isEmpty()) {
                Chunk prevChunk = chunkMap.get(prevCursor);
                if (prevChunk != null && prevChunk.getKnowledgeId().equals(baseChunk.getKnowledgeId())) {
                    prevContent.append(prevChunk.getContent());
                    prevIDs.add(prevChunk.getId());
                    prevCursor = prevChunk.getPreChunkId();
                } else {
                    prevCursor = "";
                }
            }

            if (!nextCursor.isEmpty()) {
                Chunk nextChunk = chunkMap.get(nextCursor);
                if (nextChunk != null && nextChunk.getKnowledgeId().equals(baseChunk.getKnowledgeId())) {
                    nextContent.append(nextChunk.getContent());
                    nextIDs.add(nextChunk.getId());
                    nextCursor = nextChunk.getNextChunkId();
                } else {
                    nextCursor = "";
                }
            }

            String merged;
            while (true) {
                merged = mergeOrderedContent(prevContent.toString(), baseChunk.getContent(),
                        nextContent.toString(), maxLen);
                if (merged.isEmpty()) {
                    break;
                }
                if (runeLen(merged) >= minLen) {
                    break;
                }
                if (prevCursor.isEmpty() && nextCursor.isEmpty()) {
                    break;
                }

                boolean expanded = false;
                if (!prevCursor.isEmpty()) {
                    fetchChunksIfMissing(tenantId, chunkMap, prevCursor);
                    Chunk prevChunk = chunkMap.get(prevCursor);
                    if (prevChunk != null && prevChunk.getKnowledgeId().equals(baseChunk.getKnowledgeId())) {
                        // Go: prevContent = JoinChunkContent(prevChunk.Content, prevContent, "\n\n")
                    prevContent = new StringBuilder(
                            ChunkSearchUtil.joinChunkContent(prevChunk.getContent(), prevContent.toString(), "\n\n"));
                        prevIDs.add(0, prevChunk.getId());
                        prevCursor = prevChunk.getPreChunkId();
                        expanded = true;
                    } else {
                        prevCursor = "";
                    }
                }

                merged = mergeOrderedContent(prevContent.toString(), baseChunk.getContent(),
                        nextContent.toString(), maxLen);
                if (runeLen(merged) >= minLen) {
                    break;
                }

                if (!nextCursor.isEmpty()) {
                    fetchChunksIfMissing(tenantId, chunkMap, nextCursor);
                    Chunk nextChunk = chunkMap.get(nextCursor);
                    if (nextChunk != null && nextChunk.getKnowledgeId().equals(baseChunk.getKnowledgeId())) {
                        // Go: nextContent = JoinChunkContent(nextContent, nextChunk.Content, "\n\n")
                        nextContent = new StringBuilder(
                                ChunkSearchUtil.joinChunkContent(nextContent.toString(), nextChunk.getContent(), "\n\n"));
                        nextIDs.add(nextChunk.getId());
                        nextCursor = nextChunk.getNextChunkId();
                        expanded = true;
                    } else {
                        nextCursor = "";
                    }
                }

                if (!expanded) {
                    break;
                }
            }

            if (merged.isEmpty()) {
                continue;
            }

            int beforeLen = runeLen(res.getContent());
            res.setContent(merged);
            res.setContentRewritten(true);

            for (String id : prevIDs) {
                if (!id.isEmpty() && !containsId(res.getSubChunkId(), id)) {
                    appendSubChunkId(res, id);
                }
            }
            for (String id : nextIDs) {
                if (!id.isEmpty() && !containsId(res.getSubChunkId(), id)) {
                    appendSubChunkId(res, id);
                }
            }

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chunk_id", res.getId());
            f.put("prev_ids", prevIDs);
            f.put("next_ids", nextIDs);
            f.put("before_len", beforeLen);
            f.put("after_len", runeLen(res.getContent()));
            f.put("base_content", baseChunk.getContent());
            f.put("after_content", res.getContent());
            f.put("chunk_type", res.getChunkType());
            f.put("remaining_prev", prevCursor);
            f.put("remaining_next", nextCursor);
            PipelineLog.info("Merge", "expand_short_chunk", f);
        }

        return results;
    }

    /** 对照 runeLen。 */
    static int runeLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 对照 mergeOrderedContent：prev + base + next 按序拼接，超 maxLen 截 rune。 */
    static String mergeOrderedContent(String prev, String base, String next, int maxLen) {
        String content = base;
        if (!prev.isEmpty()) {
            // Go 用 searchutil.JoinChunkContent（带重叠折叠），不是裸拼接——
            // 邻居块尾部常与 base 前缀重叠（parser 滑动窗口），裸拼会重复一段且
            // 多出 "\n\n"，与 Go 输出逐字节对不上（走查疑点⑫抓回）。
            content = ChunkSearchUtil.joinChunkContent(prev, content, "\n\n");
        }
        if (!next.isEmpty()) {
            content = ChunkSearchUtil.joinChunkContent(content, next, "\n\n");
        }
        int runes = runeLen(content);
        if (runes > maxLen) {
            return content.substring(0, content.offsetByCodePoints(0, maxLen));
        }
        return content;
    }

    static boolean containsId(List<String> ids, String target) {
        if (ids == null) {
            return false;
        }
        for (String id : ids) {
            if (id.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** SubChunkID 追加（保持 null→list 语义：Go 的 append 到 nil 产生单元素切片）。 */
    private static void appendSubChunkId(SearchResult r, String id) {
        List<String> ids = r.getSubChunkId();
        List<String> next = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        next.add(id);
        r.setSubChunkId(next);
    }

    private void fetchChunksIfMissing(long tenantId, Map<String, Chunk> chunkMap, String... chunkIds) {
        List<String> missing = new ArrayList<>(chunkIds.length);
        for (String id : chunkIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (!chunkMap.containsKey(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        List<Chunk> chunks;
        try {
            chunks = chunkRepo.listChunksById(tenantId, missing);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("missing_cnt", missing.size());
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "expand_fetch_missing_failed", f);
            chunks = new ArrayList<>();
        }

        Map<String, Boolean> found = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            chunkMap.put(chunk.getId(), chunk);
            found.put(chunk.getId(), Boolean.TRUE);
        }

        for (String id : missing) {
            if (!found.containsKey(id)) {
                chunkMap.put(id, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // merge_faq.go：FAQ 答案回填
    // ------------------------------------------------------------------

    List<SearchResult> populateFAQAnswers(ChatManage chatManage, List<SearchResult> results) {
        if (results.isEmpty() || chunkRepo == null) {
            return results;
        }

        long tenantId = chatManage != null ? chatManage.getTenantId() : 0;
        if (tenantId == 0) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "missing_tenant");
            PipelineLog.warn("Merge", "faq_enrich_skip", f);
            return results;
        }

        Map<String, List<SearchResult>> chunkResultMap = new LinkedHashMap<>();
        Map<String, Boolean> chunkIdSet = new LinkedHashMap<>();
        for (SearchResult r : results) {
            if (r == null || r.getId().isEmpty()) {
                continue;
            }
            if (!ChunkTypes.FAQ.equals(r.getChunkType())) {
                continue;
            }
            chunkResultMap.computeIfAbsent(r.getId(), k -> new ArrayList<>()).add(r);
            chunkIdSet.putIfAbsent(r.getId(), Boolean.TRUE);
        }

        if (chunkIdSet.isEmpty()) {
            return results;
        }

        List<Chunk> chunks;
        try {
            chunks = chunkRepo.listChunksById(tenantId, new ArrayList<>(chunkIdSet.keySet()));
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "faq_chunk_fetch_failed", f);
            return results;
        }

        int updated = 0;
        for (Chunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            FaqChunkMetadata meta = parseFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            String content = buildFAQAnswerContent(meta);
            if (content.isEmpty()) {
                continue;
            }
            List<SearchResult> matched = chunkResultMap.get(chunk.getId());
            if (matched == null) {
                continue;
            }
            for (SearchResult r : matched) {
                if (r == null) {
                    continue;
                }
                r.setContent(content);
                updated++;
            }
        }

        if (updated > 0) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chunk_cnt", updated);
            PipelineLog.info("Merge", "faq_content_enriched", f);
        }
        return results;
    }

    /** 对照 Chunk.FAQMetadata：解析失败/无 FAQ 字段 → null。 */
    private FaqChunkMetadata parseFaqMetadata(Chunk chunk) {
        JsonNode meta = chunk.getMetadata();
        if (meta == null || meta.isNull() || !meta.isObject()) {
            return null;
        }
        if (!meta.hasNonNull("standardQuestion") && !meta.hasNonNull("answers")
                && !meta.hasNonNull("similarQuestions")) {
            return null;
        }
        try {
            return FaqChunkMetadata.fromJson(meta);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chunk_id", chunk.getId());
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "faq_metadata_parse_failed", f);
            return null;
        }
    }

    /** 对照 buildFAQAnswerContent。 */
    static String buildFAQAnswerContent(FaqChunkMetadata meta) {
        if (meta == null) {
            return "";
        }

        String question = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        List<String> answers = new ArrayList<>(meta.answers == null ? 0 : meta.answers.size());
        if (meta.answers != null) {
            for (String ans : meta.answers) {
                String trimmed = ans == null ? "" : ans.trim();
                if (!trimmed.isEmpty()) {
                    answers.add(trimmed);
                }
            }
        }

        if (question.isEmpty() && answers.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        if (!question.isEmpty()) {
            builder.append("Q: ").append(question).append("\n");
        }
        if (!answers.isEmpty()) {
            builder.append("Answer:\n");
            for (String ans : answers) {
                builder.append("- ").append(ans).append("\n");
            }
        }
        return builder.toString().trim();
    }

    // ------------------------------------------------------------------
    // merge_history.go：历史引用过滤
    // ------------------------------------------------------------------

    /** 对照 filterHistoryResults：Jaccard ≥ 0.15 的历史引用，分数打 6 折，上限 3 条。 */
    static List<SearchResult> filterHistoryResults(ChatManage chatManage, List<SearchResult> currentResults) {
        final double minSimilarity = 0.15;
        final double historyScoreDiscount = 0.6;
        final int maxHistoryResults = 3;

        List<SearchResult> raw = SearchSupport.getSearchResultFromHistory(chatManage);
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        Map<String, Boolean> existingIDs = new LinkedHashMap<>();
        for (SearchResult r : currentResults) {
            existingIDs.put(r.getId(), Boolean.TRUE);
        }

        String query = chatManage.getRewriteQuery();
        if (query.isEmpty()) {
            query = chatManage.getQuery();
        }
        java.util.Set<String> queryTokens = SearchTextUtil.tokenizeSimple(query);

        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult r : raw) {
            if (existingIDs.containsKey(r.getId())) {
                continue;
            }
            java.util.Set<String> contentTokens = SearchTextUtil.tokenizeSimple(r.getContent());
            double sim = SearchTextUtil.jaccard(queryTokens, contentTokens);
            if (sim < minSimilarity) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("chunk_id", r.getId());
                f.put("similarity", sim);
                PipelineLog.info("Merge", "history_filter_drop", f);
                continue;
            }
            r.setMatchType(MatchTypes.HISTORY);
            r.setScore(r.getScore() * historyScoreDiscount);
            if (r.getMetadata() == null) {
                r.setMetadata(new LinkedHashMap<>());
            }
            r.getMetadata().put("history_similarity",
                    trimTrailingZeros(RetrievalObs.goFmt4(sim)));
            filtered.add(r);

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chunk_id", r.getId());
            f.put("similarity", sim);
            f.put("new_score", r.getScore());
            PipelineLog.info("Merge", "history_filter_keep", f);

            if (filtered.size() >= maxHistoryResults) {
                break;
            }
        }
        return filtered;
    }

    /** 对照 TrimRight(x, "0") 后 TrimRight(x, ".")：0.1500 → 0.15、0.0000 → ""。 */
    static String trimTrailingZeros(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    // ------------------------------------------------------------------
    // merge_overlap.go：顺序合并
    // ------------------------------------------------------------------

    /**
     * 对照 mergeSequentialChunks：可信对按位置合并；含编辑/扩展/过期内容的对
     * 落回文本匹配。入参必须已按 ChunkIndex 排序。
     */
    List<SearchResult> mergeSequentialChunks(String knowledgeID, List<SearchResult> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }

        record MergedGroup(SearchResult result, int lastIndex) {}

        List<MergedGroup> groups = new ArrayList<>();
        groups.add(new MergedGroup(chunks.get(0), chunks.get(0).getChunkIndex()));
        for (int i = 1; i < chunks.size(); i++) {
            SearchResult current = chunks.get(i);
            MergedGroup last = groups.get(groups.size() - 1);
            SearchResult lastChunk = last.result();

            MergeSituation situation = classifyMerge(lastChunk, last.lastIndex(), current);
            switch (situation) {
                case SEPARATE -> {
                    groups.add(new MergedGroup(current, current.getChunkIndex()));
                    continue;
                }
                case EXTEND -> {
                    lastChunk.setContent(appendTrustedContent(lastChunk.getContent(),
                            current.getContent(), lastChunk.getEndAt() - current.getStartAt()));
                    lastChunk.setEndAt(current.getEndAt());
                    recordMergedChild(knowledgeID, lastChunk, current, "image_merge");
                }
                case SUBSUME -> recordMergedChild(knowledgeID, lastChunk, current, "image_merge_contained");
                case JOIN_DISTINCT -> {
                    lastChunk.setContent(ChunkSearchUtil.joinChunkContent(
                            lastChunk.getContent(), current.getContent(), "\n\n"));
                    recordMergedChild(knowledgeID, lastChunk, current, "image_merge_contained");
                }
                case JOIN_TEXT -> {
                    lastChunk.setContent(ChunkSearchUtil.joinChunkContent(
                            lastChunk.getContent(), current.getContent(), "\n\n"));
                    recordMergedChild(knowledgeID, lastChunk, current, "image_merge");
                }
            }

            if (current.getChunkIndex() > last.lastIndex()) {
                last = new MergedGroup(last.result(), current.getChunkIndex());
                groups.set(groups.size() - 1, last);
            }
            if (current.getScore() > last.result().getScore()) {
                last.result().setScore(current.getScore());
            }
        }

        List<SearchResult> merged = new ArrayList<>(groups.size());
        for (MergedGroup group : groups) {
            merged.add(group.result());
        }

        merged.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return merged;
    }

    /**
     * 对照 appendTrustedContent：位置重叠优先精确裁剪（字符须逐字一致），
     * 不一致回落文本最长重叠搜索。
     */
    static String appendTrustedContent(String acc, String next, int positionOverlap) {
        SearchChunkMerge.ExactResult exact = SearchChunkMerge.appendWithExactOverlap(acc, next, positionOverlap);
        if (exact != null && exact.ok()) {
            return exact.value();
        }
        return SearchChunkMerge.appendWithOverlap(acc, next, positionOverlap);
    }

    /** 对照 chunkTrusted：坐标可信判定（长度不变量）。 */
    static boolean chunkTrusted(SearchResult chunk) {
        return chunk.getContentRevision() == 0
                && !chunk.isContentRewritten()
                && chunk.getEndAt() > chunk.getStartAt()
                && runeLen(chunk.getContent()) == chunk.getEndAt() - chunk.getStartAt();
    }

    /** 对照 mergeSituation。 */
    enum MergeSituation {
        SEPARATE, EXTEND, SUBSUME, JOIN_DISTINCT, JOIN_TEXT
    }

    /** 对照 classifyMerge：先可信位置路径，再不可信文本/顺序路径。 */
    static MergeSituation classifyMerge(SearchResult lastChunk, int lastIndex, SearchResult current) {
        if (chunkTrusted(lastChunk) && chunkTrusted(current)
                && current.getStartAt() >= lastChunk.getStartAt()) {
            if (current.getStartAt() > lastChunk.getEndAt()) {
                return MergeSituation.SEPARATE;
            }
            if (current.getEndAt() > lastChunk.getEndAt()) {
                return MergeSituation.EXTEND;
            }
            if (ChunkSearchUtil.containsChunkContent(lastChunk.getContent(), current.getContent())) {
                return MergeSituation.SUBSUME;
            }
            return MergeSituation.JOIN_DISTINCT;
        }

        boolean textContained = ChunkSearchUtil.containsChunkContent(lastChunk.getContent(), current.getContent())
                || ChunkSearchUtil.containsChunkContent(current.getContent(), lastChunk.getContent());
        boolean sequential = current.getChunkIndex() == lastIndex + 1;
        if (!textContained && !sequential) {
            return MergeSituation.SEPARATE;
        }
        return MergeSituation.JOIN_TEXT;
    }

    /** 对照 recordMergedChild：记录子块 ID + 合并 ImageInfo。 */
    private void recordMergedChild(String knowledgeID, SearchResult target, SearchResult source, String warnKey) {
        if (!containsId(target.getSubChunkId(), source.getId())) {
            appendSubChunkId(target, source.getId());
        }
        try {
            mergeImageInfo(target, source);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("knowledge_id", knowledgeID);
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", warnKey, f);
        }
    }

    /**
     * 对照 mergeImageInfo：URL 去重合并（source 的 JSON 解析失败 → 异常；
     * target 解析失败 → 整体替换为 source）。
     */
    private void mergeImageInfo(SearchResult target, SearchResult source) {
        if (source.getImageInfo().isEmpty()) {
            return;
        }

        List<com.ragagent.retrieval.domain.ImageInfo> sourceImageInfos;
        try {
            sourceImageInfos = ImageInfoMatchUtil.parseInfos(source.getImageInfo());
            if (sourceImageInfos == null) {
                throw new RuntimeException("empty image info");
            }
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("Merge", "image_unmarshal_source", f);
            throw e;
        }
        if (sourceImageInfos.isEmpty()) {
            return;
        }

        List<com.ragagent.retrieval.domain.ImageInfo> targetImageInfos = new ArrayList<>();
        if (!target.getImageInfo().isEmpty()) {
            try {
                List<com.ragagent.retrieval.domain.ImageInfo> parsed =
                        ImageInfoMatchUtil.parseInfos(target.getImageInfo());
                if (parsed != null) {
                    targetImageInfos.addAll(parsed);
                }
            } catch (RuntimeException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", e.getMessage());
                PipelineLog.warn("Merge", "image_unmarshal_target", f);
                target.setImageInfo(source.getImageInfo());
                return;
            }
        }

        targetImageInfos.addAll(sourceImageInfos);

        Map<String, Boolean> uniqueMap = new LinkedHashMap<>();
        List<com.ragagent.retrieval.domain.ImageInfo> uniqueImageInfos =
                new ArrayList<>(targetImageInfos.size());
        for (com.ragagent.retrieval.domain.ImageInfo imgInfo : targetImageInfos) {
            if (!imgInfo.getUrl().isEmpty() && !uniqueMap.containsKey(imgInfo.getUrl())) {
                uniqueMap.put(imgInfo.getUrl(), Boolean.TRUE);
                uniqueImageInfos.add(imgInfo);
            }
        }

        String mergedImageInfoJson = ImageInfoMatchUtil.marshalImageInfos(uniqueImageInfos);
        target.setImageInfo(mergedImageInfoJson);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("image_refs", uniqueImageInfos.size());
        PipelineLog.info("Merge", "image_merged", f);
    }

}
