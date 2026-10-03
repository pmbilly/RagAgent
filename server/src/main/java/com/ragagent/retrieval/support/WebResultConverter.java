package com.ragagent.retrieval.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.domain.WebSearchResult;

/**
 * 网络检索结果 → SearchResult 转换。
 *
 * <p>序号由 {@link #convert(List, SeqFunc)} 传入；缺省 seqFunc 恒返回 1。</p>
 */
public final class WebResultConverter {

    private WebResultConverter() {
    }

    /** 序号函数（可自定义）。 */
    public interface SeqFunc {
        int seq(int idx);
    }

    /** 默认 seqFunc 恒 1。 */
    public static List<SearchResult> convert(List<WebSearchResult> webResults) {
        return convert(webResults, idx -> 1);
    }

    /** 主转换。 */
    public static List<SearchResult> convert(List<WebSearchResult> webResults, SeqFunc seqFunc) {
        List<SearchResult> results = new ArrayList<>(webResults == null ? 0 : webResults.size());
        for (int i = 0; i < (webResults == null ? 0 : webResults.size()); i++) {
            WebSearchResult webResult = webResults.get(i);
            if (webResult == null) {
                continue;
            }
            String chunkId = webResult.getUrl();
            if (chunkId == null || chunkId.isEmpty()) {
                chunkId = "web_search_" + i;
            }
            String content = webResult.getTitle();
            content = appendContent(content, webResult.getSnippet());
            content = appendContent(content, webResult.getContent());

            SearchResult result = new SearchResult();
            result.setId(chunkId);
            result.setContent(content);
            // URL 作 KnowledgeID，让每条网络结果在 merge 时保持独立
            result.setKnowledgeId(chunkId);
            result.setChunkIndex(0);
            result.setKnowledgeTitle(webResult.getTitle());
            result.setStartAt(0);
            result.setEndAt(runeCount(content));
            result.setSeq(seqFunc.seq(i));
            result.setScore(0.6);
            result.setMatchType(webSearchMatchType());
            result.setSubChunkId(new ArrayList<>());
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("url", webResult.getUrl());
            metadata.put("source", webResult.getSource());
            metadata.put("title", webResult.getTitle());
            metadata.put("snippet", webResult.getSnippet());
            result.setMetadata(metadata);
            result.setChunkType("web_search");
            result.setParentChunkId("");
            result.setImageInfo("");
            result.setKnowledgeFilename("");
            result.setKnowledgeSource("web_search");

            if (webResult.getPublishedAt() != null) {
                result.getMetadata().put("published_at",
                        webResult.getPublishedAt().atZoneSameInstant(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
            }
            results.add(result);
        }
        return results;
    }

    /** 网络检索命中的 matchType 常量（= 7）。 */
    static int webSearchMatchType() {
        return 7;
    }

    /** RFC3339 输出（秒精度 UTC 'Z'）。 */
    public static String formatRfc3339(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    }

    private static String appendContent(String content, String text) {
        if (text == null || text.isEmpty()) {
            return content;
        }
        if (content != null && !content.isEmpty()) {
            return content + "\n\n" + text;
        }
        return text;
    }

    private static int runeCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
