package com.ragagent.embedding.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.embedding.EmbedderPooler;
import com.ragagent.embedding.EmbeddingHttp;
import com.ragagent.common.web.ProviderJson;

/**
 * Gemini 原生 batchEmbedContents embedding 客户端（对照 Go
 * {@code internal/models/embedding/gemini.go} 全文）。
 *
 * <p>URL：{@code {base}/models/{model}:batchEmbedContents}；鉴权头
 * {@code x-goog-api-key}；模型名剥 {@code models/} 前缀、URL 处剥 {@code /openai}
 * 后缀；请求体的 {@code model} 字段恒带 {@code models/} 前缀。空输入恒返回
 * {@code []}（Go 的 len(texts)==0 早退）。响应数量与输入不等时报错。</p>
 */
public final class GeminiEmbedder extends BaseEmbedder {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public GeminiEmbedder(String apiKey, String baseUrl, String modelName,
                          int truncatePromptTokens, int dimensions, String modelId,
                          EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("model name is required");
        }
        if (truncatePromptTokens == 0) {
            truncatePromptTokens = 511;
        }
        this.truncatePromptTokens = truncatePromptTokens;
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        baseUrl = AliyunEmbedder.goTrimRight(baseUrl, '/');
        if (baseUrl.endsWith("/openai")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/openai".length());
        }
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        this.modelName = modelName.startsWith("models/")
                ? modelName.substring("models/".length()) : modelName;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        // 对照 geminiBatchEmbedRequest：requests[] 每项 model/content(/taskType/
        // output_dimensionality omitempty)
        ObjectNode root = ProviderJson.object();
        ArrayNode requests = root.putArray("requests");
        for (String text : texts) {
            ObjectNode req = requests.addObject();
            req.put("model", "models/" + modelName);
            ObjectNode content = req.putObject("content");
            ObjectNode part = content.putArray("parts").addObject();
            part.put("text", text == null ? "" : text);
            if (supportsDimensionOverride && dimensions > 0) {
                req.put("output_dimensionality", dimensions);
            }
        }
        byte[] jsonData = ProviderJson.marshal(root);

        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(
                    baseUrl + "/models/" + modelName + ":batchEmbedContents", jsonData,
                    "x-goog-api-key", apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            throw new EmbeddingHttp.EmbeddingException("Gemini BatchEmbed API error: Http Status "
                    + resp.statusLine() + ", Response: " + truncateBody(resp.bodyText()));
        }

        JsonNode response = ProviderJson.parse(resp.bodyText());
        if (response == null) {
            throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                    + resp.bodyText());
        }
        JsonNode embeddingsNode = response.path("embeddings");
        if (embeddingsNode.size() != texts.size()) {
            throw new EmbeddingHttp.EmbeddingException("Gemini BatchEmbed returned "
                    + embeddingsNode.size() + " embeddings for " + texts.size() + " inputs");
        }
        List<float[]> embeddings = new ArrayList<>(embeddingsNode.size());
        for (JsonNode embedding : embeddingsNode) {
            embeddings.add(ProviderJson.floatArray(embedding.path("values")));
        }
        return embeddings;
    }
}
