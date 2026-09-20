package com.ragagent.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * NVIDIA embedding 客户端（对照 Go {@code internal/models/embedding/nvidia.go} 全文）。
 *
 * <p>请求体 = Go {@code NvidiaEmbedRequest}（model/input/encoding_format/dimensions/
 * truncate_prompt_tokens/input_type）；{@code input_type} 默认 {@code "passage"}，
 * {@link EmbedQueryContext#isQuery()} 时改 {@code "query"}（对照 Go 的 ctx value）。
 * 构造器<b>不收</b> truncatePromptTokens（Go 的 NewNvidiaEmbedder 无此参、字段恒 0 →
 * omitempty 恒省略）。</p>
 */
public final class NvidiaEmbedder extends BaseEmbedder {

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public NvidiaEmbedder(String apiKey, String baseUrl, String modelName,
                          int dimensions, String modelId, EmbedderPooler pooler) {
        super(modelName, 0, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://integrate.api.nvidia.com/v1";
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("model name is required");
        }
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", GoJson.arrayOfStrings(texts));
        reqBody.put("encoding_format", "float");
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
        // truncate_prompt_tokens 恒 0 → omitempty 恒省略
        reqBody.put("input_type", EmbedQueryContext.isQuery() ? "query" : "passage");
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
                    + resp.statusLine());
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
