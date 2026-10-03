package com.ragagent.rerank.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.ProviderJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * OpenAI 兼容 rerank 客户端（对照 Go {@code rerank/remote_api.go} 全文）。
 *
 * <p><b>truncate_prompt_tokens 是 opt-in</b>（issue #2143）：仅当 extra_config 显式
 * 配置正数时才发送（SiliconFlow 等 provider 会取模板 prompt 的<b>末</b> N token，
 * 把 query 截掉导致所有相关性分塌缩到近 0）；配置了非法值（非数字/负数/0）时
 * 构造即报 {@code invalid truncate_prompt_tokens in extra_config: %q}。</p>
 */
public final class OpenAiReranker implements Reranker {

    private final String modelName;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private final int truncatePromptTokens;
    private Map<String, String> customHeaders;

    public OpenAiReranker(RerankerConfig config) {
        String baseURL = config.getBaseUrl().isEmpty() ? "https://api.openai.com/v1" : config.getBaseUrl();
        RerankHttp.validateRerankBaseUrl(baseURL);

        int truncate = 0;
        if (config.getExtraConfig() != null) {
            String raw = config.getExtraConfig().get("truncate_prompt_tokens");
            raw = raw == null ? "" : raw.trim();
            if (!raw.isEmpty()) {
                int n;
                try {
                    n = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n <= 0) {
                    throw new RerankHttp.RerankException(
                            "invalid truncate_prompt_tokens in extra_config: \"" + raw + "\"");
                }
                truncate = n;
            }
        }
        this.modelName = config.getModelName();
        this.modelId = config.getModelId();
        this.apiKey = config.getApiKey();
        this.baseUrl = baseURL;
        this.truncatePromptTokens = truncate;
        this.customHeaders = config.getCustomHeaders();
    }

    public void setCustomHeaders(Map<String, String> headers) {
        this.customHeaders = headers;
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        // 对照 RerankRequest：model/query/documents/additional_data(omitempty)/
        // truncate_prompt_tokens(omitempty)
        ObjectNode requestBody = ProviderJson.object();
        requestBody.put("model", modelName);
        requestBody.put("query", query == null ? "" : query);
        requestBody.set("documents", ProviderJson.arrayOfStrings(documents));
        // additional_data 恒 nil → omitempty 恒省略
        if (truncatePromptTokens > 0) {
            requestBody.put("truncate_prompt_tokens", truncatePromptTokens);
        }
        byte[] jsonData = ProviderJson.marshal(requestBody);

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
        JsonNode response = ProviderJson.parse(resp.bodyText());
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
