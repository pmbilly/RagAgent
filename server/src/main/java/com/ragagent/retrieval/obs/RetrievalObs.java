package com.ragagent.retrieval.obs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.retrieval.domain.SearchResult;

/**
 * 检索观测的纯函数族（对照 Go internal/tracing/langfuse/retrieval_obs.go 的
 * TruncateRunes / SummarizeSearchResults / SummarizeRankScores / SummarizePassagePreviews）。
 *
 * <p>4.6a 的 langfuse seam 只收了引擎三个调用点；chat_pipeline 还消费这四个纯函数
 * （rerank.go / memory_recall.go / search.go 的 span 输入与 query_preview）。
 * 它们是纯本地计算、无 IO，放本包避免为纯函数动 langfuse seam。
 * 产出只进 span（Java 侧恒 no-op），行为契约由 rerank 实录组经 span 输入钉住。</p>
 */
public final class RetrievalObs {

    public static final int DEFAULT_HIT_PREVIEW_LIMIT = 25;

    private RetrievalObs() {}

    /** 对照 TruncateRunes：截到 maxRunes 个码点，截断时追加 "..."。 */
    public static String truncateRunes(String s, int maxRunes) {
        if (maxRunes <= 0) {
            return "";
        }
        if (s == null) {
            return "";
        }
        int len = s.codePointCount(0, s.length());
        if (len <= maxRunes) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxRunes)) + "...";
    }

    /**
     * 对照 SummarizeSearchResults：重排后的紧凑预览（分数降序，ID 决序；
     * metadata 里的 base_score/model_score/faq_* 透出）。
     */
    public static Map<String, Object> summarizeSearchResults(List<SearchResult> results, int limit) {
        int effective = limit <= 0 ? DEFAULT_HIT_PREVIEW_LIMIT : limit;
        Map<String, Object> out = new LinkedHashMap<>();
        int count = results == null ? 0 : results.size();
        out.put("count", count);
        out.put("top_hits", new ArrayList<Map<String, Object>>());
        if (count == 0) {
            return out;
        }

        List<SearchResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> {
            if (a.getScore() != b.getScore()) {
                return Double.compare(b.getScore(), a.getScore());
            }
            return a.getId().compareTo(b.getId());
        });

        List<Map<String, Object>> hits = new ArrayList<>(Math.min(effective, sorted.size()));
        for (int i = 0; i < sorted.size() && i < effective; i++) {
            SearchResult sr = sorted.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("chunk_id", sr.getId());
            item.put("knowledge_id", sr.getKnowledgeId());
            item.put("knowledge_title", sr.getKnowledgeTitle());
            item.put("composite_score", goFmt4(sr.getScore()));
            item.put("match_type", sr.getMatchType());
            item.put("chunk_type", sr.getChunkType());
            item.put("preview", truncateRunes(sr.getContent(), 160));
            if (sr.getMetadata() != null) {
                String base = sr.getMetadata().get("base_score");
                if (base != null) {
                    item.put("retrieval_score", base);
                }
                String model = sr.getMetadata().get("model_score");
                if (model != null) {
                    item.put("model_score", model);
                }
                String boosted = sr.getMetadata().get("faq_boosted");
                if (boosted != null) {
                    item.put("faq_boosted", boosted);
                }
                String orig = sr.getMetadata().get("faq_original_score");
                if (orig != null) {
                    item.put("faq_original_score", orig);
                }
            }
            hits.add(item);
        }
        out.put("top_hits", hits);
        if (sorted.size() > effective) {
            out.put("truncated", sorted.size() - effective);
        }
        return out;
    }

    /** 对照 SummarizeRankScores：超过 limit 时截前 limit 行。 */
    public static List<Map<String, Object>> summarizeRankScores(List<Map<String, Object>> results, int limit) {
        int effective = limit <= 0 ? DEFAULT_HIT_PREVIEW_LIMIT : limit;
        if (results == null || results.size() <= effective) {
            return results;
        }
        return new ArrayList<>(results.subList(0, effective));
    }

    /** 对照 SummarizePassagePreviews：与候选对齐的段落预览行。 */
    public static List<Map<String, Object>> summarizePassagePreviews(
            List<SearchResult> candidates, List<String> passages, int limit) {
        int effective = limit <= 0 ? DEFAULT_HIT_PREVIEW_LIMIT : limit;
        int n = candidates == null ? 0 : candidates.size();
        if (passages != null && passages.size() < n) {
            n = passages.size();
        }
        if (effective < n) {
            n = effective;
        }
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SearchResult sr = candidates.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("chunk_id", sr.getId());
            row.put("knowledge_id", sr.getKnowledgeId());
            row.put("knowledge_title", sr.getKnowledgeTitle());
            row.put("retrieval_score", goFmt4(sr.getScore()));
            row.put("match_type", sr.getMatchType());
            row.put("preview", truncateRunes(passages.get(i), 160));
            out.add(row);
        }
        return out;
    }

    /** Go 的 fmt.Sprintf("%.4f")（四舍五入到 4 位小数，toFixed 语义）。 */
    public static String goFmt4(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /**
     * 对照 SummarizeRetrieveOutput（retrieval_obs.go L24-61）：检索 span 的输出——
     * 多组命中汇总（total/vector/keyword + by_retriever 逐组计数）+ 前 25 条命中预览。
     *
     * <p>空输入返回全零（{@code by_retriever} 空数组、{@code top_hits} 为 null——
     * 照抄 Go 的 nil slice 语义）。</p>
     */
    public static Map<String, Object> summarizeRetrieveOutput(
            List<com.ragagent.retrieval.engine.PgVectorRetrieveRepository.RetrieveResult> results) {
        int groupCount = results == null ? 0 : results.size();
        int totalHits = 0;
        int vectorHits = 0;
        int keywordHits = 0;
        List<Map<String, Object>> byRetriever = new ArrayList<>();
        List<com.ragagent.retrieval.engine.PgVectorRetrieveRepository.IndexHit> all = new ArrayList<>();
        if (results != null) {
            for (com.ragagent.retrieval.engine.PgVectorRetrieveRepository.RetrieveResult rr : results) {
                if (rr == null) {
                    continue;
                }
                int count = rr.results() == null ? 0 : rr.results().size();
                totalHits += count;
                if ("vector".equals(rr.retrieverType())) {
                    vectorHits += count;
                } else {
                    keywordHits += count;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("engine", rr.retrieverEngineType());
                row.put("retriever", rr.retrieverType());
                row.put("count", count);
                byRetriever.add(row);
                if (rr.results() != null) {
                    all.addAll(rr.results());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_hits", totalHits);
        out.put("vector_hits", vectorHits);
        out.put("keyword_hits", keywordHits);
        out.put("group_count", groupCount);
        out.put("by_retriever", byRetriever);
        out.put("top_hits", summarizeIndexHits(all, DEFAULT_HIT_PREVIEW_LIMIT));
        return out;
    }

    /** 对照 summarizeIndexHits：分数降序、chunk_id 决序，截前 limit 条（空 → null）。 */
    public static List<Map<String, Object>> summarizeIndexHits(
            List<com.ragagent.retrieval.engine.PgVectorRetrieveRepository.IndexHit> hits, int limit) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        List<com.ragagent.retrieval.engine.PgVectorRetrieveRepository.IndexHit> sorted =
                new ArrayList<>(hits);
        sorted.sort((a, b) -> {
            if (a.score != b.score) {
                return Double.compare(b.score, a.score);
            }
            return a.chunkId.compareTo(b.chunkId);
        });
        int n = Math.min(limit, sorted.size());
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            com.ragagent.retrieval.engine.PgVectorRetrieveRepository.IndexHit hit = sorted.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("chunk_id", hit.chunkId);
            row.put("knowledge_id", hit.knowledgeId);
            row.put("knowledge_base_id", hit.knowledgeBaseId);
            row.put("score", goFmt4(hit.score));
            row.put("match_type", hit.matchType);
            row.put("preview", truncateRunes(hit.content, 160));
            out.add(row);
        }
        return out;
    }
}
