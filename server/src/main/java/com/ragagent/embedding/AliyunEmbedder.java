package com.ragagent.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 阿里云 DashScope 多模态 embedding 客户端（对照 Go
 * {@code internal/models/embedding/aliyun.go} 全文）。
 *
 * <p>请求体 = Go {@code AliyunEmbedRequest} 字段序（model/input/parameters）；
 * {@code parameters.dimension} 仅在 supportsDimensionsParam 时出现。响应按
 * {@code text_index} 回填到输入位置（越界丢弃）。错误优先解析
 * {@code code - message} 形态。</p>
 */
public final class AliyunEmbedder extends BaseEmbedder {

    /** 阿里云 DashScope 多模态 Embedding API 端点（Go AliyunMultimodalEmbeddingEndpoint）。 */
    public static final String MULTIMODAL_ENDPOINT =
            "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public AliyunEmbedder(String apiKey, String baseUrl, String modelName,
                          int truncatePromptTokens, int dimensions, String modelId,
                          EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://dashscope.aliyuncs.com";
        }
        // 去尾斜杠；若含 /compatible-mode/v1 则剥掉（多模态 API 不走该路径）
        baseUrl = goTrimRight(baseUrl, '/');
        if (baseUrl.contains("/compatible-mode/v1")) {
            baseUrl = baseUrl.replaceFirst("(?s)/compatible-mode/v1", "");
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
        // 对照 AliyunEmbedRequest：contents 数组 + 可选 parameters.dimension
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", modelName);
        ObjectNode input = reqBody.putObject("input");
        ArrayNode contents = input.putArray("contents");
        for (String text : texts) {
            ObjectNode c = contents.addObject();
            // AliyunContent.Text 带 omitempty：空文本省略 text 键
            if (text != null && !text.isEmpty()) {
                c.put("text", text);
            }
        }
        if (supportsDimensionsParam()) {
            reqBody.putObject("parameters").put("dimension", dimensions);
        }
        byte[] jsonData = GoJson.marshal(reqBody);

        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(baseUrl + MULTIMODAL_ENDPOINT, jsonData,
                    "Authorization", "Bearer " + apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            JsonNode errResp = GoJson.parse(resp.bodyText());
            if (errResp != null && !errResp.path("message").asText("").isEmpty()) {
                throw new EmbeddingHttp.EmbeddingException("API error: "
                        + errResp.path("code").asText("") + " - "
                        + errResp.path("message").asText(""));
            }
            throw new EmbeddingHttp.EmbeddingException("BatchEmbed API error: Http Status "
                    + resp.statusLine());
        }

        JsonNode response = GoJson.parse(resp.bodyText());
        if (response == null) {
            throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                    + resp.bodyText());
        }

        // 按 text_index 回填（Go：越界索引直接丢弃；未命中的位置保持 nil → Java null）
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(null);
        }
        for (JsonNode emb : response.path("output").path("embeddings")) {
            int idx = emb.path("text_index").asInt(-1);
            if (idx >= 0 && idx < embeddings.size()) {
                embeddings.set(idx, GoJson.floatArray(emb.path("embedding")));
            }
        }
        return embeddings;
    }

    /** Go strings.TrimRight(s, "/")：只剥尾部斜杠。 */
    static String goTrimRight(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(0, end);
    }
}
