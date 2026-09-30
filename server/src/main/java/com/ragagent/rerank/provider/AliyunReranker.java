package com.ragagent.rerank.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.rerank.GoJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * 阿里云 DashScope rerank 客户端（对照 Go {@code rerank/aliyun_reranker.go} 全文）。
 *
 * <p>POST 到 base URL 本身（无 /rerank 后缀）；请求体恒带
 * {@code parameters:{return_documents:true, top_n:N}}；响应从
 * {@code output.results[]} 转成标准 RankResult。错误信息带 body。</p>
 */
public final class AliyunReranker implements Reranker {

    private final String modelName;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private Map<String, String> customHeaders;

    public AliyunReranker(RerankerConfig config) {
        String baseURL = config.getBaseUrl().isEmpty()
                ? "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
                : config.getBaseUrl();
        RerankHttp.validateRerankBaseUrl(baseURL);
        this.modelName = config.getModelName();
        this.modelId = config.getModelId();
        this.apiKey = config.getApiKey();
        this.baseUrl = baseURL;
        this.customHeaders = config.getCustomHeaders();
    }

    public void setCustomHeaders(Map<String, String> headers) {
        this.customHeaders = headers;
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        ObjectNode requestBody = GoJson.object();
        requestBody.put("model", modelName);
        ObjectNode input = requestBody.putObject("input");
        input.put("query", query == null ? "" : query);
        input.set("documents", GoJson.arrayOfStrings(documents));
        // AliyunRerankParameters 是值类型（非指针）→ 恒输出
        ObjectNode parameters = requestBody.putObject("parameters");
        parameters.put("return_documents", true);
        parameters.put("top_n", documents.size());
        byte[] jsonData = GoJson.marshal(requestBody);

        RerankHttp.Result resp;
        try {
            resp = RerankHttp.post(baseUrl, jsonData, "Authorization",
                    "Bearer " + apiKey, customHeaders, null);
        } catch (RerankHttp.RerankException e) {
            throw new RerankHttp.RerankException("do request: " + e.getMessage(), e);
        }
        if (resp.status() != 200) {
            throw new RerankHttp.RerankException("aliyun rerank API error: Http Status: "
                    + resp.statusLine() + ", Body: " + resp.bodyText());
        }
        JsonNode response = GoJson.parse(resp.bodyText());
        if (response == null) {
            throw new RerankHttp.RerankException("unmarshal response: " + resp.bodyText());
        }
        List<RankResult> results = new ArrayList<>();
        for (JsonNode aliyunResult : response.path("output").path("results")) {
            RankResult r = new RankResult();
            r.setIndex(aliyunResult.path("index").asInt());
            r.getDocument().setText(aliyunResult.path("document").path("text").asText(""));
            r.setRelevanceScore(aliyunResult.path("relevance_score").asDouble());
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
