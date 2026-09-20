package com.ragagent.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Jina AI embedding 客户端（对照 Go {@code internal/models/embedding/jina.go} 全文）。
 *
 * <p>Jina 与 OpenAI 兼容但<b>不支持</b> {@code truncate_prompt_tokens}（Go 结构体
 * 根本没有该字段、也不存 truncatePromptTokens）；用 {@code truncate:true} 布尔
 * 开启长文本截断；{@code dimensions} 仅在 supportsDimensionsParam 时出现。</p>
 */
public final class JinaEmbedder extends BaseEmbedder {

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public JinaEmbedder(String apiKey, String baseUrl, String modelName,
                        int truncatePromptTokens, int dimensions, String modelId,
                        EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.jina.ai/v1";
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
        // 对照 JinaEmbedRequest：model/input/truncate/dimensions；truncate:true 恒发
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", GoJson.arrayOfStrings(texts));
        reqBody.put("truncate", true);
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
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
