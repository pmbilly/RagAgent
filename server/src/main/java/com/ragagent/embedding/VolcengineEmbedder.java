package com.ragagent.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 火山引擎 Ark 多模态 embedding 客户端（对照 Go
 * {@code internal/models/embedding/volcengine.go} 全文）。
 *
 * <p>多模态 API 对整批输入只返回<b>一个</b>合并向量，所以 Go 逐文本调用一次 API；
 * Java 照抄该逐条循环。请求体 = Go {@code VolcengineEmbedRequest}（model/input/
 * dimensions），每个 input 元素 {@code {"type":"text","text":...}}（text omitempty）。</p>
 */
public final class VolcengineEmbedder extends BaseEmbedder {

    /** 火山引擎 Ark 多模态 Embedding API 路径（Go VolcengineMultimodalEmbeddingPath）。 */
    public static final String MULTIMODAL_PATH = "/api/v3/embeddings/multimodal";

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public VolcengineEmbedder(String apiKey, String baseUrl, String modelName,
                              int truncatePromptTokens, int dimensions, String modelId,
                              EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://ark.cn-beijing.volces.com";
        }
        baseUrl = AliyunEmbedder.goTrimRight(baseUrl, '/');
        if (baseUrl.contains("/embeddings/multimodal")) {
            int idx = baseUrl.indexOf("/api/");
            if (idx != -1) {
                baseUrl = baseUrl.substring(0, idx);
            }
        } else if (baseUrl.endsWith("/api/v3")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/api/v3".length());
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
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(null);
        }
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            ObjectNode reqBody = GoJson.object();
            reqBody.put("model", modelName);
            ArrayNode input = reqBody.putArray("input");
            ObjectNode item = input.addObject();
            item.put("type", "text");
            if (text != null && !text.isEmpty()) {
                item.put("text", text);
            }
            if (supportsDimensionsParam()) {
                reqBody.put("dimensions", dimensions);
            }
            byte[] jsonData = GoJson.marshal(reqBody);

            EmbeddingHttp.Result resp;
            try {
                resp = EmbeddingHttp.postWithRetry(baseUrl + MULTIMODAL_PATH, jsonData,
                        "Authorization", "Bearer " + apiKey, customHeaders, timeout);
            } catch (EmbeddingHttp.EmbeddingException e) {
                throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
            }

            if (resp.status() != 200) {
                JsonNode errResp = GoJson.parse(resp.bodyText());
                String msg = errResp == null ? "" : errResp.path("error").path("message").asText("");
                if (!msg.isEmpty()) {
                    throw new EmbeddingHttp.EmbeddingException("API error: "
                            + errResp.path("error").path("code").asText("") + " - " + msg);
                }
                throw new EmbeddingHttp.EmbeddingException("BatchEmbed API error: Http Status "
                        + resp.statusLine());
            }

            JsonNode response = GoJson.parse(resp.bodyText());
            if (response == null) {
                throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                        + resp.bodyText());
            }
            embeddings.set(i, GoJson.floatArray(response.path("data").path("embedding")));
        }
        return embeddings;
    }
}
