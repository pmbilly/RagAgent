package com.ragagent.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Jina rerank 客户端（对照 Go {@code rerank/jina_reranker.go} 全文）。
 *
 * <p>POST {@code {base}/rerank}；不支持 truncate_prompt_tokens；恒发
 * {@code return_documents:true}（top_n 未设 → omitempty 省略）。响应直接是
 * 标准 results 数组。</p>
 */
public final class JinaReranker implements Reranker {

    private final String modelName;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private Map<String, String> customHeaders;

    public JinaReranker(RerankerConfig config) {
        String baseURL = config.getBaseUrl().isEmpty() ? "https://api.jina.ai/v1" : config.getBaseUrl();
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
        requestBody.put("return_documents", true);
        byte[] jsonData = GoJson.marshal(requestBody);

        RerankHttp.Result resp;
        try {
            resp = RerankHttp.post(baseUrl + "/rerank", jsonData, "Authorization",
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
        List<RankResult> out = new ArrayList<>();
        for (JsonNode item : response.path("results")) {
            out.add(RankResult.parse(item));
        }
        return out;
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
