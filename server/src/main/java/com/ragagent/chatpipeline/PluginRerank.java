package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.domain.ImageInfo;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.searchutil.ImageInfoMatchUtil;
import com.ragagent.searchutil.SearchTextUtil;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.knowledge.domain.DocumentChunkMetadata;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.common.web.JsonMappers;

/**
 * CHUNK_RERANK 阶段插件（对照 Go chat_pipeline/rerank.go 全文）。
 *
 * <h2>编排（实录组 rerank ×12 钉住）</h2>
 * <ul>
 *   <li>空段落跳过 → API 失败回退原检索结果（api_error_fallback）→
 *       无结果且阈值 &gt;0.3 降为 ×0.7（不低于 0.3）重试 →
 *       全低于阈值时 top1 兜底（分数 ≥ rerankFallbackMinScore：范围覆写 0 / 常态 0.15）。</li>
 *   <li>合成评分 compositeScore = 0.6×model + 0.3×base + 0.1×sourceWeight（web 0.95），
 *       clamp [0,1]；FAQ 优先时 faq 加成 ×boost 封顶 1.0。</li>
 *   <li>MMR（lambda 0.7）多样性选 topK；token 集并发预计算。</li>
 * </ul>
 *
 * <p>段落清洗 cleanPassageForRerank 的 14 条正则族逐条对照 Go（RE2 → Java 语义等价，
 * `$`→`\z`、`\s` 显式类按 §9 教训处理；实录组 rerank_clean ×20 钉住）。</p>
 */
public final class PluginRerank implements Plugin {

    private final PipelinePorts.ModelService modelService;

    public PluginRerank(PipelinePorts.ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHUNK_RERANK};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!chatManage.needsRetrieval()) {
            return next.next();
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("candidate_cnt", chatManage.getSearchResult() == null ? 0 : chatManage.getSearchResult().size());
        in.put("rerank_model", chatManage.getRerankModelId());
        in.put("rerank_thresh", chatManage.getRerankThreshold());
        in.put("rewrite_query", chatManage.getRewriteQuery());
        PipelineLog.info("Rerank", "input", in);

        if (chatManage.getSearchResult() == null || chatManage.getSearchResult().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "empty_search_result");
            PipelineLog.info("Rerank", "skip", f);
            return next.next();
        }
        if (chatManage.getRerankModelId().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "empty_model_id");
            PipelineLog.warn("Rerank", "skip", f);
            return next.next();
        }

        Reranker rerankModel;
        try {
            rerankModel = modelService.getRerankModel(chatManage.getRerankModelId());
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("model_id", chatManage.getRerankModelId());
            f.put("error", e.getMessage());
            PipelineLog.error("Rerank", "get_model", f);
            return PluginError.GET_RERANK_MODEL.withError(e);
        }

        // 组 passages（空段落跳过）
        List<String> passages = new ArrayList<>();
        List<SearchResult> candidatesToRerank = new ArrayList<>();
        for (SearchResult result : chatManage.getSearchResult()) {
            String passage = getEnrichedPassage(result);
            if (passage.trim().isEmpty()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("chunk_id", result.getId());
                PipelineLog.info("Rerank", "empty_passage_skip", f);
                continue;
            }
            passages.add(passage);
            candidatesToRerank.add(result);
        }

        var passagesPreview = RetrievalObs.summarizePassagePreviews(candidatesToRerank, passages, 25);
        Map<String, Object> spanInput = new LinkedHashMap<>();
        spanInput.put("query", chatManage.getRewriteQuery());
        spanInput.put("candidate_count", candidatesToRerank.size());
        spanInput.put("rerank_model_id", chatManage.getRerankModelId());
        spanInput.put("threshold", chatManage.getRerankThreshold());
        spanInput.put("rerank_top_k", chatManage.getRerankTopK());
        spanInput.put("faq_priority", chatManage.isFaqPriorityEnabled());
        spanInput.put("faq_score_boost", chatManage.getFaqScoreBoost());
        spanInput.put("passages_preview", passagesPreview);
        Map<String, Object> spanMeta = new LinkedHashMap<>();
        spanMeta.put("session_id", chatManage.getSessionId());
        var rerankSpan = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("rerank", spanInput, spanMeta));

        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("total_cnt", chatManage.getSearchResult().size());
        bp.put("candidate_cnt", candidatesToRerank.size());
        PipelineLog.info("Rerank", "build_passages", bp);

        List<RankResult> rerankResp = new ArrayList<>();
        List<RankResult> rawRerankResp = new ArrayList<>();
        boolean thresholdDegraded = false;
        Map<String, Object> spanOutput = new LinkedHashMap<>();

        if (!candidatesToRerank.isEmpty()) {
            double originalThreshold = chatManage.getRerankThreshold();
            List<RankResult> first;
            try {
                first = rerank(chatManage, rerankModel, chatManage.getRewriteQuery(), passages, candidatesToRerank);
            } catch (RuntimeException rerankErr) {
                // API 失败 → 回退原检索结果
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", rerankErr.getMessage());
                f.put("candidate_cnt", candidatesToRerank.size());
                PipelineLog.warn("Rerank", "api_error_fallback", f);
                chatManage.setSearchResult(new ArrayList<>(candidatesToRerank));
                spanOutput.put("stage", "api_error_fallback");
                spanOutput.put("candidate_count", candidatesToRerank.size());
                spanOutput.put("error", rerankErr.getMessage());
                rerankSpan.finish(spanOutput, null, null);
                return next.next();
            }
            if (first != null) {
                rerankResp = first;
            }
            rawRerankResp = new ArrayList<>(rerankResp);

            // 无结果且阈值足够高 → 降阈值重试
            if (rerankResp.isEmpty() && originalThreshold > 0.3) {
                thresholdDegraded = true;
                double degradedThreshold = originalThreshold * 0.7;
                if (degradedThreshold < 0.3) {
                    degradedThreshold = 0.3;
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("original", originalThreshold);
                f.put("degraded", degradedThreshold);
                f.put("candidate_cnt", candidatesToRerank.size());
                f.put("reason", "no results above original threshold, retrying with lower threshold");
                PipelineLog.warn("Rerank", "threshold_degrade", f);
                chatManage.setRerankThreshold(degradedThreshold);
                List<RankResult> second;
                try {
                    second = rerank(chatManage, rerankModel, chatManage.getRewriteQuery(), passages, candidatesToRerank);
                } catch (RuntimeException rerankErr) {
                    chatManage.setRerankThreshold(originalThreshold);
                    Map<String, Object> fw = new LinkedHashMap<>();
                    fw.put("error", rerankErr.getMessage());
                    fw.put("candidate_cnt", candidatesToRerank.size());
                    PipelineLog.warn("Rerank", "api_error_fallback", fw);
                    chatManage.setSearchResult(new ArrayList<>(candidatesToRerank));
                    spanOutput.put("stage", "api_error_fallback");
                    spanOutput.put("candidate_count", candidatesToRerank.size());
                    spanOutput.put("threshold_degraded", true);
                    spanOutput.put("error", rerankErr.getMessage());
                    rerankSpan.finish(spanOutput, null, null);
                    return next.next();
                }
                chatManage.setRerankThreshold(originalThreshold);
                if (second != null) {
                    rerankResp = second;
                }
                rawRerankResp = new ArrayList<>(rerankResp);
            }
        }

        Map<String, Object> mr = new LinkedHashMap<>();
        mr.put("result_cnt", rerankResp.size());
        PipelineLog.info("Rerank", "model_response", mr);

        logRerankInputScoreSample(chatManage.getSearchResult());

        for (int i = 0; i < chatManage.getSearchResult().size(); i++) {
            SearchResult sr = chatManage.getSearchResult().get(i);
            if (sr.getMetadata() == null) {
                sr.setMetadata(new LinkedHashMap<>());
            }
        }
        List<SearchResult> reranked = new ArrayList<>(rerankResp.size());

        for (RankResult rr : rerankResp) {
            if (rr.getIndex() >= candidatesToRerank.size()) {
                continue;
            }
            SearchResult sr = candidatesToRerank.get(rr.getIndex());
            double base = sr.getScore();
            sr.getMetadata().put("base_score", RetrievalObs.goFmt4(base));
            double modelScore = rr.getRelevanceScore();
            sr.getMetadata().put("model_score", RetrievalObs.goFmt4(modelScore));
            sr.setScore(compositeScore(sr, modelScore, base));

            // FAQ 加成
            if (chatManage.isFaqPriorityEnabled() && chatManage.getFaqScoreBoost() > 1.0
                    && ChunkTypes.FAQ.equals(sr.getChunkType())) {
                double originalScore = sr.getScore();
                sr.setScore(Math.min(sr.getScore() * chatManage.getFaqScoreBoost(), 1.0));
                sr.getMetadata().put("faq_boosted", "true");
                sr.getMetadata().put("faq_original_score", RetrievalObs.goFmt4(originalScore));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("chunk_id", sr.getId());
                f.put("original_score", RetrievalObs.goFmt4(originalScore));
                f.put("boosted_score", RetrievalObs.goFmt4(sr.getScore()));
                f.put("boost_factor", chatManage.getFaqScoreBoost());
                PipelineLog.info("Rerank", "faq_boost", f);
            }

            reranked.add(sr);
        }

        int k = Math.min(reranked.size(), Math.max(1, chatManage.getRerankTopK()));
        List<SearchResult> finalResults = applyMMR(chatManage, reranked, k, 0.7);
        chatManage.setRerankResult(finalResults);

        int topN = Math.min(3, reranked.size());
        for (int i = 0; i < topN; i++) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("rank", i + 1);
            f.put("chunk_id", reranked.get(i).getId());
            f.put("base_score", reranked.get(i).getMetadata().get("base_score"));
            f.put("final_score", RetrievalObs.goFmt4(reranked.get(i).getScore()));
            PipelineLog.info("Rerank", "composite_top", f);
        }

        if (chatManage.getRerankResult() == null || chatManage.getRerankResult().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("filtered_cnt", 0);
            PipelineLog.warn("Rerank", "output", f);
            spanOutput = buildRerankSpanOutput(candidatesToRerank, passages, rawRerankResp,
                    reranked, null, chatManage, thresholdDegraded);
            rerankSpan.finish(spanOutput, null, null);
            return PluginError.SEARCH_NOTHING;
        }

        spanOutput = buildRerankSpanOutput(candidatesToRerank, passages, rawRerankResp,
                reranked, chatManage.getRerankResult(), chatManage, thresholdDegraded);
        rerankSpan.finish(spanOutput, null, null);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("filtered_cnt", chatManage.getRerankResult().size());
        PipelineLog.info("Rerank", "output", f);
        return next.next();
    }

    /** 对照 buildRerankSpanOutput（观测面）。 */
    private static Map<String, Object> buildRerankSpanOutput(
            List<SearchResult> candidates, List<String> passages, List<RankResult> modelScores,
            List<SearchResult> composite, List<SearchResult> finalResults, ChatManage chatManage,
            boolean thresholdDegraded) {
        List<Map<String, Object>> modelRows = new ArrayList<>(modelScores.size());
        for (int i = 0; i < modelScores.size(); i++) {
            RankResult rr = modelScores.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("index", rr.getIndex());
            row.put("model_score", rr.getRelevanceScore());
            if (rr.getIndex() >= 0 && rr.getIndex() < candidates.size()) {
                SearchResult cand = candidates.get(rr.getIndex());
                row.put("chunk_id", cand.getId());
                row.put("knowledge_id", cand.getKnowledgeId());
                row.put("knowledge_title", cand.getKnowledgeTitle());
                row.put("match_type", cand.getMatchType());
                row.put("retrieval_score", cand.getScore());
                if (rr.getIndex() < passages.size()) {
                    row.put("preview", RetrievalObs.truncateRunes(passages.get(rr.getIndex()), 160));
                }
            }
            modelRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidate_count", candidates.size());
        out.put("model_result_count", modelScores.size());
        out.put("composite_count", composite.size());
        out.put("final_count", finalResults == null ? 0 : finalResults.size());
        out.put("threshold", chatManage.getRerankThreshold());
        out.put("rerank_top_k", chatManage.getRerankTopK());
        out.put("threshold_degraded", thresholdDegraded);
        out.put("model_scores", RetrievalObs.summarizeRankScores(modelRows, 50));
        out.put("composite_results", RetrievalObs.summarizeSearchResults(composite, 25));
        out.put("final_results", RetrievalObs.summarizeSearchResults(finalResults, 25));
        if (modelScores.size() > 50) {
            out.put("model_scores_truncated", modelScores.size() - 50);
        }
        return out;
    }

    /** 对照 rerank：清洗空段 → 模型调用 → 阈值过滤 → top1 兜底。失败抛异常。 */
    private List<RankResult> rerank(ChatManage chatManage, Reranker rerankModel, String query,
                                    List<String> passages, List<SearchResult> candidates) {
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("query_variant", query);
        mc.put("passages", passages.size());
        PipelineLog.info("Rerank", "model_call", mc);

        List<String> cleanPassages = new ArrayList<>();
        List<SearchResult> cleanCandidates = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            String p = passages.get(i);
            if (!p.trim().isEmpty()) {
                cleanPassages.add(p);
                if (i < candidates.size()) {
                    cleanCandidates.add(candidates.get(i));
                }
            }
        }
        if (cleanPassages.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "all_passages_empty");
            PipelineLog.info("Rerank", "model_call_skip", f);
            return null;
        }
        List<String> usePassages = cleanPassages;
        List<SearchResult> useCandidates = cleanCandidates;

        List<RankResult> rerankResp;
        try {
            rerankResp = rerankModel.rerank(query, usePassages);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("query_variant", query);
            f.put("error", e.getMessage());
            PipelineLog.error("Rerank", "model_call", f);
            throw e;
        }
        if (rerankResp == null) {
            rerankResp = new ArrayList<>();
        }

        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("threshold", chatManage.getRerankThreshold());
        PipelineLog.info("Rerank", "threshold", tf);

        int logged = Math.min(5, rerankResp.size());
        for (int i = 0; i < logged; i++) {
            RankResult rr = rerankResp.get(i);
            if (rr.getIndex() < useCandidates.size()) {
                SearchResult cand = useCandidates.get(rr.getIndex());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("rank", i + 1);
                f.put("score", rr.getRelevanceScore());
                f.put("chunk_id", cand.getId());
                f.put("match_type", cand.getMatchType());
                f.put("chunk_type", cand.getChunkType());
                f.put("content_len", cand.getContent().length());
                PipelineLog.info("Rerank", "top_score", f);
            }
        }
        if (rerankResp.size() > logged) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("total", rerankResp.size());
            f.put("logged", logged);
            f.put("truncated", rerankResp.size() - logged);
            PipelineLog.info("Rerank", "top_score_summary", f);
        }

        // 阈值过滤
        List<RankResult> rankFilter = new ArrayList<>();
        for (RankResult result : rerankResp) {
            if (result.getIndex() >= useCandidates.size()) {
                continue;
            }
            if (result.getRelevanceScore() >= chatManage.getRerankThreshold()) {
                rankFilter.add(result);
            }
        }

        // top1 兜底（范围覆写时最小分 0，常态 0.15）
        double fallbackMinScore = rerankFallbackMinScore(chatManage.getSearchTargets());
        if (rankFilter.isEmpty() && !rerankResp.isEmpty()
                && rerankResp.get(0).getRelevanceScore() >= fallbackMinScore) {
            rankFilter = new ArrayList<>(rerankResp.subList(0, 1));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "all_below_threshold");
            f.put("threshold", chatManage.getRerankThreshold());
            f.put("top_score", rerankResp.get(0).getRelevanceScore());
            PipelineLog.info("Rerank", "fallback_top1", f);
        } else if (rankFilter.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "top_score_too_low");
            f.put("threshold", chatManage.getRerankThreshold());
            f.put("top_score", safeTopScore(rerankResp));
            PipelineLog.info("Rerank", "fallback_skip", f);
        }

        return rankFilter;
    }

    /** 对照 rerankFallbackMinScore。 */
    static double rerankFallbackMinScore(List<SearchTarget> searchTargets) {
        if (new SearchTarget.SearchTargets(searchTargets).hasRecallThresholdOverride()) {
            return 0;
        }
        return 0.15;
    }

    static double safeTopScore(List<RankResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        return results.get(0).getRelevanceScore();
    }

    /** 对照 compositeScore。 */
    static double compositeScore(SearchResult sr, double modelScore, double baseScore) {
        double sourceWeight;
        switch (sr.getKnowledgeSource() == null ? "" : sr.getKnowledgeSource().toLowerCase(java.util.Locale.ROOT)) {
            case "web_search" -> sourceWeight = 0.95;
            default -> sourceWeight = 1.0;
        }
        double composite = 0.6 * modelScore + 0.3 * baseScore + 0.1 * sourceWeight;
        if (composite < 0) {
            composite = 0;
        }
        if (composite > 1) {
            composite = 1;
        }
        return composite;
    }

    /** 对照 applyMMR：预计算 token 集（并发）→ 迭代选 k。 */
    static List<SearchResult> applyMMR(ChatManage chatManage, List<SearchResult> results, int k, double lambda) {
        if (k <= 0 || results.isEmpty()) {
            return null;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("lambda", lambda);
        f.put("k", k);
        f.put("candidates", results.size());
        PipelineLog.info("Rerank", "mmr_start", f);

        List<Set<String>> allTokenSets = PipelineCommon.parallelMap(results, 0,
                (BiFunction<Integer, SearchResult, Set<String>>) (i, r) ->
                        SearchTextUtil.tokenizeSimple(getEnrichedPassage(r)));

        List<SearchResult> selected = new ArrayList<>(k);
        List<Set<String>> selectedTokenSets = new ArrayList<>(k);
        Set<Integer> selectedIndices = new HashSet<>();

        while (selected.size() < k && selectedIndices.size() < results.size()) {
            int bestIdx = -1;
            double bestScore = -1.0;

            for (int i = 0; i < results.size(); i++) {
                if (selectedIndices.contains(i)) {
                    continue;
                }
                SearchResult r = results.get(i);
                double relevance = r.getScore();
                double redundancy = 0.0;
                for (Set<String> selTokens : selectedTokenSets) {
                    double sim = SearchTextUtil.jaccard(allTokenSets.get(i), selTokens);
                    if (sim > redundancy) {
                        redundancy = sim;
                    }
                }
                double mmr = lambda * relevance - (1.0 - lambda) * redundancy;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }

            if (bestIdx < 0) {
                break;
            }
            selected.add(results.get(bestIdx));
            selectedTokenSets.add(allTokenSets.get(bestIdx));
            selectedIndices.add(bestIdx);
        }

        double avgRed = 0.0;
        if (selected.size() > 1) {
            int pairs = 0;
            for (int i = 0; i < selectedTokenSets.size(); i++) {
                for (int j = i + 1; j < selectedTokenSets.size(); j++) {
                    avgRed += SearchTextUtil.jaccard(selectedTokenSets.get(i), selectedTokenSets.get(j));
                    pairs++;
                }
            }
            if (pairs > 0) {
                avgRed /= pairs;
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("selected", selected.size());
        d.put("avg_redundancy", RetrievalObs.goFmt4(avgRed));
        PipelineLog.info("Rerank", "mmr_done", d);
        return selected;
    }

    // ------------------------------------------------------------------
    // 段落清洗（rerank.go:540-642）
    // ------------------------------------------------------------------

    private static final Pattern RE_CODE_BLOCK =
            Pattern.compile("(?s)```[^\\r\\n]*\\r?\\n(.*?)\\r?\\n?```");
    private static final Pattern RE_LATEX_BLOCK = Pattern.compile("(?s)\\$\\$(.*?)\\$\\$");
    private static final Pattern RE_HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern RE_LINKED_IMAGE = Pattern.compile(
            "\\[!\\[([^\\]]*)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)\\]"
                    + "\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)");
    private static final Pattern RE_MARKDOWN_IMAGE =
            Pattern.compile("!\\[[^\\]]*\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)");
    private static final Pattern RE_MARKDOWN_LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)");
    private static final Pattern RE_RAW_URL = Pattern.compile("https?://[^\\s)\\]>]+");
    private static final Pattern RE_TABLE_SEP = Pattern.compile("(?m)^[ \\t]*\\|[ \\t:|-]+\\|[ \\t]*$");
    private static final Pattern RE_TABLE_ROW = Pattern.compile("(?m)^[ \\t]*\\|(.+?)\\|[ \\t]*$");
    private static final Pattern RE_HEADING_PREFIX = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern RE_BLOCKQUOTE = Pattern.compile("(?m)^>\\s?");
    private static final Pattern RE_BOLD_ITALIC_3 = Pattern.compile("\\*{3}(.+?)\\*{3}");
    private static final Pattern RE_BOLD_ITALIC_2 = Pattern.compile("\\*{2}(.+?)\\*{2}");
    private static final Pattern RE_BOLD_ITALIC_1 = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern RE_EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern RE_LIST_MARKER = Pattern.compile("(?m)^[\\t ]*(?:[-*+]|\\d+\\.)\\s+");

    /** 对照 cleanPassageForRerank：12 步去格式噪声（顺序即语义）。 */
    static String cleanPassageForRerank(String text) {
        // 1. 代码块解包
        text = RE_CODE_BLOCK.matcher(text).replaceAll("$1");
        // 2. LaTeX 块解包
        text = RE_LATEX_BLOCK.matcher(text).replaceAll("$1");
        // 3. HTML 标签
        text = RE_HTML_TAG.matcher(text).replaceAll("");
        // 3.5 嵌套图片链接解包
        text = RE_LINKED_IMAGE.matcher(text).replaceAll("![$1]($2)");
        // 4. markdown 图片整体移除
        text = RE_MARKDOWN_IMAGE.matcher(text).replaceAll("");
        // 5. markdown 链接只留文本
        text = RE_MARKDOWN_LINK.matcher(text).replaceAll("$1");
        // 6. 裸 URL
        text = RE_RAW_URL.matcher(text).replaceAll("");
        // 7. 表格分隔行
        text = RE_TABLE_SEP.matcher(text).replaceAll("");
        // 7.5 表格数据行 → 逗号连接
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher rowMatcher = RE_TABLE_ROW.matcher(text);
        while (rowMatcher.find()) {
            String inner = rowMatcher.groupCount() >= 1 ? rowMatcher.group(1) : "";
            String[] cells = inner.split("\\|", -1);
            List<String> parts = new ArrayList<>();
            for (String cell : cells) {
                String c = cell.trim();
                if (!c.isEmpty()) {
                    parts.add(c);
                }
            }
            String joined = String.join(", ", parts);
            rowMatcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(joined));
        }
        rowMatcher.appendTail(sb);
        text = sb.toString();
        // 8. 标题标记
        text = RE_HEADING_PREFIX.matcher(text).replaceAll("");
        // 9. 引用标记
        text = RE_BLOCKQUOTE.matcher(text).replaceAll("");
        // 10. 粗斜体解包（*** → ** → *）
        text = RE_BOLD_ITALIC_3.matcher(text).replaceAll("$1");
        text = RE_BOLD_ITALIC_2.matcher(text).replaceAll("$1");
        text = RE_BOLD_ITALIC_1.matcher(text).replaceAll("$1");
        // 11. 列表标记
        text = RE_LIST_MARKER.matcher(text).replaceAll("");
        // 12. 压缩多余换行
        text = RE_EXCESSIVE_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    /** 对照 getEnrichedPassage：Content + ImageInfo + GeneratedQuestions 合并。 */
    static String getEnrichedPassage(SearchResult result) {
        String combinedText = cleanPassageForRerank(result.getContent());
        List<String> enrichments = new ArrayList<>();

        if (!result.getImageInfo().isEmpty()) {
            List<ImageInfo> imageInfos = ImageInfoMatchUtil.parseInfos(result.getImageInfo());
            if (imageInfos == null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", "invalid image info json");
                PipelineLog.warn("Rerank", "image_info_parse", f);
            } else {
                for (ImageInfo img : imageInfos) {
                    if (!img.getCaption().isEmpty()) {
                        enrichments.add(img.getCaption());
                    }
                    if (!img.getOcrText().isEmpty()) {
                        enrichments.add(img.getOcrText());
                    }
                }
            }
        }

        JsonNode metaNode = result.getChunkMetadata();
        if (metaNode != null && !metaNode.isNull()) {
            DocumentChunkMetadata docMeta = parseDocMeta(metaNode);
            if (docMeta == null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", "invalid chunk metadata json");
                PipelineLog.warn("Rerank", "chunk_metadata_parse", f);
            } else {
                List<String> questionStrings = new ArrayList<>();
                if (docMeta.getGeneratedQuestions() != null) {
                    for (GeneratedQuestion q : docMeta.getGeneratedQuestions()) {
                        if (q != null) {
                            questionStrings.add(q.getQuestion());
                        }
                    }
                }
                if (!questionStrings.isEmpty()) {
                    enrichments.add(String.join("; ", questionStrings));
                }
            }
        }

        if (enrichments.isEmpty()) {
            return combinedText;
        }
        if (!combinedText.isEmpty()) {
            combinedText += "\n\n";
        }
        combinedText += String.join("\n", enrichments);
        return combinedText;
    }

    private static DocumentChunkMetadata parseDocMeta(JsonNode node) {
        try {
            return JsonMappers.lenient().treeToValue(node, DocumentChunkMetadata.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 对照 logRerankInputScoreSample。 */
    static void logRerankInputScoreSample(List<SearchResult> results) {
        final int maxLogRows = 8;
        int limit = results == null ? 0 : Math.min(maxLogRows, results.size());
        for (int i = 0; i < limit; i++) {
            SearchResult sr = results.get(i);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("index", i);
            f.put("chunk_id", sr.getId());
            f.put("score", RetrievalObs.goFmt4(sr.getScore()));
            f.put("match_type", sr.getMatchType());
            PipelineLog.info("Rerank", "input_score", f);
        }
        int total = results == null ? 0 : results.size();
        if (total > limit) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("total", total);
            f.put("logged", limit);
            f.put("truncated", total - limit);
            PipelineLog.info("Rerank", "input_score_summary", f);
        }
    }
}
