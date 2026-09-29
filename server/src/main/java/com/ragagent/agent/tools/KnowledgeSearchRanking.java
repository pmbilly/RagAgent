package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.agent.tools.KnowledgeSearchTool.RankResult;
import com.ragagent.agent.tools.KnowledgeSearchTool.ResultWithMeta;
import com.ragagent.agent.tools.KnowledgeSearchTool.SearchResultView;

import static com.ragagent.agent.tools.KnowledgeSearchTool.nz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class KnowledgeSearchRanking {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchRanking.class);

    private final KnowledgeSearchTool tool;

    KnowledgeSearchRanking(KnowledgeSearchTool tool) {
        this.tool = tool;
    }

    List<ResultWithMeta> rerankResults(String query, List<ResultWithMeta> results) {
        if (results.isEmpty() || tool.reranker == null) {
            return results;
        }
        List<RankResult> rankResults;
        try {
            rankResults = rerankScores(query, results);
        } catch (RuntimeException e) {
            return results; // rerank 失败回落原始结果
        }
        double threshold = rerankThreshold();
        boolean preserveTop = tool.searchTargets != null && tool.searchTargets.hasRecallThresholdOverride();
        return applyModelRerankScores(results, rankResults, threshold, preserveTop);
    }

    List<RankResult> rerankScores(String query, List<ResultWithMeta> results) {
        List<String> passages = new ArrayList<>(results.size());
        for (ResultWithMeta result : results) {
            passages.add(getEnrichedPassage(result.sr));
        }
        return tool.reranker.rerank(query, passages);
    }

    /** 重排阈值：配置 &gt;0 用之，否则 0.3。 */
    double rerankThreshold() {
        return tool.config.rerankThreshold() > 0 ? tool.config.rerankThreshold() : 0.3;
    }

    /** 阈值过滤；全被滤掉时视 preserveTop/兜底分决定是否回落最高分单条。 */
    static List<RankResult> filterRerankRankResults(List<RankResult> rankResults, double threshold,
            boolean preserveTop) {
        if (rankResults == null || rankResults.isEmpty()) {
            return null;
        }
        List<RankResult> filtered = new ArrayList<>(rankResults.size());
        for (RankResult r : rankResults) {
            if (r.relevanceScore() >= threshold) {
                filtered.add(r);
            }
        }
        if (filtered.isEmpty()) {
            RankResult top = rankResults.get(0);
            for (RankResult r : rankResults.subList(1, rankResults.size())) {
                if (r.relevanceScore() > top.relevanceScore()) {
                    top = r;
                }
            }
            if (preserveTop || top.relevanceScore() >= KnowledgeSearchTool.RERANK_FALLBACK_MIN_SCORE) {
                return List.of(top);
            }
        }
        return filtered;
    }

    /** composite 打分 + 按分降序。 */
    List<ResultWithMeta> applyModelRerankScores(List<ResultWithMeta> originals, List<RankResult> rankResults,
            double threshold, boolean preserveTop) {
        List<RankResult> filtered = filterRerankRankResults(rankResults, threshold, preserveTop);
        List<ResultWithMeta> out = new ArrayList<>();
        if (filtered != null) {
            for (RankResult rr : filtered) {
                if (rr.index() < 0 || rr.index() >= originals.size()) {
                    continue;
                }
                SearchResultView copy = originals.get(rr.index()).sr.copy();
                double baseScore = copy.score;
                double modelScore = rr.relevanceScore();
                copy.score = compositeScore(copy, modelScore, baseScore);
                out.add(new ResultWithMeta(copy, originals.get(rr.index()).sourceQuery,
                        originals.get(rr.index()).queryType, originals.get(rr.index()).knowledgeBaseType));
            }
        }
        out.sort((a, b) -> Double.compare(b.sr.score, a.sr.score));
        return out;
    }

    /** 多键 + 内容签名去重；同 ID 保留最高分（与 grep 的留先不同）。 */
    static List<ResultWithMeta> deduplicateResults(List<ResultWithMeta> results) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> contentSig = new LinkedHashSet<>();
        List<ResultWithMeta> uniqueResults = new ArrayList<>();

        for (ResultWithMeta r : results) {
            List<String> keys = new ArrayList<>();
            keys.add(nz(r.sr.id));
            if (!nz(r.sr.parentChunkId).isEmpty()) {
                keys.add("parent:" + nz(r.sr.parentChunkId));
            }
            if (!nz(r.sr.knowledgeId).isEmpty()) {
                keys.add("kb:" + nz(r.sr.knowledgeId) + "#" + r.sr.chunkIndex);
            }

            boolean dup = false;
            for (String k : keys) {
                if (seen.contains(k)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }

            String sig = GrepChunksScoring.buildContentSignature(nz(r.sr.content));
            if (!sig.isEmpty() && !contentSig.add(sig)) {
                continue;
            }

            seen.addAll(keys);
            uniqueResults.add(r);
        }

        // 同 ID 不同分：留最高分。
        Map<String, ResultWithMeta> seenByID = new LinkedHashMap<>();
        for (ResultWithMeta r : uniqueResults) {
            ResultWithMeta existing = seenByID.get(nz(r.sr.id));
            if (existing != null) {
                if (r.sr.score > existing.sr.score) {
                    seenByID.put(nz(r.sr.id), r);
                }
            } else {
                seenByID.put(nz(r.sr.id), r);
            }
        }
        return new ArrayList<>(seenByID.values());
    }

    /** composite 打分：0.6 模型分 + 0.3 基础分 + 0.1 来源权重，位置先验乘子，[0,1] 截断。 */
    static double compositeScore(SearchResultView result, double modelScore, double baseScore) {
        double sourceWeight = 1.0;
        if ("web_search".equalsIgnoreCase(nz(result.knowledgeSource))) {
            sourceWeight = 0.95;
        }
        double positionPrior = 1.0;
        if (result.startAt >= 0 && result.endAt > result.startAt) {
            double positionRatio = 1.0 - result.startAt / (double) (result.endAt + 1);
            positionPrior += clampFloat(positionRatio, -0.05, 0.05);
        }
        double composite = 0.6 * modelScore + 0.3 * baseScore + 0.1 * sourceWeight;
        composite *= positionPrior;
        if (composite < 0) {
            composite = 0;
        }
        if (composite > 1) {
            composite = 1;
        }
        return composite;
    }

    /** 区间截断。 */
    static double clampFloat(double v, double minV, double maxV) {
        if (v < minV) {
            return minV;
        }
        if (v > maxV) {
            return maxV;
        }
        return v;
    }

    /**
     * MMR 增量版（maxRedundancy 缓存），与朴素版逐位一致；
     * 删除用保序 remove——与 grep 的 swap-remove 不同！
     */
    static List<ResultWithMeta> applyMMR(List<ResultWithMeta> results, int k, double lambda) {
        if (k <= 0 || results.isEmpty()) {
            return null;
        }

        List<ResultWithMeta> selected = new ArrayList<>(k);
        List<ResultWithMeta> candidates = new ArrayList<>(results);

        List<Map<String, Boolean>> tokenSets = new ArrayList<>(candidates.size());
        for (ResultWithMeta r : candidates) {
            tokenSets.add(GrepChunksScoring.tokenizeSimple(getEnrichedPassage(r.sr)));
        }

        List<Double> maxRedundancy = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            maxRedundancy.add(0.0);
        }

        while (selected.size() < k && !candidates.isEmpty()) {
            int bestIdx = 0;
            double bestScore = -1.0;

            for (int i = 0; i < candidates.size(); i++) {
                double mmr = lambda * candidates.get(i).sr.score - (1.0 - lambda) * maxRedundancy.get(i);
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }

            ResultWithMeta chosen = candidates.get(bestIdx);
            Map<String, Boolean> chosenTokens = tokenSets.get(bestIdx);
            selected.add(chosen);
            candidates.remove(bestIdx);
            tokenSets.remove(bestIdx);
            maxRedundancy.remove(bestIdx);

            for (int i = 0; i < candidates.size(); i++) {
                maxRedundancy.set(i,
                        Math.max(maxRedundancy.get(i), GrepChunksScoring.jaccard(tokenSets.get(i), chosenTokens)));
            }
        }

        return selected;
    }

    /** 重排 passage：拼接图片 caption/ocr 文本。 */
    static String getEnrichedPassage(SearchResultView result) {
        if (nz(result.imageInfo).isEmpty()) {
            return nz(result.content);
        }
        JsonNode arr;
        try {
            arr = KnowledgeSearchTool.RecordingSupportHolder.MAPPER.readTree(result.imageInfo);
        } catch (java.io.IOException e) {
            return nz(result.content);
        }
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return nz(result.content);
        }
        List<String> imageTexts = new ArrayList<>();
        for (JsonNode img : arr) {
            String caption = img.path("caption").asText("");
            if (!caption.isEmpty()) {
                imageTexts.add("Image Caption: " + caption);
            }
            String ocr = img.path("ocr_text").asText("");
            if (!ocr.isEmpty()) {
                imageTexts.add("Image Text: " + ocr);
            }
        }
        if (imageTexts.isEmpty()) {
            return nz(result.content);
        }
        String combined = nz(result.content);
        if (!combined.isEmpty()) {
            combined += "\n\n";
        }
        return combined + String.join("\n", imageTexts);
    }
}
