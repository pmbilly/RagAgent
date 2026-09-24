package com.ragagent.tracing.langfuse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.rerank.RankResult;

/**
 * 模型观测的载荷/用量辅助（合并对照 Go 各 {@code langfuse_wrapper.go} 的私有工具：
 * chat 的 truncateLangfuseText、embedding 的 previewTexts/truncateRunes/approxEmbeddingUsage、
 * rerank 的 previewDocs/summarizeResults/approxRerankUsage、chat 的 convertUsage）。
 *
 * <p>省略号形态照抄：chat 的 MCP 目录裁剪切用 {@code "…"}（单字符），
 * embedding/rerank 的预览裁剪切用 {@code "..."}（三点）——Go 两处就是这么不一致的。</p>
 */
final class LangfusePayloads {

    /** chat 的 MCP 目录截断上限（对照 langfuseMCPCatalogRunes）。 */
    static final int MCP_CATALOG_RUNES = 8000;
    /** rerank 的输出条数上限（对照 langfuseRerankMaxScores）。 */
    static final int RERANK_MAX_SCORES = 50;
    /** rerank 的输入文档预览条数（对照 langfuseRerankPreviewDocs）。 */
    static final int RERANK_PREVIEW_DOCS = 8;

    private LangfusePayloads() {
    }

    /** 按码点截断（maxRunes<=0 → ""；超长追加省略号）。 */
    static String truncate(String s, int maxRunes, String ellipsis) {
        if (s == null) {
            return "";
        }
        if (maxRunes <= 0) {
            return "";
        }
        int[] runes = s.codePoints().toArray();
        if (runes.length <= maxRunes) {
            return s;
        }
        return new String(runes, 0, maxRunes) + ellipsis;
    }

    /** 对照 chat 的 truncateLangfuseText（省略号 "…"）。 */
    static String truncateChat(String s, int maxRunes) {
        return truncate(s, maxRunes, "…");
    }

    /** 对照 embedding 的 previewTexts：前 n 条、各裁到 120 码点。 */
    static List<String> previewTexts(List<String> texts, int n) {
        List<String> out = new ArrayList<>(Math.min(n, texts.size()));
        int limit = Math.min(n, texts.size());
        for (int i = 0; i < limit; i++) {
            out.add(truncate(texts.get(i), 120, "..."));
        }
        return out;
    }

    /** 对照 rerank 的 previewDocs：前 n 条文档的 {index, preview, length}。 */
    static List<Map<String, Object>> previewDocs(List<String> docs, int n) {
        int limit = Math.min(n, docs.size());
        List<Map<String, Object>> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("preview", truncate(docs.get(i), 160, "..."));
            row.put("length", codePointCount(docs.get(i)));
            out.add(row);
        }
        return out;
    }

    /** 对照 rerank 的 summarizeResults：前 n 条 {rank, index, model_score, preview}。 */
    static List<Map<String, Object>> summarizeRerankResults(List<RankResult> results,
                                                            List<String> documents, int n) {
        int limit = Math.min(n, results.size());
        List<Map<String, Object>> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RankResult result = results.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("index", result.getIndex());
            row.put("model_score", result.getRelevanceScore());
            int idx = result.getIndex();
            if (idx >= 0 && idx < documents.size()) {
                row.put("preview", truncate(documents.get(idx), 160, "..."));
            }
            out.add(row);
        }
        return out;
    }

    /** 对照 rerank 的 scoreStats：{min, max, avg}；空结果 → null。 */
    static Map<String, Object> scoreStats(List<RankResult> results) {
        if (results.isEmpty()) {
            return null;
        }
        double minScore = results.get(0).getRelevanceScore();
        double maxScore = minScore;
        double sum = 0.0;
        for (RankResult r : results) {
            if (r.getRelevanceScore() < minScore) {
                minScore = r.getRelevanceScore();
            }
            if (r.getRelevanceScore() > maxScore) {
                maxScore = r.getRelevanceScore();
            }
            sum += r.getRelevanceScore();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("min", minScore);
        stats.put("max", maxScore);
        stats.put("avg", sum / results.size());
        return stats;
    }

    /** 对照 rerank 的 avgDocChars。 */
    static int avgDocChars(List<String> documents) {
        if (documents.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String doc : documents) {
            total += codePointCount(doc);
        }
        return total / documents.size();
    }

    /** 对照 approxEmbeddingUsage：Σ(码点数/4 + 1)，空 → null。 */
    static TokenUsage approxEmbeddingUsage(List<String> texts) {
        int total = 0;
        for (String t : texts) {
            int runes = codePointCount(t);
            if (runes == 0) {
                continue;
            }
            total += runes / 4 + 1;
        }
        if (total == 0) {
            return null;
        }
        return inputTokens(total);
    }

    /** 对照 approxRerankUsage：query + 各文档的 (码点数/4 + 1)。 */
    static TokenUsage approxRerankUsage(String query, List<String> documents) {
        int total = codePointCount(query) / 4 + 1;
        for (String d : documents) {
            total += codePointCount(d) / 4 + 1;
        }
        if (total == 0) {
            return null;
        }
        return inputTokens(total);
    }

    private static TokenUsage inputTokens(int total) {
        TokenUsage usage = new TokenUsage();
        usage.input = total;
        usage.total = total;
        usage.unit = "TOKENS";
        return usage;
    }

    /** 对照 chat 的 convertUsage：三值全零 → null（不上报）；否则映射 + unit=TOKENS。 */
    static TokenUsage convertUsage(com.ragagent.llm.domain.TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        if (usage.getPromptTokens() == 0 && usage.getCompletionTokens() == 0
                && usage.getTotalTokens() == 0) {
            return null;
        }
        TokenUsage out = new TokenUsage();
        out.input = usage.getPromptTokens();
        out.output = usage.getCompletionTokens();
        out.total = usage.getTotalTokens();
        out.cacheRead = usage.getCacheReadTokens();
        out.cacheWrite = usage.getCacheWriteTokens();
        out.cacheMiss = usage.getCacheMissTokens();
        out.unit = "TOKENS";
        return out;
    }

    /** 对照 len([]rune(s))。 */
    static int codePointCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
