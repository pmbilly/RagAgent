package com.ragagent.rerank.provider;

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
import com.ragagent.rerank.GoJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * WeKnoraCloud rerank 客户端（对照 Go {@code rerank/weknoracloud.go} 全文）。
 *
 * <p>POST {@code {base}/api/v1/rerank}，签名头由
 * {@link com.ragagent.embedding.provider.WeknoraCloudSign}（全项目第二份 Sign 实现的复用点）
 * 生成；请求体 model/query/documents；响应 results[].document 是对象
 * {@code {"text":...}}。</p>
 */
public final class WeknoraCloudReranker implements Reranker {

    static final String RERANK_PATH = "/api/v1/rerank";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String modelName;
    private String remoteModelName = "";
    private final String modelId;
    private final String appId;
    private final String apiKey;
    private final String baseUrl;

    public WeknoraCloudReranker(RerankerConfig config) {
        if (config.getAppId().isEmpty()) {
            throw new RerankHttp.RerankException("WeKnoraCloud reranker: AppID is required");
        }
        if (config.getAppSecret().isEmpty()) {
            throw new RerankHttp.RerankException("WeKnoraCloud reranker: AppSecret is required");
        }
        String base = AliyunTrim(config.getBaseUrl());
        RerankHttp.validateRerankBaseUrl(base);
        if (config.getExtraConfig() != null) {
            String rm = config.getExtraConfig().get("remote_model_name");
            this.remoteModelName = rm == null ? "" : rm.trim();
        }
        this.modelName = config.getModelName();
        this.modelId = config.getModelId();
        this.appId = config.getAppId();
        this.apiKey = config.getAppSecret();
        this.baseUrl = base;
    }

    private static String AliyunTrim(String s) {
        int end = s == null ? 0 : s.length();
        String v = s == null ? "" : s;
        while (end > 0 && v.charAt(end - 1) == '/') {
            end--;
        }
        return v.substring(0, end);
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        ObjectNode reqBody = GoJson.object();
        reqBody.put("model", effectiveModelName());
        reqBody.put("query", query == null ? "" : query);
        reqBody.set("documents", GoJson.arrayOfStrings(documents));
        byte[] bodyBytes = GoJson.marshal(reqBody);

        String requestID = UUID.randomUUID().toString();
        Map<String, String> headers = com.ragagent.embedding.provider.WeknoraCloudSign.sign(
                appId, apiKey, requestID, new String(bodyBytes, StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + RERANK_PATH))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }

        HttpResponse<InputStream> resp;
        try {
            resp = com.ragagent.llm.chat.LlmTransport.send(builder.build());
        } catch (RerankHttp.RerankException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankHttp.RerankException(
                    "weknoracloud reranker: do request: " + e.getMessage(), e);
        }
        byte[] respBytes;
        try (InputStream in = resp.body()) {
            respBytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RerankHttp.RerankException(
                    "weknoracloud reranker: read response: " + e.getMessage(), e);
        }
        String bodyText = new String(respBytes, StandardCharsets.UTF_8);
        if (resp.statusCode() != 200) {
            throw new RerankHttp.RerankException("weknoracloud reranker: status "
                    + resp.statusCode() + ": " + bodyText);
        }
        JsonNode rerankResp = GoJson.parse(bodyText);
        if (rerankResp == null) {
            throw new RerankHttp.RerankException("weknoracloud reranker: unmarshal: " + bodyText);
        }

        List<RankResult> results = new ArrayList<>();
        for (JsonNode item : rerankResp.path("results")) {
            RankResult r = new RankResult();
            r.setIndex(item.path("index").asInt());
            r.setRelevanceScore(item.path("relevance_score").asDouble());
            r.getDocument().setText(item.path("document").path("text").asText(""));
            results.add(r);
        }
        return results;
    }

    String effectiveModelName() {
        return remoteModelName.isEmpty() ? modelName : remoteModelName;
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
