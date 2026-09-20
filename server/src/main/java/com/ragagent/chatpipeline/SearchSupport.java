package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.searchutil.SearchChunkMerge;
import com.ragagent.searchutil.SearchTextUtil;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * search.go 的包级函数（对照 chat_pipeline/search.go:165-324）：
 * 历史引用提取、去重、部分重叠移除、分数采样日志。
 * search.go 的 plugin 主体在 {@link PluginSearch}；removePartialOverlaps 里的
 * 归一化/包含/重叠率全部走 searchutil（4.4 已对齐 Go）。
 */
public final class SearchSupport {

    private SearchSupport() {}

    /**
     * 对照 getSearchResultFromHistory：从最近一轮带引用的历史里取引用，
     * 全部标 MatchTypeHistory（int 常量 3，对照 types.MatchTypeHistory）。
     */
    static List<SearchResult> getSearchResultFromHistory(ChatManage chatManage) {
        List<History> history = chatManage.getHistory();
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            List<SearchResult> refs = history.get(i).getKnowledgeReferences();
            if (refs != null && !refs.isEmpty()) {
                for (SearchResult reference : refs) {
                    reference.setMatchType(MatchTypes.HISTORY);
                }
                return refs;
            }
        }
        return null;
    }

    /**
     * 对照 removeDuplicateResults：只按 chunk ID 去重（共享 ParentChunkID 不算重复），
     * 加内容签名去重（searchutil.BuildContentSignature）。
     */
    static List<SearchResult> removeDuplicateResults(List<SearchResult> results) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Map<String, String> contentSig = new LinkedHashMap<>(); // sig → 首个 chunk ID
        List<SearchResult> uniqueResults = new ArrayList<>();
        if (results == null) {
            return null;
        }
        for (SearchResult r : results) {
            if (seen.containsKey(r.getId())) {
                continue;
            }
            String sig = SearchTextUtil.buildContentSignature(r.getContent());
            if (sig != null && !sig.isEmpty()) {
                String firstChunk = contentSig.get(sig);
                if (firstChunk != null) {
                    continue;
                }
                contentSig.put(sig, r.getId());
            }
            seen.put(r.getId(), Boolean.TRUE);
            uniqueResults.add(r);
        }
        return uniqueResults;
    }

    /**
     * 对照 removePartialOverlaps：内容大范围被高分块包含的块（跨知识源）移除。
     * 两个阈值：字面包含（归一化后）或 token 重叠系数 ≥ 0.85。
     * 输入必须已经过 ID/签名去重；分数低者被移除，平分按内容长度（长者保留）。
     */
    static List<SearchResult> removePartialOverlaps(List<SearchResult> results) {
        final double overlapThreshold = 0.85;

        if (results == null || results.size() <= 1) {
            return results;
        }

        record NormEntry(String norm, SearchResult result) {}

        List<NormEntry> entries = new ArrayList<>(results.size());
        for (SearchResult r : results) {
            entries.add(new NormEntry(SearchTextUtil.normalizeContent(r.getContent()), r));
        }

        boolean[] removed = new boolean[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            if (removed[i]) {
                continue;
            }
            for (int j = i + 1; j < entries.size(); j++) {
                if (removed[j]) {
                    continue;
                }

                NormEntry a = entries.get(i);
                NormEntry b = entries.get(j);

                int shortIdx = i;
                int longIdx = j;
                if (a.norm().length() > b.norm().length()) {
                    shortIdx = j;
                    longIdx = i;
                }

                boolean contained = SearchTextUtil.isContentContained(
                        entries.get(shortIdx).norm(), entries.get(longIdx).norm());

                if (!contained) {
                    double ratio = SearchTextUtil.contentOverlapRatio(
                            entries.get(shortIdx).result().getContent(),
                            entries.get(longIdx).result().getContent());
                    if (ratio < overlapThreshold) {
                        continue;
                    }
                }

                int victim = shortIdx;
                if (entries.get(shortIdx).result().getScore() > entries.get(longIdx).result().getScore()) {
                    victim = longIdx;
                }
                removed[victim] = true;

                int keptIdx = victim == i ? j : i;
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("kept_id", entries.get(keptIdx).result().getId());
                fields.put("dropped_id", entries.get(victim).result().getId());
                fields.put("contained", contained);
                PipelineLog.info("Merge", "partial_overlap_drop", fields);
            }
        }

        List<SearchResult> out = new ArrayList<>(results.size());
        for (int i = 0; i < entries.size(); i++) {
            if (!removed[i]) {
                out.add(entries.get(i).result());
            }
        }
        return out;
    }

    /** 对照 logSearchScoreSample：前 8 条分数采样日志（观测面，非契约）。 */
    static void logSearchScoreSample(String action, List<SearchResult> results) {
        final int maxLogRows = 8;
        int limit = results == null ? 0 : Math.min(maxLogRows, results.size());
        for (int i = 0; i < limit; i++) {
            SearchResult r = results.get(i);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("index", i);
            fields.put("chunk_id", r.getId());
            fields.put("score", RetrievalObs.goFmt4(r.getScore()));
            fields.put("match_type", r.getMatchType());
            PipelineLog.info("Search", action, fields);
        }
        int total = results == null ? 0 : results.size();
        if (total > limit) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("total", total);
            fields.put("logged", limit);
            fields.put("truncated", total - limit);
            PipelineLog.info("Search", action + "_summary", fields);
        }
    }

    /** SearchChunkMerge 引用占位（保持 searchutil 依赖集中可见）。 */
    static final class MergeRef {
        static final Class<?> REF = SearchChunkMerge.class;
    }
}
