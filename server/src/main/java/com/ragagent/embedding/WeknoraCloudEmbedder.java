package com.ragagent.embedding;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * WeKnoraCloud embedding 客户端（对照 Go
 * {@code internal/models/embedding/weknoracloud.go} 全文）。
 *
 * <p>POST {@code {base}/api/v1/embeddings}，签名头由 {@link WeknoraCloudSign} 生成；
 * {@code dimensions} 仅在 supportsDimensionOverride + 维度为正时出现。响应按
 * index 回填且<b>严格校验</b>：越界 / 重复 / 缺失都报错（文案逐字对照）。</p>
 */
public class WeknoraCloudEmbedder extends BaseEmbedder implements EmbedderPooler {

    static final String EMBED_PATH = "/api/v1/embeddings";

    String remoteModelName = "";
    final String appId;
    final String baseUrl;

    public WeknoraCloudEmbedder(EmbedderConfig config) {
        super(config.getModelName(), 0, config.getDimensions(), config.getModelId(), null);
        if (config.getAppId() == null || config.getAppId().isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("WeKnoraCloud embedder: AppID is required");
        }
        if (config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("WeKnoraCloud embedder: AppSecret is required");
        }
        if (config.getExtraConfig() != null) {
            String rm = config.getExtraConfig().get("remote_model_name");
            this.remoteModelName = rm == null ? "" : rm.trim();
        }
        String base = config.getBaseUrl() == null ? "" : AliyunEmbedder.goTrimRight(config.getBaseUrl(), '/');
        if (base.isEmpty()) {
            base = "https://weknora.weixin.qq.com";
        }
        EmbeddingHttp.validateEmbeddingBaseUrl(base);
        this.baseUrl = base;
        this.appId = config.getAppId();
        setApiKey(config.getAppSecret());
        this.supportsDimensionOverride = config.isSupportsDimensionOverride();
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", effectiveModelName());
        reqBody.set("input", GoJson.arrayOfStrings(texts));
        if (supportsDimensionOverride && dimensions > 0) {
            reqBody.put("dimensions", dimensions);
        }
        byte[] bodyBytes = GoJson.marshal(reqBody);

        String requestID = UUID.randomUUID().toString();
        Map<String, String> headers = WeknoraCloudSign.sign(appId, apiKey, requestID,
                new String(bodyBytes, StandardCharsets.UTF_8));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(baseUrl + EMBED_PATH))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            requestBuilder.header(e.getKey(), e.getValue());
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = com.ragagent.llm.chat.LlmTransport.send(request);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingHttp.EmbeddingException(
                    "weknoracloud embedder: do request: " + e.getMessage(), e);
        }
        byte[] respBytes;
        try (InputStream in = resp.body()) {
            respBytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new EmbeddingHttp.EmbeddingException(
                    "weknoracloud embedder: read response: " + e.getMessage(), e);
        }
        String bodyText = new String(respBytes, StandardCharsets.UTF_8);
        if (resp.statusCode() != 200) {
            throw new EmbeddingHttp.EmbeddingException("weknoracloud embedder: status "
                    + resp.statusCode() + ": " + bodyText);
        }

        JsonNode embedResp = GoJson.parse(bodyText);
        if (embedResp == null) {
            throw new EmbeddingHttp.EmbeddingException("weknoracloud embedder: unmarshal: "
                    + bodyText);
        }

        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            result.add(null);
        }
        boolean[] seen = new boolean[texts.size()];
        for (JsonNode item : embedResp.path("data")) {
            int index = item.path("index").asInt();
            if (index < 0 || index >= result.size()) {
                throw new EmbeddingHttp.EmbeddingException("weknoracloud embedder: response index "
                        + index + " out of range for " + texts.size() + " inputs");
            }
            if (seen[index]) {
                throw new EmbeddingHttp.EmbeddingException("weknoracloud embedder: duplicate response index "
                        + index);
            }
            result.set(index, GoJson.floatArray(item.path("embedding")));
            seen[index] = true;
        }
        for (int index = 0; index < seen.length; index++) {
            if (!seen[index]) {
                throw new EmbeddingHttp.EmbeddingException("weknoracloud embedder: missing embedding for input index "
                        + index);
            }
        }
        return result;
    }

    /** 对照 BatchEmbedWithPool：自身直连（不再走池）。 */
    @Override
    public List<float[]> batchEmbedWithPool(Embedder model, List<String> texts) {
        return batchEmbed(texts);
    }

    String effectiveModelName() {
        return remoteModelName.isEmpty() ? modelName : remoteModelName;
    }
}
