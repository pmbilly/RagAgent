package com.ragagent.embedding.provider;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.embedding.EmbedderPooler;
import com.ragagent.embedding.EmbeddingHttp;
import com.ragagent.common.web.ProviderJson;
import com.ragagent.llm.ollama.OllamaService;

/**
 * Ollama embedding 客户端（对照 Go {@code internal/models/embedding/ollama.go} 全文）。
 *
 * <p>不走 SSRF HTTP 设施（Ollama 是本地服务）：先 {@code ensureModelAvailable}
 * （对照 Go 的探活），再经 {@link OllamaService#embeddings} 走 {@code POST /api/embed}。
 * 请求体对照 ollama {@code api.EmbedRequest}：model/input/options（num_ctx）/
 * truncate/dimensions。空批响应导致 {@code failed to embed text: ...} 语义保留。</p>
 */
public final class OllamaEmbedder extends BaseEmbedder {

    private final OllamaService ollamaService;

    public OllamaEmbedder(String baseUrl, String modelName, int truncatePromptTokens,
                          int dimensions, String modelId, EmbedderPooler pooler,
                          OllamaService ollamaService) {
        super(modelName == null || modelName.isEmpty() ? "nomic-embed-text" : modelName,
                truncatePromptTokens, dimensions, modelId, pooler);
        if (this.truncatePromptTokens == 0) {
            this.truncatePromptTokens = 511;
        }
        this.ollamaService = ollamaService;
    }

    @Override
    public float[] embed(String text) {
        try {
            return super.embed(text);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("failed to embed text: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        // Ensure model is available
        ollamaService.ensureModelAvailable(modelName);

        ObjectNode req = ProviderJson.object();
        req.put("model", modelName);
        ArrayNode input = req.putArray("input");
        for (String t : texts) {
            input.add(t == null ? "" : t);
        }
        req.putObject("options");
        if (supportsDimensionOverride && dimensions > 0) {
            req.put("dimensions", dimensions);
        }
        if (truncatePromptTokens > 0) {
            req.putObject("options").put("num_ctx", truncatePromptTokens);
            req.put("truncate", true);
        }

        JsonNode resp;
        try {
            resp = ollamaService.embeddings(req);
        } catch (RuntimeException e) {
            throw new EmbeddingHttp.EmbeddingException("failed to get embedding vectors: "
                    + e.getMessage(), e);
        }
        if (resp == null) {
            throw new EmbeddingHttp.EmbeddingException("failed to get embedding vectors: empty response");
        }
        List<float[]> out = new ArrayList<>();
        for (JsonNode emb : resp.path("embeddings")) {
            out.add(ProviderJson.floatArray(emb));
        }
        return out;
    }
}
