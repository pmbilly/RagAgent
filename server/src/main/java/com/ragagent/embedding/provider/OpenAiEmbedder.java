package com.ragagent.embedding.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.embedding.EmbedderPooler;
import com.ragagent.embedding.EmbeddingHttp;
import com.ragagent.embedding.GoJson;

/**
 * OpenAI 兼容 embedding 客户端（对照 Go {@code internal/models/embedding/openai.go} 全文）。
 *
 * <p>请求体 = Go {@code OpenAIEmbedRequest} 字段序；{@code encoding_format} 恒
 * {@code "float"}；{@code dimensions} 仅在「显式覆盖 + 维度为正」时出现（omitempty）。
 * 错误文案逐字对照（含 body 1000 字节截断、send/unmarshal 前缀）。</p>
 */
public final class OpenAiEmbedder extends BaseEmbedder {

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public OpenAiEmbedder(String apiKey, String baseUrl, String modelName,
                          int truncatePromptTokens, int dimensions, String modelId,
                          EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("model name is required");
        }
        if (truncatePromptTokens == 0) {
            truncatePromptTokens = 511;
        }
        this.truncatePromptTokens = truncatePromptTokens;
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        // 对照 OpenAIEmbedRequest：字段序 model/input/encoding_format/dimensions/truncate_prompt_tokens
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", GoJson.arrayOfStrings(texts));
        reqBody.put("encoding_format", "float");
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
        reqBody.put("truncate_prompt_tokens", truncatePromptTokens);
        byte[] jsonData = GoJson.marshal(reqBody);

        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(baseUrl + "/embeddings", jsonData,
                    "Authorization", "Bearer " + apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            throw new EmbeddingHttp.EmbeddingException("EmbedBatch API error: Http Status "
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
