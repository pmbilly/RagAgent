package com.ragagent.rerank.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.rerank.GoJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * NVIDIA rerank 客户端（对照 Go {@code rerank/nvidia_reranker.go} 全文）。
 *
 * <p>请求体形如 {@code {"model":...,"query":{"text":...},"passages":[{"text":...}]}}
 * （字段名是 query/passages，非 OpenAI 形）；响应是 {@code rankings[].logit}，
 * 经 {@link #normalizeNvidiaLogit}（数值稳定 sigmoid）转成概率。</p>
 */
public final class NvidiaReranker implements Reranker {

    private final String modelName;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private Map<String, String> customHeaders;

    public NvidiaReranker(RerankerConfig config) {
        String baseURL = config.getBaseUrl().isEmpty()
                ? "https://ai.api.nvidia.com/v1/retrieval/nvidia/reranking" : config.getBaseUrl();
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
        requestBody.putObject("query").put("text", query == null ? "" : query);
        ArrayNode passages = requestBody.putArray("passages");
        for (String d : documents) {
            passages.addObject().put("text", d == null ? "" : d);
        }
        byte[] jsonData = GoJson.marshal(requestBody);

        RerankHttp.Result resp;
        try {
            resp = RerankHttp.post(baseUrl, jsonData, "Authorization",
                    "Bearer " + apiKey, customHeaders, null);
        } catch (RerankHttp.RerankException e) {
            throw new RerankHttp.RerankException("do request: " + e.getMessage(), e);
        }
        if (resp.status() != 200) {
            throw new RerankHttp.RerankException("Rerank API error: Http Status: " + resp.statusLine());
        }
        JsonNode response = GoJson.parse(resp.bodyText());
        if (response == null) {
            throw new RerankHttp.RerankException("unmarshal response: " + resp.bodyText());
        }
        List<RankResult> ret = new ArrayList<>();
        for (JsonNode result : response.path("rankings")) {
            RankResult r = new RankResult();
            int idx = result.path("index").asInt();
            r.setIndex(idx);
            r.getDocument().setText(idx >= 0 && idx < documents.size()
                    ? documents.get(idx) : "");
            r.setRelevanceScore(normalizeNvidiaLogit(result.path("logit").asDouble()));
            ret.add(r);
        }
        return ret;
    }

    /**
     * 对照 normalizeNvidiaLogit：把原始 logit 转成概率（按符号分两个分支防溢出）。
     */
    static double normalizeNvidiaLogit(double logit) {
        if (logit >= 0) {
            return 1 / (1 + Math.exp(-logit));
        }
        double expLogit = Math.exp(logit);
        return expLogit / (1 + expLogit);
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
