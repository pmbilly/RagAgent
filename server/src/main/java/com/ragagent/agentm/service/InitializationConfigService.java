package com.ragagent.agentm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseResponse;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.security.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService;

/**
 * initialization config/initialize 端点用例：KB 初始化配置读写、初始化装配与请求绑定。
 *
 * <p>initialization 薄层化的产物：端点注解留在 controller，方法体逐字迁移至此。</p>
 */
public final class InitializationConfigService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeAccessGuard kbGuard;
    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ModelService modelService;
    private final SsrfGuard ssrfGuard;

    public InitializationConfigService(KnowledgeAccessGuard kbGuard, KnowledgeBaseService kbService, KnowledgeBaseMapper kbMapper, KnowledgeMapper knowledgeMapper, ModelService modelService, SsrfGuard ssrfGuard) {
        this.kbGuard = kbGuard;
        this.kbService = kbService;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.modelService = modelService;
        this.ssrfGuard = ssrfGuard;
    }

    // ══════════════ GET /initialization/config/:kbId ══════════════

    @GetMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> getConfig(@PathVariable("kbId") String kbId) {
        kbGuard.requireKbAccess(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        List<Model> models = new ArrayList<>();
        for (String id : List.of(ModelConnectivityTestService.orEmpty(kb.getEmbeddingModelId()), ModelConnectivityTestService.orEmpty(kb.getSummaryModelId()),
                ModelConnectivityTestService.orEmpty(kb.getVlmConfig().getModelId()))) {
            if (id.isEmpty()) {
                continue;
            }
            try {
                Model m = modelService.getModelByID(id);
                if (m != null) {
                    models.add(m);
                }
            } catch (Exception ignored) {
                // Go：Warn 后 continue
            }
        }
        Map<String, Object> body = new TreeMap<>();
        body.put("data", configResponse(models, kb, hasFiles(kbId)));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ POST /initialization/initialize/:kbId ══════════════

    @PostMapping("/api/v1/initialization/initialize/{kbId}")
    public ResponseEntity<Object> initialize(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        InitializationRequests.InitializationRequest req = InitializationRequests.bindInitializationRequest(rawBody);
        KnowledgeBase kb = kbForWrite(kbId);
        validateConfigs(req);

        List<Model> processed = new ArrayList<>();
        for (ModelDescriptor d : buildModelDescriptors(req)) {
            Model model = toModel(d);
            String existingId = findExistingModelId(kb, d.type());
            Model existing = null;
            if (!existingId.isEmpty()) {
                try {
                    existing = modelService.getModelByID(existingId);
                } catch (Exception ignored) {
                    existing = null;
                }
            }
            if (existing != null) {
                existing.setName(model.getName());
                existing.setSource(model.getSource());
                existing.setDescription(model.getDescription());
                existing.setParameters(model.getParameters());
                modelService.updateModel(existing);
                processed.add(existing);
            } else {
                modelService.createModel(model);
                processed.add(model);
            }
        }
        applyInitialization(kb, req, processed);
        saveKb(kb);

        Map<String, Object> data = new TreeMap<>();
        data.put("knowledge_base", KnowledgeBaseResponse.from(kb, kbService.retrieveDriver()));
        // Go 直接 marshal *types.Model（非 NewModelResponse：api_key 留在 parameters、无 credentials）
        data.put("models", processed.stream().map(com.ragagent.agentm.dto.InitResponses::rawModel).toList());
        Map<String, Object> body = new TreeMap<>();
        body.put("data", data);
        body.put("message", "知识库配置更新成功");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ PUT /initialization/config/:kbId ══════════════

    @PutMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> updateConfig(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        InitializationRequests.KBModelConfigRequest req = InitializationRequests.bindKBModelConfigRequest(rawBody);
        kbGuard.requireKbAccess(kbId);
        requireOwned(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }

        // Embedding 变更 + 已有文件 → 400（先于模型校验）
        if (!kb.getEmbeddingModelId().isEmpty() && !req.embeddingModelId().isEmpty()
                && !kb.getEmbeddingModelId().equals(req.embeddingModelId())
                && hasFiles(kbId)) {
            throw new BizException(AppError.badRequest("知识库中已有文件，无法修改Embedding模型"));
        }
        requireModel(req.llmModelId(), "LLM模型不存在");
        if (!req.embeddingModelId().isEmpty()) {
            requireModel(req.embeddingModelId(), "Embedding模型不存在");
        }

        kb.setSummaryModelId(req.llmModelId());
        if (!req.embeddingModelId().isEmpty()) {
            kb.setEmbeddingModelId(req.embeddingModelId());
        }

        // VLM / ASR 重置后再按请求回填
        kb.setVlmConfig(new KnowledgeBaseVlmConfig());
        KnowledgeBaseVlmConfig vlm = kb.getVlmConfig();
        JsonNode vlmReq = req.vlmConfig();
        if (vlmReq != null && req.multimodalEnabled() && !vlmReq.path("model_id").asText("").isEmpty()) {
            String vlmModelId = vlmReq.path("model_id").asText("");
            try {
                if (modelService.getModelByID(vlmModelId) != null) {
                    vlm.setEnabled(vlmReq.path("enabled").asBoolean(false));
                    vlm.setModelId(vlmModelId);
                }
            } catch (Exception ignored) {
                // Go：Warn "VLM model not found"
            }
        }
        if (!vlm.isEnabled()) {
            vlm.setModelId("");
        }
        kb.setAsrConfig(new KnowledgeBaseAsrConfig());
        KnowledgeBaseAsrConfig asr = kb.getAsrConfig();
        JsonNode asrReq = req.asrConfig();
        if (asrReq != null && asrReq.path("enabled").asBoolean(false)
                && !asrReq.path("model_id").asText("").isEmpty()) {
            try {
                if (modelService.getModelByID(asrReq.path("model_id").asText()) != null) {
                    asr.setEnabled(true);
                    asr.setModelId(asrReq.path("model_id").asText());
                    asr.setLanguage(asrReq.path("language").asText(""));
                }
            } catch (Exception ignored) {
                // Go：Warn
            }
        }

        // 文档分块
        var chunking = kb.getChunkingConfig();
        if (req.chunkSize() > 0) {
            chunking.setChunkSize(req.chunkSize());
        }
        if (req.chunkOverlap() >= 0) {
            chunking.setChunkOverlap(req.chunkOverlap());
        }
        if (req.separators() != null && !req.separators().isEmpty()) {
            chunking.setSeparators(req.separators());
        }
        chunking.setParserEngineRules(req.parserEngineRules());
        chunking.setEnableParentChild(req.enableParentChild());
        if (req.parentChunkSize() != null && req.parentChunkSize() > 0) {
            chunking.setParentChunkSize(req.parentChunkSize());
        }
        if (req.childChunkSize() != null && req.childChunkSize() > 0) {
            chunking.setChildChunkSize(req.childChunkSize());
        }
        if (req.strategy() != null) {
            chunking.setStrategy(req.strategy());
        }
        if (req.tokenLimit() != null) {
            chunking.setTokenLimit(req.tokenLimit());
        }
        if (req.languages() != null) {
            chunking.setLanguages(req.languages());
        }
        if (req.tableMetadataInstructions() != null) {
            chunking.setTableMetadataInstructions(req.tableMetadataInstructions().trim());
        }

        if (!req.multimodalEnabled()) {
            vlm.setModelId("");
        }
        if (vlmReq != null) {
            vlm.setDescriptionLanguage(vlmReq.path("description_language").asText("").trim());
            vlm.setCustomInstructions(vlmReq.path("custom_instructions").asText("").trim());
        }

        // 存储引擎：provider 兼容投影
        String provider = req.storageProvider() == null ? "" : req.storageProvider().trim().toLowerCase();
        if (provider.isEmpty()) {
            provider = "local";
        }
        List<String> supported = List.of("local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");
        if (!supported.contains(provider)) {
            throw new BizException(AppError.badRequest("Storage provider is not allowed by STORAGE_ALLOW_LIST"));
        }
        kb.setStorageProvider(provider);

        // 知识图谱
        if (req.nodeExtractEnabled()) {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", true);
            extract.put("text", req.nodeExtractText());
            extract.set("tags", MAPPER.valueToTree(req.nodeExtractTags()));
            extract.set("nodes", MAPPER.valueToTree(req.nodeExtractNodes()));
            extract.set("relations", MAPPER.valueToTree(req.nodeExtractRelations()));
            extract.put("custom_instructions", req.nodeExtractCustomInstructions().trim());
            kb.setExtractConfig(extract);
        } else if (kb.getExtractConfig() != null) {
            ((ObjectNode) kb.getExtractConfig()).put("enabled", false);
        } else {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", false);
            kb.setExtractConfig(extract);
        }

        // 问题生成
        ObjectNode qg = MAPPER.createObjectNode();
        if (req.questionGenerationEnabled()) {
            int count = req.questionGenerationCount();
            if (count <= 0) {
                count = 3;
            }
            if (count > 10) {
                count = 10;
            }
            qg.put("enabled", true);
            qg.put("question_count", count);
            qg.put("custom_instructions", req.questionGenerationInstructions().trim());
        } else {
            qg.put("enabled", false);
            qg.put("custom_instructions", req.questionGenerationInstructions().trim());
        }
        kb.setQuestionGenerationConfig(qg);

        saveKb(kb);
        Map<String, Object> body = new TreeMap<>();
        body.put("message", "配置更新成功");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** Go time.Time 的 RFC3339Nano + 服务器本地时区（同 GoTimeSerializer 逻辑）。 */

    static Map<String, String> toStringMap(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        var fields = n.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText(""));
        }
        return out;
    }

    // ══════════════ 绑定 ══════════════





    // ══════════════ 守卫与校验 ══════════════

    private KnowledgeBase kbForWrite(String kbId) {
        kbGuard.requireKbAccess(kbId);
        requireOwned(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        return kb;
    }

    private void requireOwned(String kbId) {
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role == null ? "" : role)
                .hasPermission(TenantRole.ADMIN);
        String uid = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        String creator = kb.getCreatorId() == null ? "" : kb.getCreatorId();
        if (!admin && (creator.isEmpty() || !creator.equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    private void validateConfigs(InitializationRequests.InitializationRequest req) {
        List<String[]> urls = new ArrayList<>();
        urls.add(new String[] {"LLM BaseURL", req.llmBaseUrl()});
        urls.add(new String[] {"Embedding BaseURL", req.embBaseUrl()});
        urls.add(new String[] {"Rerank BaseURL", req.rerankBaseUrl()});
        if (req.multimodal() != null && req.multimodal().get("vlm") != null) {
            urls.add(new String[] {"VLM BaseURL", req.multimodal().path("vlm").path("baseUrl").asText("")});
        }
        for (String[] u : urls) {
            if (!u[1].isEmpty()) {
                try {
                    ssrfGuard.validateURLForSSRF(u[1]);
                } catch (Exception e) {
                    throw new BizException(AppError.badRequest(
                            ssrfGuard.formatSSRFError(u[0], u[1], e)));
                }
            }
        }
        if (req.multimodalEnabled()) {
            JsonNode vlm = req.multimodal() == null ? null : req.multimodal().get("vlm");
            if (vlm == null) {
                throw new BizException(AppError.badRequest("启用多模态时需要配置VLM信息"));
            }
            String modelName = vlm.path("modelName").asText("");
            String baseUrl = vlm.path("baseUrl").asText("");
            if ("ollama".equals(vlm.path("interfaceType").asText(""))) {
                String ollama = System.getenv("OLLAMA_BASE_URL");
                baseUrl = (ollama == null ? "" : ollama) + "/v1";
            }
            if (modelName.isEmpty() || baseUrl.isEmpty()) {
                throw new BizException(AppError.badRequest("VLM配置不完整"));
            }
        }
        if (req.rerankEnabled() && (req.rerankModelName().isEmpty() || req.rerankBaseUrl().isEmpty())) {
            throw new BizException(AppError.badRequest("Rerank配置不完整"));
        }
        if (req.nodeExtractEnabled()) {
            String neo4j = System.getenv("NEO4J_ENABLE");
            if (!"true".equalsIgnoreCase(neo4j == null ? "" : neo4j)) {
                throw new BizException(AppError.badRequest("请正确配置环境变量NEO4J_ENABLE"));
            }
            if (req.nodeExtractText().isEmpty() || req.nodeExtractTags().isEmpty()) {
                throw new BizException(AppError.badRequest("Node Extractor配置不完整"));
            }
            if (req.nodeExtractNodes().isEmpty() || req.nodeExtractRelations().isEmpty()) {
                throw new BizException(AppError.badRequest("请先提取实体和关系"));
            }
        }
    }

    // ══════════════ 模型处理 ══════════════

    private record ModelDescriptor(String type, String name, String source, String description,
            String baseUrl, String apiKey, int dimension, String interfaceType) {}

    private List<ModelDescriptor> buildModelDescriptors(InitializationRequests.InitializationRequest req) {
        List<ModelDescriptor> list = new ArrayList<>();
        list.add(new ModelDescriptor("KnowledgeQA", req.llmModelName(), req.llmSource(),
                "LLM Model for Knowledge QA", req.llmBaseUrl(), req.llmApiKey(), 0, ""));
        list.add(new ModelDescriptor("Embedding", req.embModelName(), req.embSource(),
                "Embedding Model", req.embBaseUrl(), req.embApiKey(), req.embDimension(), ""));
        if (req.rerankEnabled()) {
            list.add(new ModelDescriptor("Rerank", req.rerankModelName(), "remote",
                    "Rerank Model", req.rerankBaseUrl(), req.rerankApiKey(), 0, ""));
        }
        if (req.multimodalEnabled() && req.multimodal() != null && req.multimodal().get("vlm") != null) {
            JsonNode vlm = req.multimodal().get("vlm");
            list.add(new ModelDescriptor("VLLM", vlm.path("modelName").asText(""), "remote",
                    "VLM Model", vlm.path("baseUrl").asText(""), vlm.path("apiKey").asText(""),
                    0, vlm.path("interfaceType").asText("")));
        }
        return list;
    }

    private Model toModel(ModelDescriptor d) {
        Model m = new Model();
        m.setType(d.type());
        m.setName(d.name());
        m.setSource(d.source());
        m.setDescription(d.description());
        ModelParameters p = new ModelParameters();
        p.setBaseUrl(d.baseUrl());
        p.setApiKey(d.apiKey());
        p.setInterfaceType(d.interfaceType());
        if ("Embedding".equals(d.type())) {
            p.getEmbeddingParameters().setDimension(d.dimension());
        }
        m.setParameters(p);
        m.setDisplayName("");
        m.setIsDefault(false);
        m.setStatus("active");
        // Go 的 uint 零值语义：handler 不回填 tenant → 行落 tenant_id=0，
        // 后续按租户回读找不到（golden init-get-config-after 无 llm/embedding 键即此因）
        m.setTenantId(0L);
        return m;
    }

    private String findExistingModelId(KnowledgeBase kb, String type) {
        return switch (type) {
            case "Embedding" -> ModelConnectivityTestService.orEmpty(kb.getEmbeddingModelId());
            case "KnowledgeQA" -> ModelConnectivityTestService.orEmpty(kb.getSummaryModelId());
            case "VLLM" -> ModelConnectivityTestService.orEmpty(kb.getVlmConfig().getModelId());
            default -> "";
        };
    }

    private void applyInitialization(KnowledgeBase kb, InitializationRequests.InitializationRequest req,
            List<Model> processed) {
        String embeddingId = "";
        String llmId = "";
        String vlmId = "";
        for (Model m : processed) {
            switch (m.getType()) {
                case "Embedding" -> embeddingId = m.getId();
                case "KnowledgeQA" -> llmId = m.getId();
                case "VLLM" -> vlmId = m.getId();
                default -> {
                }
            }
        }
        kb.setSummaryModelId(llmId);
        kb.setEmbeddingModelId(embeddingId);
        var chunking = kb.getChunkingConfig();
        chunking.setChunkSize(req.chunkSize());
        chunking.setChunkOverlap(req.chunkOverlap());
        chunking.setSeparators(req.separators());

        if (req.multimodalEnabled()) {
            KnowledgeBaseVlmConfig vlm = new KnowledgeBaseVlmConfig();
            vlm.setEnabled(true);
            vlm.setModelId(vlmId);
            kb.setVlmConfig(vlm);
            String storageType = req.multimodal() == null ? ""
                    : req.multimodal().path("storageType").asText("");
            // cos/minio 凭据落库段依赖部署环境（dev 无），Go 也只在段存在时触达
            if (("cos".equals(storageType) || "minio".equals(storageType))
                    && req.multimodal().get(storageType) != null) {
                kb.setStorageProvider(storageType);
            }
        } else {
            kb.setVlmConfig(new KnowledgeBaseVlmConfig());
            kb.setStorageProvider("");
        }

        if (req.nodeExtractEnabled()) {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("text", req.nodeExtractText());
            extract.set("tags", MAPPER.valueToTree(req.nodeExtractTags()));
            var nodes = extract.putArray("nodes");
            for (Object n : req.nodeExtractNodes()) {
                JsonNode node = MAPPER.valueToTree(n);
                ObjectNode copy = nodes.addObject();
                copy.put("name", node.path("name").asText(""));
                copy.set("attributes", node.get("attributes") == null
                        ? MAPPER.createArrayNode() : node.get("attributes").deepCopy());
            }
            var relations = extract.putArray("relations");
            for (Object r : req.nodeExtractRelations()) {
                JsonNode rel = MAPPER.valueToTree(r);
                ObjectNode copy = relations.addObject();
                copy.put("node1", rel.path("node1").asText(""));
                copy.put("node2", rel.path("node2").asText(""));
                copy.put("type", rel.path("type").asText(""));
            }
            kb.setExtractConfig(extract);
        }
    }

    private void saveKb(KnowledgeBase kb) {
        kbMapper.updateById(kb);
    }

    // ══════════════ GET config 响应（全 map 字母序，对照 buildConfigResponse）══════════════

    private Map<String, Object> configResponse(List<Model> models, KnowledgeBase kb,
            boolean hasFiles) {
        Map<String, Object> config = new TreeMap<>();
        config.put("hasFiles", hasFiles);
        boolean canView = canViewIntegrationSecrets();

        for (Model m : models) {
            String baseUrl = m.getParameters() == null ? "" : m.getParameters().getBaseUrl();
            if (m.isIsBuiltin() || !canView) {
                baseUrl = "";
            }
            boolean hasKey = m.getParameters() != null && !m.getParameters().getApiKey().isEmpty()
                    && !m.isIsBuiltin();
            switch (m.getType()) {
                case "KnowledgeQA" -> config.put("llm", sortedBlock(Map.of(
                        "source", ModelConnectivityTestService.orEmpty(m.getSource()),
                        "modelName", ModelConnectivityTestService.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "Embedding" -> config.put("embedding", sortedBlock(Map.of(
                        "source", ModelConnectivityTestService.orEmpty(m.getSource()),
                        "modelName", ModelConnectivityTestService.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "dimension", m.getParameters() == null ? 0
                                : m.getParameters().getEmbeddingParameters().getDimension(),
                        "credentials", Map.of("apiKey", hasKey))));
                case "Rerank" -> config.put("rerank", sortedBlock(Map.of(
                        "enabled", true,
                        "modelName", ModelConnectivityTestService.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "VLLM" -> {
                    Map<String, Object> mm = castMap(config.get("multimodal"));
                    if (mm == null) {
                        mm = new TreeMap<>();
                        mm.put("enabled", true);
                        config.put("multimodal", mm);
                    }
                    mm.put("vlm", sortedBlock(Map.of(
                            "modelName", ModelConnectivityTestService.orEmpty(m.getName()),
                            "baseUrl", baseUrl,
                            "interfaceType", m.getParameters() == null ? ""
                                    : m.getParameters().getInterfaceType(),
                            "modelId", m.getId(),
                            "credentials", Map.of("apiKey", hasKey))));
                }
                default -> {
                }
            }
        }

        String storageProvider = kb.getStorageProvider();
        boolean hasMultimodal = kb.getVlmConfig().isEnabled()
                || !kb.getStorageConfig().getSecretId().isEmpty()
                || !kb.getStorageConfig().getBucketName().isEmpty()
                || (!storageProvider.isEmpty() && !"local".equals(storageProvider));
        Map<String, Object> mm = castMap(config.get("multimodal"));
        if (mm == null) {
            mm = new TreeMap<>();
            mm.put("enabled", hasMultimodal);
            config.put("multimodal", mm);
        } else {
            mm.put("enabled", hasMultimodal);
        }
        String descLang = kb.getVlmConfig().getDescriptionLanguage();
        String customInstr = kb.getVlmConfig().getCustomInstructions();
        if ((descLang != null && !descLang.isEmpty()) || (customInstr != null && !customInstr.isEmpty())) {
            if (descLang != null && !descLang.isEmpty()) {
                mm.put("descriptionLanguage", descLang);
            }
            if (customInstr != null && !customInstr.isEmpty()) {
                mm.put("customInstructions", customInstr);
            }
        }

        if (config.get("rerank") == null) {
            config.put("rerank", sortedBlock(Map.of(
                    "enabled", false,
                    "modelName", "",
                    "baseUrl", "",
                    "credentials", Map.of("apiKey", false))));
        }

        var c = kb.getChunkingConfig();
        Map<String, Object> ds = new TreeMap<>();
        ds.put("chunkSize", c.getChunkSize());
        ds.put("chunkOverlap", c.getChunkOverlap());
        ds.put("separators", c.getSeparators());
        if (c.getStrategy() != null && !c.getStrategy().isEmpty()) {
            ds.put("strategy", c.getStrategy());
        }
        if (c.getTokenLimit() > 0) {
            ds.put("tokenLimit", c.getTokenLimit());
        }
        if (c.getLanguages() != null && !c.getLanguages().isEmpty()) {
            ds.put("languages", c.getLanguages());
        }
        if (c.getTableMetadataInstructions() != null && !c.getTableMetadataInstructions().isEmpty()) {
            ds.put("tableMetadataInstructions", c.getTableMetadataInstructions());
        }
        config.put("documentSplitting", ds);

        String effectiveProvider = kb.getStorageProvider();
        if (!kb.getStorageConfig().getSecretId().isEmpty()
                || (!effectiveProvider.isEmpty() && !"local".equals(effectiveProvider))) {
            Map<String, Object> mm2 = castMap(config.get("multimodal"));
            if (mm2 == null) {
                mm2 = new TreeMap<>();
                mm2.put("enabled", true);
                config.put("multimodal", mm2);
            }
            mm2.put("storageType", effectiveProvider);
            if ("cos".equals(effectiveProvider)) {
                Map<String, Object> cos = new TreeMap<>();
                cos.put("region", kb.getStorageConfig().getRegion());
                cos.put("bucketName", kb.getStorageConfig().getBucketName());
                cos.put("appId", kb.getStorageConfig().getAppId());
                cos.put("pathPrefix", kb.getStorageConfig().getPathPrefix());
                cos.put("credentials", Map.of(
                        "secretId", !kb.getStorageConfig().getSecretId().isEmpty(),
                        "secretKey", !kb.getStorageConfig().getSecretKey().isEmpty()));
                mm2.put("cos", cos);
            } else if ("minio".equals(effectiveProvider)) {
                mm2.put("minio", sortedBlock(Map.of(
                        "bucketName", kb.getStorageConfig().getBucketName(),
                        "pathPrefix", kb.getStorageConfig().getPathPrefix())));
            }
        }

        JsonNode extract = kb.getExtractConfig();
        if (extract != null) {
            Map<String, Object> ne = new TreeMap<>();
            ne.put("enabled", extract.path("enabled").asBoolean(false));
            ne.put("text", extract.path("text").asText(""));
            ne.put("tags", extract.get("tags"));
            ne.put("nodes", extract.get("nodes"));
            ne.put("relations", extract.get("relations"));
            String ci = extract.path("custom_instructions").asText("");
            if (!ci.isEmpty()) {
                ne.put("customInstructions", ci);
            }
            config.put("nodeExtract", ne);
        } else {
            config.put("nodeExtract", sortedBlock(Map.of("enabled", false)));
        }

        JsonNode qg = kb.getQuestionGenerationConfig();
        if (qg != null) {
            config.put("questionGeneration", sortedBlock(Map.of(
                    "enabled", qg.path("enabled").asBoolean(false),
                    "questionCount", qg.path("question_count").asInt(0),
                    "customInstructions", qg.path("custom_instructions").asText(""))));
        } else {
            config.put("questionGeneration", sortedBlock(Map.of("enabled", false)));
        }
        return config;
    }

    // ══════════════ 小工具 ══════════════

    private boolean hasFiles(String kbId) {
        Long tid = TenantContext.currentTenantId();
        Long count = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(tid != null, Knowledge::getTenantId, tid)
                .isNull(Knowledge::getDeletedAt));
        return count != null && count > 0;
    }

    private void requireModel(String id, String message) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest(message));
        }
        try {
            Model m = modelService.getModelByID(id);
            if (m != null) {
                return;
            }
        } catch (Exception ignored) {
            // 落到下方 400
        }
        throw new BizException(AppError.badRequest(message));
    }

    private boolean canViewIntegrationSecrets() {
        String role = TenantContext.currentRole();
        if (TenantRole.fromString(role == null ? "" : role).hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        var scope = APIKeyScopeContext.current();
        return scope != null && (scope.fullAccess() || scope.hasCapability("manage_tenant_settings"));
    }

    /** Map.of 乱序 → TreeMap 重排（Go map 序列化 = 键字母序）。 */
    private static Map<String, Object> sortedBlock(Map<String, Object> entries) {
        return new TreeMap<>(entries);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }



}
