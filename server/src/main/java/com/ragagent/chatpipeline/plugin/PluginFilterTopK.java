package com.ragagent.chatpipeline.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.common.retrieval.SearchResult;

/**
 * FILTER_TOP_K 阶段插件（对照 Go chat_pipeline/filter_top_k.go）：
 * MergeResult &gt; RerankResult &gt; SearchResult 择一截 TopK；
 * 截断前先做确定性排序（分数降序 + knowledgeID/chunkType/chunkIndex/ID 逐级 tiebreak，
 * nil 元素沉底）。
 */
public final class PluginFilterTopK implements Plugin {

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.FILTER_TOP_K};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!chatManage.needsRetrieval()) {
            return next.next();
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("top_k", chatManage.getRerankTopK());
        in.put("merge_cnt", chatManage.getMergeResult() == null ? 0 : chatManage.getMergeResult().size());
        in.put("rerank_cnt", chatManage.getRerankResult() == null ? 0 : chatManage.getRerankResult().size());
        in.put("search_cnt", chatManage.getSearchResult() == null ? 0 : chatManage.getSearchResult().size());
        PipelineLog.info("FilterTopK", "input", in);

        int topK = chatManage.getRerankTopK();

        if (chatManage.getMergeResult() != null && !chatManage.getMergeResult().isEmpty()) {
            chatManage.setMergeResult(filterTopK(chatManage.getMergeResult(), topK));
        } else if (chatManage.getRerankResult() != null && !chatManage.getRerankResult().isEmpty()) {
            chatManage.setRerankResult(filterTopK(chatManage.getRerankResult(), topK));
        } else if (chatManage.getSearchResult() != null && !chatManage.getSearchResult().isEmpty()) {
            chatManage.setSearchResult(filterTopK(chatManage.getSearchResult(), topK));
        } else {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "no_results");
            PipelineLog.warn("FilterTopK", "skip", f);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merge_cnt", chatManage.getMergeResult() == null ? 0 : chatManage.getMergeResult().size());
        out.put("rerank_cnt", chatManage.getRerankResult() == null ? 0 : chatManage.getRerankResult().size());
        out.put("search_cnt", chatManage.getSearchResult() == null ? 0 : chatManage.getSearchResult().size());
        PipelineLog.info("FilterTopK", "output", out);
        return next.next();
    }

    private static List<SearchResult> filterTopK(List<SearchResult> searchResult, int topK) {
        sortSearchResultsDeterministically(searchResult);
        if (topK > 0 && searchResult.size() > topK) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("before", searchResult.size());
            f.put("after", topK);
            PipelineLog.info("FilterTopK", "filter", f);
            return new java.util.ArrayList<>(searchResult.subList(0, topK));
        }
        return searchResult;
    }

    /**
     * 对照 sortSearchResultsDeterministically：merge 各阶段经 map 分组后恢复全局相关性序。
     * 稳定排序（List.sort 是 TimSort，同 Go 的 SliceStable 语义）。
     * nil 元素沉底（Go 的 {@code left != nil}：非 nil 排前）。
     */
    public static void sortSearchResultsDeterministically(List<SearchResult> results) {
        results.sort((left, right) -> {
            if (left == null || right == null) {
                return left == right ? 0 : (left != null ? -1 : 1);
            }
            if (left.getScore() != right.getScore()) {
                return Double.compare(right.getScore(), left.getScore());
            }
            int c = left.getKnowledgeId().compareTo(right.getKnowledgeId());
            if (c != 0) {
                return c;
            }
            c = left.getChunkType().compareTo(right.getChunkType());
            if (c != 0) {
                return c;
            }
            if (left.getChunkIndex() != right.getChunkIndex()) {
                return Integer.compare(left.getChunkIndex(), right.getChunkIndex());
            }
            return left.getId().compareTo(right.getId());
        });
    }
}
