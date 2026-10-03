package com.ragagent.initialization.service;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/** initialization 端点的原始请求体绑定器（record + 手写绑定,契约换锚时一并 DTO 化）。 */
public final class InitializationRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InitializationRequests() {
    }
    record KBModelConfigRequest(String llmModelId, String embeddingModelId,
            JsonNode vlmConfig, JsonNode asrConfig, int chunkSize, int chunkOverlap,
            List<String> separators, List<com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule>
            parserEngineRules, boolean enableParentChild, Integer parentChunkSize,
            Integer childChunkSize, String strategy, Integer tokenLimit, List<String> languages,
            String tableMetadataInstructions, boolean multimodalEnabled, String storageProvider,
            boolean nodeExtractEnabled, String nodeExtractText, List<String> nodeExtractTags,
            List<Object> nodeExtractNodes, List<Object> nodeExtractRelations,
            String nodeExtractCustomInstructions, boolean questionGenerationEnabled,
            int questionGenerationCount, String questionGenerationInstructions) {}
    static KBModelConfigRequest bindKBModelConfigRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    e.getMessage()));
        }
        if (n == null || !n.isObject()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        String llmModelId = ModelConnectivityTestService.text(n, "llmModelId");
        if (llmModelId.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'KBModelConfigRequest.LLMModelID' Error:Field validation for "
                            + "'LLMModelID' failed on the 'required' tag"));
        }
        JsonNode ds = n.get("documentSplitting");
        JsonNode ne = n.get("nodeExtract");
        JsonNode qg = n.get("questionGeneration");
        List<String> seps = new ArrayList<>();
        if (ds != null && ds.get("separators") != null && ds.get("separators").isArray()) {
            ds.get("separators").forEach(s -> seps.add(s.asText()));
        }
        List<com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule> rules = new ArrayList<>();
        if (ds != null && ds.get("parserEngineRules") != null && ds.get("parserEngineRules").isArray()) {
            for (JsonNode r : ds.get("parserEngineRules")) {
                com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule rule =
                        new com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule();
                rule.setFileTypes(toStringList(r.get("fileTypes")));
                rule.setEngine(r.path("engine").asText(""));
                rule.setXlsxFirstRowAsHeader(r.path("xlsxFirstRowAsHeader").asBoolean(false));
                rules.add(rule);
            }
        }
        return new KBModelConfigRequest(
                llmModelId,
                ModelConnectivityTestService.text(n, "embeddingModelId"),
                n.get("vlmConfig"),
                n.get("asrConfig"),
                ds == null ? 0 : ds.path("chunkSize").asInt(0),
                ds == null ? 0 : ds.path("chunkOverlap").asInt(0),
                seps,
                rules,
                ds != null && ds.path("enableParentChild").asBoolean(false),
                ds != null && ds.hasNonNull("parentChunkSize") ? ds.path("parentChunkSize").asInt() : null,
                ds != null && ds.hasNonNull("childChunkSize") ? ds.path("childChunkSize").asInt() : null,
                ds != null && ds.hasNonNull("strategy") ? ds.path("strategy").asText() : null,
                ds != null && ds.hasNonNull("tokenLimit") ? ds.path("tokenLimit").asInt() : null,
                ds != null && ds.hasNonNull("languages") ? toStringList(ds.get("languages")) : null,
                ds != null && ds.hasNonNull("tableMetadataInstructions")
                        ? ds.path("tableMetadataInstructions").asText() : null,
                n.path("multimodal").path("enabled").asBoolean(false),
                ModelConnectivityTestService.text(n, "storageProvider"),
                ne != null && ne.path("enabled").asBoolean(false),
                ne == null ? "" : ne.path("text").asText(""),
                ne != null ? toStringList(ne.get("tags")) : List.of(),
                ne != null && ne.get("nodes") != null ? toList(ne.get("nodes")) : List.of(),
                ne != null && ne.get("relations") != null ? toList(ne.get("relations")) : List.of(),
                ne == null ? "" : ne.path("customInstructions").asText(""),
                qg != null && qg.path("enabled").asBoolean(false),
                qg == null ? 0 : qg.path("questionCount").asInt(0),
                qg == null ? "" : qg.path("customInstructions").asText(""));
    }
    record InitializationRequest(String llmSource, String llmModelName, String llmBaseUrl,
            String llmApiKey, String embSource, String embModelName, String embBaseUrl,
            String embApiKey, int embDimension, boolean rerankEnabled, String rerankModelName,
            String rerankBaseUrl, String rerankApiKey, boolean multimodalEnabled,
            JsonNode multimodal, int chunkSize, int chunkOverlap, List<String> separators,
            boolean nodeExtractEnabled, String nodeExtractText, List<String> nodeExtractTags,
            List<Object> nodeExtractNodes, List<Object> nodeExtractRelations) {}
    static InitializationRequest bindInitializationRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    e.getMessage()));
        }
        if (n == null || !n.isObject()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        JsonNode llm = n.get("llm");
        JsonNode emb = n.get("embedding");
        JsonNode ds = n.get("documentSplitting");
        // gin binding 按 struct 字段序报第一个错误
        if (llm == null || llm.path("source").asText("").isEmpty()) {
            throw required("InitializationRequest.LLM.Source", "Source");
        }
        if (llm.path("modelName").asText("").isEmpty()) {
            throw required("InitializationRequest.LLM.ModelName", "ModelName");
        }
        if (emb == null || emb.path("source").asText("").isEmpty()) {
            throw required("InitializationRequest.Embedding.Source", "Source");
        }
        if (emb.path("modelName").asText("").isEmpty()) {
            throw required("InitializationRequest.Embedding.ModelName", "ModelName");
        }
        int chunkSize = 0;
        int chunkOverlap = 0;
        List<String> seps = new ArrayList<>();
        if (ds == null) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting' Error:Field validation for "
                            + "'DocumentSplitting' failed on the 'required' tag"));
        }
        chunkSize = ds.path("chunkSize").asInt(0);
        chunkOverlap = ds.path("chunkOverlap").asInt(0);
        if (ds.get("separators") != null && ds.get("separators").isArray()) {
            ds.get("separators").forEach(s -> seps.add(s.asText()));
        }
        if (chunkSize < 100) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.ChunkSize' "
                            + "Error:Field validation for 'ChunkSize' failed on the 'min' tag"));
        }
        if (chunkSize > 10000) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.ChunkSize' "
                            + "Error:Field validation for 'ChunkSize' failed on the 'max' tag"));
        }
        if (seps.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.Separators' "
                            + "Error:Field validation for 'Separators' failed on the 'min' tag"));
        }
        JsonNode mm = n.get("multimodal");
        JsonNode ne = n.get("nodeExtract");
        return new InitializationRequest(
                llm.path("source").asText(""), llm.path("modelName").asText(""),
                llm.path("baseUrl").asText(""), llm.path("apiKey").asText(""),
                emb.path("source").asText(""), emb.path("modelName").asText(""),
                emb.path("baseUrl").asText(""), emb.path("apiKey").asText(""),
                emb.path("dimension").asInt(0),
                n.path("rerank").path("enabled").asBoolean(false),
                n.path("rerank").path("modelName").asText(""),
                n.path("rerank").path("baseUrl").asText(""),
                n.path("rerank").path("apiKey").asText(""),
                mm != null && mm.path("enabled").asBoolean(false),
                mm,
                chunkSize, chunkOverlap, seps,
                ne != null && ne.path("enabled").asBoolean(false),
                ne == null ? "" : ne.path("text").asText(""),
                ne != null ? toStringList(ne.get("tags")) : List.of(),
                ne != null && ne.get("nodes") != null ? toList(ne.get("nodes")) : List.of(),
                ne != null && ne.get("relations") != null ? toList(ne.get("relations")) : List.of());
    }
    static BizException required(String structField, String field) {
        return new BizException(AppError.badRequest("Key: '" + structField
                + "' Error:Field validation for '" + field + "' failed on the 'required' tag"));
    }

    static List<String> toStringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.asText(""));
            }
        }
        return out;
    }

    static List<Object> toList(JsonNode n) {
        List<Object> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(out::add);
        }
        return out;
    }

}
