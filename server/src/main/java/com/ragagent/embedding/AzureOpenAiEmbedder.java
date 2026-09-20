package com.ragagent.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Azure OpenAI embedding 客户端（对照 Go
 * {@code internal/models/embedding/azure_openai.go} 全文）。
 *
 * <p>URL 公式：{@code {base}/openai/deployments/{model}/embeddings?api-version={v}}
 * （api_version 默认 2024-10-21，取 extra_config）；鉴权头是 {@code api-key}
 * （非 Bearer）。请求体无 truncate_prompt_tokens。</p>
 */
public final class AzureOpenAiEmbedder extends BaseEmbedder {

    private final String baseUrl;
    private final String apiVersion;
    private final Duration timeout = Duration.ofSeconds(60);

    public AzureOpenAiEmbedder(String apiKey, String baseUrl, String modelName,
                               int truncatePromptTokens, int dimensions, String modelId,
                               String apiVersion, EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("Azure resource endpoint (base URL) is required");
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("deployment name (model name) is required");
        }
        if (apiVersion == null || apiVersion.isEmpty()) {
            apiVersion = "2024-10-21";
        }
        if (truncatePromptTokens == 0) {
            truncatePromptTokens = 511;
        }
        this.truncatePromptTokens = truncatePromptTokens;
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        this.apiVersion = apiVersion;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        // 对照 azureOpenAIEmbedRequest：model/input/encoding_format/dimensions
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", GoJson.arrayOfStrings(texts));
        reqBody.put("encoding_format", "float");
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
        byte[] jsonData = GoJson.marshal(reqBody);

        String url = baseUrl + "/openai/deployments/" + modelName
                + "/embeddings?api-version=" + apiVersion;
        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(url, jsonData,
                    "api-key", apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            throw new EmbeddingHttp.EmbeddingException("Azure Embedding API error: Http Status "
                    + resp.statusLine() + ", Response: " + truncateBody(resp.bodyText()));
        }

        JsonNode response = GoJson.parse(resp.bodyText());
        if (response == null) {
            throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                    + resp.bodyText());
        }
        List<float[]> embeddings = new ArrayList<>();
        for (JsonNode data : response.path("data")) {
            embeddings.add(GoJson.floatArray(data.path("embedding")));
        }
        return embeddings;
    }
}
