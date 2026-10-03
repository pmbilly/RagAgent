package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineCommon;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.support.MatchTypes;
import com.ragagent.chatpipeline.support.SearchSupport;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.support.ChunkSearchUtil;
import com.ragagent.retrieval.support.ImageInfoMatchUtil;
import com.ragagent.knowledge.support.SearchChunkMerge;
import com.ragagent.retrieval.support.SearchTextUtil;
import com.ragagent.common.web.JsonMappers;
import com.ragagent.common.pipeline.ChunkTypes;
import com.ragagent.retrieval.obs.RetrievalObs;

/**
 * CHUNK_MERGE 阶段插件。
 *
 * <h2>OnEvent 八步</h2>
 * 输入选择 → 去重 → 历史引用注入 → 父块解析 → 分组顺序合并 → FAQ 答案回填 →
 * 短上下文邻居扩展 → 扩展后再合并 → 终去重（ID+签名+部分重叠）。
 *
 * <h2>合并分类</h2>
 * SEPARATE / EXTEND（可信对，位置重叠裁剪）/ SUBSUME（可信包含）/ JOIN_DISTINCT /
 * JOIN_TEXT（不可信，纯文本匹配）。可信 = 未编辑 + 未被管线改写 + 坐标区间有效 +
 * runeLen(Content) == EndAt-StartAt（长度不变量）。
 */
public final class PluginMerge implements Plugin {

    private static final ObjectMapper JSON = JsonMappers.lenient();

    final PipelinePorts.ChunkRepository chunkRepo;
    final MergeParentOps parentOps;
    private final PipelinePorts.ChunkService chunkService; // 父块解析预留（当前未用）

    public PluginMerge(PipelinePorts.ChunkRepository chunkRepo, PipelinePorts.ChunkService chunkService) {
        this.chunkRepo = chunkRepo;
        this.chunkService = chunkService;
        this.parentOps = new MergeParentOps(this);
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
    // 输入选择 / 去重 / 历史注入
    // ------------------------------------------------------------------

    /** rerank 优先，回落按分数降序的检索结果。 */
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

    /** 带前后日志的去重（removeDuplicateResults）。 */
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

    /** 历史引用注入后重去重。 */
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

    /** KnowledgeID+ChunkType 分组 → 组内顺序合并 → 全局确定性排序。 */
    public List<SearchResult> groupAndMergeCurrentContent(List<SearchResult> results) {
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

    /** 字符串的 Unicode 码点数。 */
    public static int runeLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** prev + base + next 按序拼接，超 maxLen 按码点截断。 */
    public static String mergeOrderedContent(String prev, String base, String next, int maxLen) {
        String content = base;
        if (!prev.isEmpty()) {
            // joinChunkContent 带重叠折叠，不是裸拼接——
            // 邻居块尾部常与 base 前缀重叠（parser 滑动窗口），裸拼会重复一段且
            // 多出 "\n\n"。
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

    /** SubChunkID 追加（null 列表先建空列表再追加）。 */
    static void appendSubChunkId(SearchResult r, String id) {
        List<String> ids = r.getSubChunkId();
        List<String> next = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        next.add(id);
        r.setSubChunkId(next);
    }
    // ------------------------------------------------------------------
    // FAQ 答案回填
    // ------------------------------------------------------------------

    public List<SearchResult> resolveParentChunks(ChatManage chatManage, List<SearchResult> results) {
        return parentOps.resolveParentChunks(chatManage, results);
    }

    public List<SearchResult> expandShortContextWithNeighbors(ChatManage chatManage, List<SearchResult> results) {
        return parentOps.expandShortContextWithNeighbors(chatManage, results);
    }

    public List<SearchResult> populateFAQAnswers(ChatManage chatManage, List<SearchResult> results) {
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

    /** FAQ 元数据解析（解析失败/无 FAQ 字段 → null）。 */
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

    public static String buildFAQAnswerContent(FaqChunkMetadata meta) {
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
    // 历史引用过滤
    // ------------------------------------------------------------------

    /** Jaccard ≥ 0.15 的历史引用，分数打 6 折，上限 3 条。 */
    public static List<SearchResult> filterHistoryResults(ChatManage chatManage, List<SearchResult> currentResults) {
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

    /** 先去尾部 '0' 再去尾部 '.'：0.1500 → 0.15、0.0000 → ""。 */
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
    // 顺序合并
    // ------------------------------------------------------------------

    /**
     * 可信对按位置合并；含编辑/扩展/过期内容的对
     * 落回文本匹配。入参必须已按 ChunkIndex 排序。
     */
    public List<SearchResult> mergeSequentialChunks(String knowledgeID, List<SearchResult> chunks) {
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
     * 位置重叠优先精确裁剪（字符须逐字一致），
     * 不一致回落文本最长重叠搜索。
     */
    static String appendTrustedContent(String acc, String next, int positionOverlap) {
        SearchChunkMerge.ExactResult exact = SearchChunkMerge.appendWithExactOverlap(acc, next, positionOverlap);
        if (exact != null && exact.ok()) {
            return exact.value();
        }
        return SearchChunkMerge.appendWithOverlap(acc, next, positionOverlap);
    }

    /** 坐标可信判定（长度不变量）。 */
    public static boolean chunkTrusted(SearchResult chunk) {
        return chunk.getContentRevision() == 0
                && !chunk.isContentRewritten()
                && chunk.getEndAt() > chunk.getStartAt()
                && runeLen(chunk.getContent()) == chunk.getEndAt() - chunk.getStartAt();
    }

    public enum MergeSituation {
        SEPARATE, EXTEND, SUBSUME, JOIN_DISTINCT, JOIN_TEXT
    }

    /** 先可信位置路径，再不可信文本/顺序路径。 */
    public static MergeSituation classifyMerge(SearchResult lastChunk, int lastIndex, SearchResult current) {
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

    /** 记录子块 ID + 合并 ImageInfo。 */
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
     * URL 去重合并（source 的 JSON 解析失败 → 异常；
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
