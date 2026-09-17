package com.ragagent.model.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.model.domain.Model;
import com.ragagent.model.mapper.ModelMapper;
import com.ragagent.model.mapper.ModelUsageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go internal/application/service/model.go 的 modelService（阶段 2 子集：
 * 配置 CRUD + credentials 子资源 + 删除守卫；GetChatModel 等运行时工厂随阶段 7 agent 引擎）。
 *
 * 语义要点（golden 已锁定）：
 * - CreateModel：source=remote → active；其他 → downloading（Go 会起 ollama 拉取协程，
 *   阶段 2 无 OllamaService → 保持 downloading 不轮转，记录为已知差异 §9）
 * - GetModelByID：downloading → 500 "model is currently downloading"；download_failed → 500
 * - UpdateModel：内置模型仅系统管理员可改（403），系统管理员修改时 managed_by 清空
 * - 凭证永不经 PUT /models/:id 正文（controller 层快照保留，对照 handler）
 * - DeleteModel：内置 400；被 KB/agent/长期记忆引用 → 400 code=2300 + usage details
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long USAGE_LIST_LIMIT = 50;

    public static final String SOURCE_REMOTE = "remote";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_DOWNLOAD_FAILED = "download_failed";

    private final ModelMapper modelMapper;
    private final ModelUsageMapper usageMapper;
    private final TenantService tenantService;

    public ModelService(ModelMapper modelMapper, ModelUsageMapper usageMapper, TenantService tenantService) {
        this.modelMapper = modelMapper;
        this.usageMapper = usageMapper;
        this.tenantService = tenantService;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────

    /** 对照 CreateModel（本地源的后台拉取协程见类注释的阶段性说明） */
    public Model createModel(Model model) {
        log.info("Creating model: {}, type: {}, source: {}", model.getName(), model.getType(), model.getSource());
        if (model.getId() == null || model.getId().isEmpty()) {
            model.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        if (SOURCE_REMOTE.equals(model.getSource())) {
            model.setStatus(STATUS_ACTIVE);
        } else {
            model.setStatus(STATUS_DOWNLOADING);
        }
        modelMapper.insert(model);
        log.info("Model created successfully: {}", model.getId());
        return model;
    }

    /**
     * 对照 GetModelByID：带状态闸门（downloading/download_failed → 500）。
     * @throws ModelNotFoundException 不存在
     * @throws BizException 500（1007）状态异常
     */
    public Model getModelByID(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(new AppError(1000, "model ID cannot be empty", null, 500));
        }
        Model model = getByIdVisible(tenantId(), id);
        if (model == null) {
            throw new ModelNotFoundException();
        }
        return switch (model.getStatus()) {
            case STATUS_ACTIVE -> model;
            case STATUS_DOWNLOADING ->
                    throw new BizException(new AppError(1007, "model is currently downloading", null, 500));
            case STATUS_DOWNLOAD_FAILED ->
                    throw new BizException(new AppError(1007, "model download failed", null, 500));
            default -> throw new BizException(new AppError(1007, "abnormal model status", null, 500));
        };
    }

    /** 对照 repo.GetByID：WHERE (tenant_id = ? OR is_builtin = true) AND deleted_at IS NULL */
    public Model getByIdVisible(long tenantId, String id) {
        return modelMapper.selectOne(new LambdaQueryWrapper<Model>()
                .eq(Model::getId, id)
                .and(w -> w.eq(Model::getTenantId, tenantId).or().eq(Model::isIsBuiltin, true))
                .isNull(Model::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 对照 ListModels：无排序（DB 自然序，与 Go 一致） */
    public List<Model> listModels() {
        return modelMapper.selectList(new LambdaQueryWrapper<Model>()
                .and(w -> w.eq(Model::getTenantId, tenantId()).or().eq(Model::isIsBuiltin, true))
                .isNull(Model::getDeletedAt));
    }

    /**
     * 对照 UpdateModel：内置模型守卫 + 系统管理员修改时清空 managed_by。
     * 注意 model 须为"读-改-写"后的完整对象（controller 负责字段合并）。
     */
    public Model updateModel(Model model) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, model.getId());
        if (existing != null && existing.isIsBuiltin()) {
            if (!TenantContext.isSystemAdmin()) {
                log.warn("Non-system-admin attempted to update builtin model: {}", model.getId());
                throw new BizException(AppError.forbidden("only system administrators can update builtin models"));
            }
            // UI 编辑 = 显式运行时覆盖：清 YAML 托管标记，防止启动对账静默覆盖
            model.setTenantId(existing.getTenantId());
            model.setIsBuiltin(true);
            model.setManagedBy("");
        }
        modelMapper.updateById(model);
        log.info("Model updated successfully: {}", model.getId());
        return model;
    }

    // ── credentials 子资源 ────────────────────────────────────────────────

    /** 对照 UpdateModelCredentials：仅写入非空且变化的值；内置模型需系统管理员 */
    public Model updateModelCredentials(String id, String apiKey, String appSecret) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin() && !TenantContext.isSystemAdmin()) {
            throw new BizException(AppError.forbidden(
                    "only system administrators can modify builtin model credentials"));
        }
        boolean changed = false;
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals(existing.getParameters().getApiKey())) {
            existing.getParameters().setApiKey(apiKey);
            changed = true;
        }
        if (appSecret != null && !appSecret.isEmpty()
                && !appSecret.equals(existing.getParameters().getAppSecret())) {
            existing.getParameters().setAppSecret(appSecret);
            changed = true;
        }
        if (!changed) {
            return existing;
        }
        if (existing.isIsBuiltin()) {
            existing.setManagedBy("");
        }
        modelMapper.updateById(existing);
        log.info("Model credentials updated: id={}", id);
        return existing;
    }

    /** 对照 ClearModelCredential：幂等清除单字段 */
    public void clearModelCredential(String id, String field) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin() && !TenantContext.isSystemAdmin()) {
            throw new BizException(AppError.forbidden(
                    "only system administrators can modify builtin model credentials"));
        }
        boolean changed = switch (field) {
            case "api_key" -> {
                if (!existing.getParameters().getApiKey().isEmpty()) {
                    existing.getParameters().setApiKey("");
                    yield true;
                }
                yield false;
            }
            case "app_secret" -> {
                if (!existing.getParameters().getAppSecret().isEmpty()) {
                    existing.getParameters().setAppSecret("");
                    yield true;
                }
                yield false;
            }
            default -> throw new BizException(
                    new AppError(1000, "unknown credential field: " + field, null, 500));
        };
        if (!changed) {
            return;
        }
        if (existing.isIsBuiltin()) {
            existing.setManagedBy("");
        }
        modelMapper.updateById(existing);
        log.info("Model credential cleared by user: id={} field={}", id, field);
    }

    // ── 删除守卫 ─────────────────────────────────────────────────────────

    /** 对照 DeleteModel */
    public void deleteModel(String id) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin()) {
            log.warn("Attempted to delete builtin model: {}", id);
            throw new BizException(AppError.badRequest("builtin models cannot be deleted"));
        }
        JsonNode usage = getModelUsageDetails(tid, id);
        if (inUse(usage)) {
            long kbCount = usage.get("knowledge_base_total").asLong();
            long agentCount = usage.get("agent_total").asLong();
            boolean memory = usage.get("long_term_memory").get("bindings").size() > 0;
            log.warn("Model {} is in use: kb={} agent={} memory={}", id, kbCount, agentCount, memory);
            throw new BizException(new AppError(2300, formatInUseMessage(kbCount, agentCount, memory), usage, 400));
        }
        // GORM 软删除：UPDATE deleted_at = now
        modelMapper.update(null, new UpdateWrapper<Model>()
                .eq("id", id)
                .eq("tenant_id", tid)
                .set("deleted_at", OffsetDateTime.now(ZoneOffset.UTC)));
        log.info("Model deleted successfully: {}", id);
    }

    private static boolean inUse(JsonNode usage) {
        return usage.get("knowledge_base_total").asLong() > 0
                || usage.get("agent_total").asLong() > 0
                || usage.get("knowledge_bases").size() > 0
                || usage.get("agents").size() > 0
                || usage.get("long_term_memory").get("bindings").size() > 0;
    }

    /** 对照 formatModelInUseMessage */
    static String formatInUseMessage(long kbCount, long agentCount, boolean memory) {
        List<String> parts = new ArrayList<>();
        if (kbCount > 0) {
            parts.add(kbCount + " knowledge base(s)");
        }
        if (agentCount > 0) {
            parts.add(agentCount + " agent(s)");
        }
        if (memory) {
            parts.add("long-term memory");
        }
        return "model is used by " + String.join(" and ", parts)
                + "; reconfigure or remove those references before deleting";
    }

    /**
     * 对照 getModelUsageDetails：KB/agent 引用 + 空间长期记忆模型绑定。
     * 返回 ObjectNode（字段序 = Go struct 序），作为 2300 错误的 details 原样输出。
     */
    public JsonNode getModelUsageDetails(long tid, String modelId) {
        ObjectNode details = MAPPER.createObjectNode();
        ArrayNode kbs = details.putArray("knowledge_bases");
        ArrayNode agents = details.putArray("agents");
        ObjectNode memory = details.putObject("long_term_memory");
        ArrayNode memoryBindings = memory.putArray("bindings");

        long kbTotal = 0;
        for (Map<String, Object> row : usageMapper.listKnowledgeBaseRows(tid)) {
            List<String> bindings = kbBindings(row, modelId);
            if (bindings.isEmpty()) {
                continue;
            }
            kbTotal++;
            if (kbTotal <= USAGE_LIST_LIMIT) {
                ObjectNode r = kbs.addObject();
                r.put("id", String.valueOf(row.get("id")));
                r.put("name", String.valueOf(row.get("name")));
                ArrayNode b = r.putArray("bindings");
                bindings.forEach(b::add);
            }
        }

        long agentTotal = 0;
        for (Map<String, Object> row : usageMapper.listCustomAgentRows(tid)) {
            List<String> bindings = agentBindings(row, modelId);
            if (bindings.isEmpty()) {
                continue;
            }
            agentTotal++;
            if (agentTotal <= USAGE_LIST_LIMIT) {
                ObjectNode r = agents.addObject();
                r.put("id", String.valueOf(row.get("id")));
                r.put("name", String.valueOf(row.get("name")));
                ArrayNode b = r.putArray("bindings");
                bindings.forEach(b::add);
            }
        }

        var tenant = tenantService.getTenantById(tid);
        if (tenant != null && tenant.getMemoryConfig() != null) {
            JsonNode memoryConfig = tenant.getMemoryConfig();
            // 两个记忆模型钉都要查：删任一会让空间指向不存在的模型（对照 Go 注释）
            if (modelId.equals(text(memoryConfig.get("embedding_model_id")))) {
                memoryBindings.add("embedding_model");
            }
            if (modelId.equals(text(memoryConfig.get("extract_model_id")))) {
                memoryBindings.add("extract_model");
            }
        }

        details.put("knowledge_base_total", kbTotal);
        details.put("agent_total", agentTotal);
        return details;
    }

    /** 对照 knowledgeBaseModelUsageBindings（绑定序固定） */
    private static List<String> kbBindings(Map<String, Object> row, String modelId) {
        List<String> bindings = new ArrayList<>();
        if (modelId.equals(stringOrNull(row.get("embedding_model_id")))) {
            bindings.add("embedding_model");
        }
        if (modelId.equals(stringOrNull(row.get("summary_model_id")))) {
            bindings.add("summary_model");
        }
        if (modelId.equals(jsonField(row.get("image_processing_config"), "model_id"))) {
            bindings.add("image_processing_model");
        }
        if (modelId.equals(jsonField(row.get("vlm_config"), "model_id"))) {
            bindings.add("vlm_model");
        }
        if (modelId.equals(jsonField(row.get("asr_config"), "model_id"))) {
            bindings.add("asr_model");
        }
        if (modelId.equals(jsonField(row.get("wiki_config"), "synthesis_model_id"))) {
            bindings.add("wiki_synthesis_model");
        }
        return bindings;
    }

    /** 对照 customAgentModelUsageBindings（绑定序固定） */
    private static List<String> agentBindings(Map<String, Object> row, String modelId) {
        List<String> bindings = new ArrayList<>();
        Object configRaw = row.get("config");
        JsonNode config = parseJson(configRaw);
        if (config == null) {
            return bindings;
        }
        if (modelId.equals(text(config.get("model_id")))) {
            bindings.add("chat_model");
        }
        if (modelId.equals(text(config.get("rerank_model_id")))) {
            bindings.add("rerank_model");
        }
        if (modelId.equals(text(config.get("vlm_model_id")))) {
            bindings.add("vlm_model");
        }
        if (modelId.equals(text(config.get("asr_model_id")))) {
            bindings.add("asr_model");
        }
        if (modelId.equals(text(config.get("query_understand_model_id")))) {
            bindings.add("query_understand_model");
        }
        JsonNode followUps = config.get("question_suggestions");
        if (followUps != null && followUps.get("follow_ups") != null) {
            if (modelId.equals(text(followUps.get("follow_ups").get("model_id")))) {
                bindings.add("follow_up_model");
            }
        }
        return bindings;
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static JsonNode parseJson(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof JsonNode node) {
                return node;
            }
            return MAPPER.readTree(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonField(Object raw, String field) {
        JsonNode node = parseJson(raw);
        return node == null ? null : text(node.get(field));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 模型不存在（对照 Go ErrModelNotFound → handler 404 "Model not found"） */
    public static class ModelNotFoundException extends RuntimeException {
    }
}
