package com.ragagent.model.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 对照 Go types/builtin_models_config.go LoadBuiltinModelsConfig：
 * 声明式内置模型（config/builtin_models.yaml，env BUILTIN_MODELS_CONFIG 可覆盖）与
 * models 表的 YAML 托管切片（managed_by='yaml'）对账。
 *
 * 生命周期契约（逐条对照 Go 注释）：
 * - 只写 managed_by='yaml' 行；UI/API/SQL 创建的同名行（managed_by=''）保留不动
 * - 逐条 UPSERT（deleted_at 强制复位 NULL）；运行时覆盖（系统管理员编辑清空 managed_by）保留
 * - 对账清扫：文件里消失且 managed_by='yaml' 的行软删除
 * - is_default 维持 (tenant_id, type) 桶内唯一
 * - 文件缺失/目录/解析失败 → no-op（清扫不跑）
 *
 * 阶段说明：Java 仓默认不捆绑 builtin_models.yaml（Go 仓同样没有）→ 默认 no-op。
 */
@Component
public class BuiltinModelsReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinModelsReconciler.class);
    public static final String MANAGED_BY_YAML = "yaml";
    private static final long DEFAULT_TENANT_ID = 10000;
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ModelMapper modelMapper;

    public BuiltinModelsReconciler(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        String pathEnv = System.getenv("BUILTIN_MODELS_CONFIG");
        Path path = Path.of(pathEnv != null && !pathEnv.isEmpty() ? pathEnv : "./config/builtin_models.yaml");
        try {
            if (!Files.isRegularFile(path)) {
                log.info("[builtin-models] config not present at {}; skipping", path);
                return;
            }
            reconcile(path);
        } catch (Exception e) {
            log.warn("[builtin-models] reconcile failed: {}; continuing", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void reconcile(Path path) throws Exception {
        String raw = Files.readString(path);
        String expanded = interpolateEnv(raw);
        Map<String, Object> file = new Yaml().load(expanded);
        List<Map<String, Object>> entries = file == null ? null
                : (List<Map<String, Object>>) file.get("builtin_models");
        if (entries == null) {
            entries = List.of();
        }

        java.util.Set<String> yamlIds = new java.util.HashSet<>();
        int applied = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            String id = str(e.get("id"));
            if (id.isEmpty() || id.length() > 64) {
                log.warn("[builtin-models] WARN: entry {} has empty/oversized id; skipping", i);
                continue;
            }
            String type = str(e.get("type"));
            if (!List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR").contains(type)) {
                log.warn("[builtin-models] WARN: entry {} ({}) has unknown type \"{}\"; skipping",
                        i, id, type);
                continue;
            }
            // 运行时覆盖保留（含软删除的手工行，对照 Unscoped 检查）
            Model existing = modelMapper.selectById(id);
            if (existing != null && !MANAGED_BY_YAML.equals(str(existing.getManagedBy()))) {
                log.info("[builtin-models] preserving runtime override: id={}", id);
                continue;
            }

            Model m = new Model();
            m.setId(id);
            m.setTenantId(num(e.get("tenant_id")) == 0 ? DEFAULT_TENANT_ID : num(e.get("tenant_id")));
            m.setName(str(e.get("name")));
            m.setType(type);
            m.setSource(str(e.get("source")).isEmpty() ? "remote" : str(e.get("source")));
            m.setDescription(str(e.get("description")));
            m.setParameters(parseParameters(e.get("parameters")));
            m.setIsDefault(bool(e.get("is_default")));
            m.setIsBuiltin(true);
            m.setManagedBy(MANAGED_BY_YAML);
            String status = str(e.get("status"));
            m.setStatus(status.isEmpty() ? "active" : status);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);

            if (m.isIsDefault()) {
                // 同 (tenant_id, type) 桶内清其他默认（排除自身）
                modelMapper.update(null, new UpdateWrapper<Model>()
                        .eq("tenant_id", m.getTenantId())
                        .eq("type", m.getType())
                        .ne("id", m.getId())
                        .eq("is_default", true)
                        .set("is_default", false));
            }
            upsert(m);
            applied++;
            yamlIds.add(id);
            log.info("[builtin-models] upserted: id={} name={} type={}", id, m.getName(), type);
        }

        // 漂移清扫：YAML 托管且不在文件中的行软删除
        int pruned = 0;
        List<Model> managed = modelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Model>()
                        .eq(Model::getManagedBy, MANAGED_BY_YAML)
                        .isNull(Model::getDeletedAt));
        for (Model row : managed) {
            if (!yamlIds.contains(row.getId())) {
                modelMapper.update(null, new UpdateWrapper<Model>()
                        .eq("id", row.getId())
                        .set("deleted_at", OffsetDateTime.now(ZoneOffset.UTC)));
                pruned++;
            }
        }
        log.info("[builtin-models] applied: {} upserted, {} pruned from {}", applied, pruned, path);
    }

    /** INSERT ... ON CONFLICT(id) DO UPDATE（对照 clause.OnConflict DoUpdates 列集） */
    private void upsert(Model m) {
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Model> uw =
                new UpdateWrapper<Model>().eq("id", m.getId());
        // MyBatis-Plus 无原生 upsert：先按 id 尝试更新，影响 0 行则插入（PK 冲突兜底）
        // 并发安全由 PK 唯一约束保证：插入冲突时重试更新
        int updated = modelMapper.update(m, uw);
        if (updated == 0) {
            try {
                modelMapper.insert(m);
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                modelMapper.update(m, new UpdateWrapper<Model>().eq("id", m.getId()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ModelParameters parseParameters(Object raw) {
        ModelParameters p = new ModelParameters();
        if (!(raw instanceof Map<?, ?> map)) {
            return p;
        }
        p.setBaseUrl(str(map.get("base_url")));
        p.setApiKey(str(map.get("api_key")));
        p.setInterfaceType(str(map.get("interface_type")));
        if (map.get("embedding_parameters") instanceof Map<?, ?> ep) {
            ModelParameters.EmbeddingParameters embedding = new ModelParameters.EmbeddingParameters();
            embedding.setDimension((int) num(ep.get("dimension")));
            embedding.setTruncatePromptTokens((int) num(ep.get("truncate_prompt_tokens")));
            embedding.setSupportsDimensionOverride(bool(ep.get("supports_dimension_override")));
            p.setEmbeddingParameters(embedding);
        }
        p.setParameterSize(str(map.get("parameter_size")));
        p.setProvider(str(map.get("provider")));
        if (map.get("extra_config") instanceof Map<?, ?> ec) {
            java.util.Map<String, String> extra = new java.util.TreeMap<>();
            ec.forEach((k, v) -> extra.put(String.valueOf(k), str(v)));
            p.setExtraConfig(extra);
        }
        if (map.get("custom_headers") instanceof Map<?, ?> ch) {
            java.util.Map<String, String> headers = new java.util.TreeMap<>();
            ch.forEach((k, v) -> headers.put(String.valueOf(k), str(v)));
            p.setCustomHeaders(headers);
        }
        p.setSupportsVision(bool(map.get("supports_vision")));
        p.setContextWindow((int) num(map.get("context_window")));
        p.setMaxOutputTokens((int) num(map.get("max_output_tokens")));
        p.setMaxConcurrency((int) num(map.get("max_concurrency")));
        p.setAppId(str(map.get("app_id")));
        p.setAppSecret(str(map.get("app_secret")));
        return p;
    }

    /** 对照 interpolateBuiltinModelEnv：${NAME} 用 env 替换，未设置保留字面量 */
    static String interpolateEnv(String s) {
        java.util.regex.Matcher m = ENV_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = System.getenv(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v != null && !v.isEmpty() ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private static boolean bool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    /** id 生成占位：保持与 ModelService 的 UUID 语义一致（YAML 条目自带 id，不走这里） */
    static String newId() {
        return UUID.randomUUID().toString();
    }
}
