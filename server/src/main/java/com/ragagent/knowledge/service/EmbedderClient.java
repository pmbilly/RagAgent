package com.ragagent.knowledge.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.model.domain.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 最小 OpenAI 兼容 embedding 客户端（对照 Go internal/models/embedding 的
 * OpenAICompatibleEmbedder 路径；完整 EmbedderPooler 随阶段 7）。
 *
 * POST {base_url}/embeddings，Bearer api_key；输入 batch 文本，返回 float 向量。
 */
@Service
public class EmbedderClient {

    private static final Logger log = LoggerFactory.getLogger(EmbedderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** 对照 embedding.NewEmbedder 的配置解析：base_url/api_key 来自模型参数 */
    public record EmbedConfig(String baseUrl, String apiKey, String modelName) {}

    public static EmbedConfig configFrom(Model model) {
        var p = model.getParameters();
        String base = p.getBaseUrl();
        if (base.isEmpty()) {
            base = "https://api.openai.com/v1";
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return new EmbedConfig(base, p.getApiKey(), model.getName());
    }

    /** 批量嵌入（对照 BatchEmbed；失败抛异常，由调用方归类为 failed） */
    public List<float[]> embedBatch(EmbedConfig config, List<String> texts) throws Exception {
        var input = MAPPER.createArrayNode();
        texts.forEach(input::add);
        var body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        body.set("input", input);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/embeddings"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (!config.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            // 对照 Go "send request: %w"（url.Error 含方法与地址）：JDK 裸 ConnectException
            // 常无 message，兜底类名；dial tcp 等传输层内文属已记录的掩码 DIFF 族。
            throw new IllegalStateException(
                    "send request: Post \"" + config.baseUrl() + "/embeddings\": " + ioDetail(e), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("embedding request failed: HTTP " + resp.statusCode()
                    + " " + abbreviate(resp.body()));
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("embedding response missing data: " + abbreviate(resp.body()));
        }
        List<float[]> out = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode emb = item.get("embedding");
            if (emb == null || !emb.isArray()) {
                throw new IllegalStateException("embedding item missing vector");
            }
            float[] vector = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vector[i] = (float) emb.get(i).asDouble();
            }
            out.add(vector);
        }
        return out;
    }

    private static String ioDetail(java.io.IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
