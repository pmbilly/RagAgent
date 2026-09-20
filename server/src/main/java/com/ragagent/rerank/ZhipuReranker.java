package com.ragagent.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 智谱 rerank 客户端（对照 Go {@code rerank/zhipu_reranker.go} 全文）。
 *
 * <p>POST 到 base URL 本身（默认
 * {@code https://open.bigmodel.cn/api/paas/v4/rerank}）；请求体
 * {@code top_n:0}（omitempty 省略）、{@code return_documents:true}
 * （omitempty 但 true 非零恒输出）、{@code return_raw_scores:false}（省略）。
 * 响应 {@code results[].document} 是<b>字符串</b>。</p>
 */
public final class ZhipuReranker implements Reranker {

    private final String modelName;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private Map<String, String> customHeaders;

    public ZhipuReranker(RerankerConfig config) {
        String baseURL = config.getBaseUrl().isEmpty()
                ? "https://open.bigmodel.cn/api/paas/v4/rerank" : config.getBaseUrl();
        RerankHttp.validateRerankBaseUrl(baseURL);
        this.modelName = config.getModelName();
        this.modelId = config.getModelId();
        this.apiKey = config.getApiKey();
        this.baseUrl = baseURL;
        this.customHeaders = config.getCustomHeaders();
    }

    void setCustomHeaders(Map<String, String> headers) {
        this.customHeaders = headers;
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        ObjectNode requestBody = GoJson.object();
        requestBody.put("model", modelName);
        requestBody.put("query", query == null ? "" : query);
        requestBody.set("documents", GoJson.arrayOfStrings(documents));
        // TopN=0 → omitempty 省略；ReturnDocuments=true → 输出；ReturnRawScores=false → 省略
        requestBody.put("return_documents", true);
        byte[] jsonData = GoJson.marshal(requestBody);

        RerankHttp.Result resp;
        try {
            resp = RerankHttp.post(baseUrl, jsonData, "Authorization",
                    "Bearer " + apiKey, customHeaders, null);
        } catch (RerankHttp.RerankException e) {
            throw new RerankHttp.RerankException("do request: " + e.getMessage(), e);
        }
        if (resp.status() != 200) {
            throw new RerankHttp.RerankException("zhipu rerank API error: Http Status: "
                    + resp.statusLine() + ", Body: " + resp.bodyText());
        }
        JsonNode response = GoJson.parse(resp.bodyText());
        if (response == null) {
            throw new RerankHttp.RerankException("unmarshal response: " + resp.bodyText());
        }
        List<RankResult> results = new ArrayList<>();
        for (JsonNode zhipuResult : response.path("results")) {
            RankResult r = new RankResult();
            r.setIndex(zhipuResult.path("index").asInt());
            r.getDocument().setText(zhipuResult.path("document").asText(""));
            r.setRelevanceScore(zhipuResult.path("relevance_score").asDouble());
            results.add(r);
        }
        return results;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String getModelID() {
        return modelId;
    }
}
