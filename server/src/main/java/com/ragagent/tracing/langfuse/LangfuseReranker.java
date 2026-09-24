package com.ragagent.tracing.langfuse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;

/**
 * rerank 客户端的 langfuse 装饰器（对照 Go internal/models/rerank/langfuse_wrapper.go）：
 * Rerank 发一条 generation；用量按 query + 各文档的「码点数/4 + 1」估算
 * （vendor 多按千文档计费，估算是给成本面板的比例信号）。由
 * {@code ModelRuntimeFactory.getRerankModel} 装配。
 */
public final class LangfuseReranker implements Reranker {

    private final Reranker inner;

    LangfuseReranker(Reranker inner) {
        this.inner = inner;
    }

    /** 对照 wrapRerankerLangfuse：未启用/空客户端原样返回。 */
    public static Reranker wrap(Reranker reranker) {
        if (reranker == null || !LangfuseManager.get().enabled()) {
            return reranker;
        }
        return new LangfuseReranker(reranker);
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.rerank(query, documents);
        }
        int totalChars = LangfusePayloads.codePointCount(query);
        for (String doc : documents) {
            totalChars += LangfusePayloads.codePointCount(doc);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("document_count", documents.size());
        input.put("documents_preview",
                LangfusePayloads.previewDocs(documents, LangfusePayloads.RERANK_PREVIEW_DOCS));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model_id", inner.getModelID());
        metadata.put("num_queries", 1);
        metadata.put("total_chars", totalChars);
        metadata.put("avg_doc_chars", LangfusePayloads.avgDocChars(documents));

        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "rerank", inner.getModelName(), input, metadata, null));

        List<RankResult> results = null;
        String err = null;
        try {
            results = inner.rerank(query, documents);
            return results;
        } catch (RuntimeException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            Map<String, Object> output = new LinkedHashMap<>();
            List<RankResult> safeResults = results == null ? List.of() : results;
            output.put("results", LangfusePayloads.summarizeRerankResults(
                    safeResults, documents, LangfusePayloads.RERANK_MAX_SCORES));
            output.put("total_count", safeResults.size());
            output.put("score_stats", LangfusePayloads.scoreStats(safeResults));
            if (safeResults.size() > LangfusePayloads.RERANK_MAX_SCORES) {
                output.put("truncated", safeResults.size() - LangfusePayloads.RERANK_MAX_SCORES);
            }
            gen.finish(output, LangfusePayloads.approxRerankUsage(query, documents), err);
        }
    }

    @Override
    public String getModelName() {
        return inner.getModelName();
    }

    @Override
    public String getModelID() {
        return inner.getModelID();
    }
}
