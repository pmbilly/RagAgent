package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.mapper.AgentQuestionMapper;
import com.ragagent.agentm.mapper.CustomAgentMapper;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.org.mapper.TenantDisabledSharedAgentMapper;

/**
 * agents CRUD 家族 service（对照 Go internal/application/service/custom_agent.go +
 * internal/handler/custom_agent.go 的可复刻子集）。
 *
 * <p>已知降级（均不在 golden/A-B 场景内，报告与测试注释均有声明）：</p>
 * <ul>
 *   <li>imService.DeleteChannelsByAgent：im 模块未翻译，Java 侧 no-op（Go 在无渠道时
 *       同样立即返回，无渠道空间里 HTTP 契约等价）。</li>
 *   <li>suggested-questions 的 wiki fallback（ListRecentForSuggestions）未实现。</li>
 *   <li>kb_selection_mode=all 的能力过滤只实现 quick-answer 的 vector/keyword any-of
 *       基础面，allowed_tools→capability 派生表未移植。</li>
 * </ul>
 */
@Service
public class CustomAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 suggestionDefaultLimit / suggestionMaxLimit。 */
    private static final int SUGGESTION_DEFAULT_LIMIT = 6;
    private static final int SUGGESTION_MAX_LIMIT = 30;

    private final CustomAgentMapper agentMapper;
    private final AgentQuestionMapper questionMapper;
    private final TenantDisabledSharedAgentMapper disabledMapper;
    private final com.ragagent.auth.service.UserService userService;
    private final KnowledgeBaseService kbService;
    private final BuiltinAgentRegistry registry;

    public CustomAgentService(CustomAgentMapper agentMapper,
            AgentQuestionMapper questionMapper,
            TenantDisabledSharedAgentMapper disabledMapper,
            com.ragagent.auth.service.UserService userService,
            KnowledgeBaseService kbService,
            BuiltinAgentRegistry registry) {
        this.agentMapper = agentMapper;
        this.questionMapper = questionMapper;
        this.disabledMapper = disabledMapper;
        this.userService = userService;
        this.kbService = kbService;
        this.registry = registry;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ═══════════════════ 查询 ═══════════════════

    /** 对照 GetAgentByID（内建优先 DB，回落注册表）。config 树已 EnsureDefaults。 */
    public Result getAgentByID(String id, String locale) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("agent ID cannot be empty"));
        }
        long tenant = tenantId();
        CustomAgentEntity row = agentMapper.getByIDAndTenant(id, tenant);
        if (row != null) {
            ObjectNode cfg = AgentConfigJson.ensureDefaults(parse(row.getConfig()));
            if (BuiltinAgentRegistry.isBuiltinAgentID(id)) {
                applyLocalization(id, row, locale);
            }
            return new Result(row, cfg);
        }
        if (BuiltinAgentRegistry.isBuiltinAgentID(id)) {
            ObjectNode built = registry.builtinAgentConfig(id, locale);
            if (built != null) {
                return virtualAgent(built, id, tenant, true);
            }
        }
        throw new BizException(AppError.notFound("Agent not found"));
    }

    /** 响应层组合：row + 已 defaults 的 config 树。 */
    public record Result(CustomAgentEntity row, ObjectNode config) {}

    private Result virtualAgent(ObjectNode built, String id, long tenant, boolean builtin) {
        CustomAgentEntity virtual = new CustomAgentEntity();
        virtual.setId(id);
        virtual.setName(built.path("name").asText(""));
        virtual.setDescription(built.path("description").asText(""));
        virtual.setAvatar(built.path("avatar").asText(""));
        virtual.setBuiltin(builtin);
        virtual.setTenantId(tenant);
        return new Result(virtual, (ObjectNode) built.get("config"));
    }

    /**
     * 对照 ListAgents：DB 行（created_at DESC）→ 内建固定序在前（DB 行优先，本地化覆盖）→
     * 非内建按 repo 序 → creator 筛选 → disabled_own_agent_ids → creator_name 回填。
     */
    public ListResult listAgents(String creatorFilter, String locale) {
        long tenant = tenantId();
        List<CustomAgentEntity> all = agentMapper.listByTenant(tenant);
        Set<String> builtinInDb = new HashSet<>();
        List<Result> prepared = new ArrayList<>();
        for (CustomAgentEntity row : all) {
            ObjectNode cfg = AgentConfigJson.ensureDefaults(parse(row.getConfig()));
            if (BuiltinAgentRegistry.isBuiltinAgentID(row.getId())) {
                builtinInDb.add(row.getId());
                applyLocalization(row.getId(), row, locale);
            }
            prepared.add(new Result(row, cfg));
        }
        List<Result> result = new ArrayList<>();
        for (String builtinId : registry.orderedIds()) {
            if (builtinInDb.contains(builtinId)) {
                prepared.stream().filter(r -> r.row().getId().equals(builtinId))
                        .findFirst().ifPresent(result::add);
            } else {
                ObjectNode built = registry.builtinAgentConfig(builtinId, locale);
                if (built != null) {
                    result.add(virtualAgent(built, builtinId, tenant, true));
                }
            }
        }
        for (Result r : prepared) {
            if (!BuiltinAgentRegistry.isBuiltinAgentID(r.row().getId())) {
                result.add(r);
            }
        }

        // creator=mine/others 筛选（内建恒保留；CreatedBy=="" 的行被丢弃）
        if ("mine".equals(creatorFilter) || "others".equals(creatorFilter)) {
            String caller = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
            List<Result> filtered = new ArrayList<>();
            for (Result r : result) {
                if (r.row().isBuiltin()) {
                    filtered.add(r);
                    continue;
                }
                String by = r.row().getCreatedBy() == null ? "" : r.row().getCreatedBy();
                if (by.isEmpty()) {
                    continue;
                }
                if ("mine".equals(creatorFilter) && by.equals(caller)) {
                    filtered.add(r);
                } else if ("others".equals(creatorFilter) && !by.equals(caller)) {
                    filtered.add(r);
                }
            }
            result = filtered;
        }

        // disabled_own_agent_ids（tenant_id = source_tenant_id = 当前空间的行）
        List<String> disabledOwn = new ArrayList<>();
        for (TenantDisabledSharedAgentMapper.DisabledRow row : disabledMapper.listByTenant(tenant)) {
            if (row.getSourceTenantId() != null && row.getSourceTenantId() == tenant) {
                disabledOwn.add(row.getAgentId());
            }
        }

        enrichCreatorNames(result);
        return new ListResult(result, disabledOwn);
    }

    public record ListResult(List<Result> agents, List<String> disabledOwnIds) {}

    /** 对照 ApplyBuiltinAgentLocalization：YAML 的 locale name/description + avatar 无条件覆盖。 */
    private void applyLocalization(String id, CustomAgentEntity row, String locale) {
        ObjectNode built = registry.builtinAgentConfig(id, locale);
        if (built == null) {
            return;
        }
        if (!built.path("name").asText("").isEmpty()) {
            row.setName(built.path("name").asText());
        }
        if (!built.path("description").asText("").isEmpty()) {
            row.setDescription(built.path("description").asText());
        }
        row.setAvatar(built.path("avatar").asText());
    }

    /** 对照 enrichAgentCreatorNames：内建/空 created_by 跳过，命中用户则 username（回落 email）。 */
    private void enrichCreatorNames(List<Result> agents) {
        Set<String> ids = new HashSet<>();
        for (Result r : agents) {
            if (!r.row().isBuiltin() && r.row().getCreatedBy() != null
                    && !r.row().getCreatedBy().isEmpty()) {
                ids.add(r.row().getCreatedBy());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, com.ragagent.auth.domain.User> users;
        try {
            users = userService.getUsersByIds(new ArrayList<>(ids));
        } catch (Exception e) {
            return; // Go：Warnf 后返回
        }
        for (Result r : agents) {
            if (r.row().isBuiltin() || r.row().getCreatedBy() == null
                    || r.row().getCreatedBy().isEmpty()) {
                continue;
            }
            com.ragagent.auth.domain.User u = users.get(r.row().getCreatedBy());
            if (u == null) {
                continue;
            }
            String name = u.getUsername() != null && !u.getUsername().isEmpty()
                    ? u.getUsername() : (u.getEmail() == null ? "" : u.getEmail());
            r.row().setCreatorName(name);
        }
    }

    // ═══════════════════ 写路径 ═══════════════════

    /** 对照 CreateAgent（service 校验 + 落库；config 已 EnsureDefaults）。 */
    public Result createAgent(String name, String description, String avatar, ObjectNode config) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("agent name is required"));
        }
        CustomAgentEntity agent = new CustomAgentEntity();
        agent.setId(UUID.randomUUID().toString());
        agent.setName(name);
        agent.setDescription(description == null ? "" : description);
        agent.setAvatar(avatar == null ? "" : avatar);
        agent.setTenantId(tenantId());
        String uid = TenantContext.currentUserId();
        if (uid != null && !isSyntheticUserId(uid)) {
            agent.setCreatedBy(uid);
        } else {
            agent.setCreatedBy("");
        }
        OffsetDateTime now = OffsetDateTime.now();
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agent.setBuiltin(false);
        if (config.path("agent_mode").asText("").isEmpty()) {
            config.put("agent_mode", "quick-answer");
        }
        AgentConfigJson.ensureDefaults(config);
        String err = AgentConfigJson.validateSuggestions(config);
        if (err != null) {
            throw new BizException(AppError.badRequest(err));
        }
        agent.setConfig(config.toString());
        agentMapper.insertAgent(agent);
        return new Result(agent, config);
    }

    /** 对照 UpdateAgent（含内建 config-only 更新分支）。 */
    public Result updateAgent(String id, String name, String description, String avatar,
            ObjectNode config, String locale) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("agent ID cannot be empty"));
        }
        long tenant = tenantId();
        if (BuiltinAgentRegistry.isBuiltinAgentID(id)) {
            return updateBuiltinAgent(id, config, tenant);
        }
        CustomAgentEntity existing = agentMapper.getByIDAndTenant(id, tenant);
        if (existing == null) {
            throw new BizException(AppError.notFound("Agent not found"));
        }
        if (existing.isBuiltin()) {
            throw new BizException(AppError.forbidden("Cannot modify built-in agent"));
        }
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("agent name is required"));
        }
        existing.setName(name);
        existing.setDescription(description == null ? "" : description);
        existing.setAvatar(avatar == null ? "" : avatar);
        existing.setUpdatedAt(OffsetDateTime.now());
        return persistWithDefaults(existing, config, false);
    }

    /** 对照 updateBuiltinAgent：只更新 config，保留 DB 基础信息；无行则创建。 */
    private Result updateBuiltinAgent(String id, ObjectNode config, long tenant) {
        BuiltinAgentRegistry.Entry entry = registry.entry(id);
        if (entry == null) {
            throw new BizException(AppError.notFound("Agent not found"));
        }
        CustomAgentEntity existing = agentMapper.getByIDAndTenant(id, tenant);
        if (existing != null) {
            existing.setUpdatedAt(OffsetDateTime.now());
            Result r = persistWithDefaults(existing, config, false);
            applyLocalization(id, existing, currentLocale());
            return r;
        }
        // 无 DB 行 → 创建（基础信息取 YAML default locale，config 来自请求）
        String[] dflt = registry.resolveI18n(entry, "");
        CustomAgentEntity created = new CustomAgentEntity();
        created.setId(id);
        created.setName(dflt[0]);
        created.setDescription(dflt[1]);
        created.setAvatar(entry.avatar());
        created.setBuiltin(true);
        created.setTenantId(tenant);
        created.setCreatedBy("");
        created.setCreatedAt(OffsetDateTime.now());
        created.setUpdatedAt(OffsetDateTime.now());
        Result r = persistWithDefaults(created, config, true);
        applyLocalization(id, created, currentLocale());
        return r;
    }

    /** EnsureDefaults + Validate + 落库（Go 三处重复段的合并位）。 */
    private Result persistWithDefaults(CustomAgentEntity entity, ObjectNode config, boolean insert) {
        AgentConfigJson.ensureDefaults(config);
        String err = AgentConfigJson.validateSuggestions(config);
        if (err != null) {
            throw new BizException(AppError.badRequest(err));
        }
        entity.setConfig(config.toString());
        if (insert) {
            agentMapper.insertAgent(entity);
        } else {
            agentMapper.updateAgent(entity);
        }
        return new Result(entity, config);
    }

    /** 对照 DeleteAgent（im 渠道清理 no-op 降级，见类注释）。 */
    public void deleteAgent(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("agent ID cannot be empty"));
        }
        if (BuiltinAgentRegistry.isBuiltinAgentID(id)) {
            throw new BizException(AppError.forbidden("Cannot delete built-in agent"));
        }
        long tenant = tenantId();
        CustomAgentEntity existing = agentMapper.getByIDAndTenant(id, tenant);
        if (existing == null) {
            throw new BizException(AppError.notFound("Agent not found"));
        }
        if (existing.isBuiltin()) {
            throw new BizException(AppError.forbidden("Cannot delete built-in agent"));
        }
        agentMapper.softDelete(id, tenant);
    }

    /** 对照 CopyAgent：config 深拷贝、名字 + " (副本)"、归属当前调用者。 */
    public Result copyAgent(String id, String locale) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("agent ID cannot be empty"));
        }
        Result source = getAgentByID(id, locale);
        CustomAgentEntity created = new CustomAgentEntity();
        created.setId(UUID.randomUUID().toString());
        created.setName(source.row().getName() + " (副本)");
        created.setDescription(source.row().getDescription());
        created.setAvatar(source.row().getAvatar());
        created.setBuiltin(false);
        created.setTenantId(tenantId());
        created.setConfig(source.config().toString());
        created.setCreatedAt(OffsetDateTime.now());
        created.setUpdatedAt(OffsetDateTime.now());
        String uid = TenantContext.currentUserId();
        if (uid != null && !isSyntheticUserId(uid)) {
            created.setCreatedBy(uid);
        } else {
            created.setCreatedBy("");
        }
        ObjectNode cfg = (ObjectNode) source.config().deepCopy();
        AgentConfigJson.ensureDefaults(cfg);
        agentMapper.insertAgent(created);
        return new Result(created, cfg);
    }

    // ═══════════════════ suggested-questions ═══════════════════

    /** 对照 GetSuggestedQuestions（静态面：curated / 无范围 / FAQ+document chunk 池）。 */
    public ArrayNode getSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<TagScope> tagScopes, int limit, String locale) {
        boolean limitProvided = limit > 0;
        if (!limitProvided) {
            limit = SUGGESTION_DEFAULT_LIMIT;
        }
        if (limit > SUGGESTION_MAX_LIMIT) {
            limit = SUGGESTION_MAX_LIMIT;
        }

        TenantAPIKeyScope.authorizeKnowledgeTargets(orEmpty(kbIds), orEmpty(knowledgeIds));
        List<String> scopeTagIds = flattenTagScopeIds(tagScopes);
        TenantAPIKeyScope.authorizeOptionalTagIds(scopeTagIds);

        Result agent = getAgentByID(agentId, locale);
        ObjectNode cfg = agent.config();

        List<Object[]> curated = new ArrayList<>(); // [question, source, kbId]
        String starterMode = AgentConfigJson.SUGGESTION_KNOWLEDGE;

        JsonNode qs = cfg.get("question_suggestions");
        boolean startersEnabled = qs != null && qs.path("starters").path("enabled").asBoolean(false);
        if (!startersEnabled) {
            return MAPPER.createArrayNode();
        }
        JsonNode starters = qs.get("starters");
        if (!limitProvided && starters.path("count").asInt(0) > 0) {
            limit = starters.path("count").asInt();
        }
        String mode = starters.path("mode").asText("");
        starterMode = mode;
        if (AgentConfigJson.SUGGESTION_CURATED.equals(mode) || AgentConfigJson.SUGGESTION_HYBRID.equals(mode)) {
            JsonNode items = starters.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String prompt = item.asText("");
                    if (prompt.trim().isEmpty()) {
                        continue;
                    }
                    curated.add(new Object[] {prompt, "agent_config", ""});
                }
            }
        }
        if (AgentConfigJson.SUGGESTION_CURATED.equals(mode)) {
            return truncate(curated, limit);
        }

        // tag scopes 解析
        List<String> tagKnowledgeBaseIds = new ArrayList<>();
        List<String> tagKnowledgeIds = new ArrayList<>();
        Map<Long, List<String>> tagIdsByTenant = new LinkedHashMap<>();
        if (!scopeTagIds.isEmpty()) {
            ResolvedTags resolved = resolveTagScopes(tagScopes);
            tagKnowledgeBaseIds = resolved.knowledgeBaseIds();
            tagKnowledgeIds = resolved.knowledgeIds();
            tagIdsByTenant = resolved.tagIdsByTenant();
            knowledgeIds = mergeUnique(knowledgeIds, resolved.knowledgeIds());
            if ((knowledgeIds == null || knowledgeIds.isEmpty())
                    && resolved.tagIdsByTenant().isEmpty()) {
                return finalize(curated, null, starterMode, limit);
            }
        }

        // KB 范围
        List<String> effectiveKbIds = new ArrayList<>(orEmpty(kbIds));
        if (effectiveKbIds.isEmpty() && (knowledgeIds == null || knowledgeIds.isEmpty())
                && tagIdsByTenant.isEmpty()) {
            String kbMode = cfg.path("kb_selection_mode").asText("");
            switch (kbMode) {
                case "all" -> {
                    List<String> ids = new ArrayList<>();
                    boolean quickAnswer = "quick-answer".equals(cfg.path("agent_mode").asText(""));
                    for (KnowledgeBase kb : kbService.listKnowledgeBases(null)) {
                        var caps = kb.capabilities();
                        if (quickAnswer && !(caps.vector() || caps.keyword())) {
                            continue;
                        }
                        ids.add(kb.getId());
                    }
                    effectiveKbIds = ids;
                }
                case "none" -> {
                    return finalize(curated, null, starterMode, limit);
                }
                default -> {
                    JsonNode kbs = cfg.get("knowledge_bases");
                    if (kbs != null && kbs.isArray()) {
                        for (JsonNode k : kbs) {
                            effectiveKbIds.add(k.asText(""));
                        }
                    }
                }
            }
        }
        effectiveKbIds = excludeStrings(effectiveKbIds, tagKnowledgeBaseIds);
        effectiveKbIds = TenantAPIKeyScope.filterKnowledgeBases(orEmpty(kbIds), effectiveKbIds);
        if (effectiveKbIds.isEmpty() && (knowledgeIds == null || knowledgeIds.isEmpty())
                && tagIdsByTenant.isEmpty()) {
            return finalize(curated, null, starterMode, limit);
        }

        Set<String> seen = new HashSet<>();
        for (Object[] q : curated) {
            seen.add((String) q[0]);
        }
        int remaining = limit;
        int fetchLimit = Math.max(remaining * 5, 20);

        List<String> scopeKbIds = mergeUnique(effectiveKbIds, tagKnowledgeBaseIds);
        Map<Long, List<String>> kbGroups = groupKbIdsByEffectiveTenant(scopeKbIds);
        if (scopeKbIds.isEmpty()) {
            kbGroups.computeIfAbsent(tenantId(), k -> new ArrayList<>());
        }
        List<String> queryKnowledgeIds = knowledgeIds == null ? List.of() : knowledgeIds;

        Map<String, List<Object[]>> buckets = new LinkedHashMap<>();

        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            List<String> explicit = intersect(g.getValue(), effectiveKbIds);
            List<String> gTagIds = tagIdsByTenant.getOrDefault(g.getKey(), List.of());
            faqQuery(g.getKey(), explicit, queryKnowledgeIds, gTagIds, fetchLimit, buckets, seen);
        }
        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            List<String> explicit = intersect(g.getValue(), effectiveKbIds);
            docQuery(g.getKey(), explicit, queryKnowledgeIds, fetchLimit, buckets, seen);
        }

        // wiki fallback 未实现（类注释差异声明）；桶内/桶间随机序不进契约（golden 单元素池）
        List<Object[]> knowledgeResult = roundRobin(buckets, limit);
        return finalize(curated, knowledgeResult, starterMode, limit);
    }

    private void faqQuery(long groupTenant, List<String> explicitKbIds, List<String> knowledgeIds,
            List<String> tagIds, int fetchLimit, Map<String, List<Object[]>> buckets, Set<String> seen) {
        if (explicitKbIds.isEmpty() && knowledgeIds.isEmpty() && tagIds.isEmpty()) {
            return; // Go repo：三个范围全空 → 直接返回 nil
        }
        List<Map<String, Object>> rows = questionMapper.listRecommendedFaqChunks(groupTenant,
                explicitKbIds, !explicitKbIds.isEmpty(),
                knowledgeIds, !knowledgeIds.isEmpty(),
                tagIds, !tagIds.isEmpty(), fetchLimit);
        for (Map<String, Object> row : rows) {
            String question = faqStandardQuestion((String) row.get("metadata"));
            if (question == null || question.isEmpty() || seen.contains(question)) {
                continue;
            }
            seen.add(question);
            buckets.computeIfAbsent((String) row.get("knowledgeId"), k -> new ArrayList<>())
                    .add(new Object[] {question, "faq", row.get("knowledgeBaseId")});
        }
    }

    private void docQuery(long groupTenant, List<String> explicitKbIds, List<String> knowledgeIds,
            int fetchLimit, Map<String, List<Object[]>> buckets, Set<String> seen) {
        List<Map<String, Object>> rows = questionMapper.listRecentDocumentChunksWithQuestions(
                groupTenant, explicitKbIds, !explicitKbIds.isEmpty(),
                knowledgeIds, !knowledgeIds.isEmpty(), fetchLimit);
        for (Map<String, Object> row : rows) {
            String q = firstGeneratedQuestion((String) row.get("metadata"));
            if (q == null || q.isEmpty() || seen.contains(q)) {
                continue;
            }
            seen.add(q);
            buckets.computeIfAbsent((String) row.get("knowledgeId"), k -> new ArrayList<>())
                    .add(new Object[] {q, "document", row.get("knowledgeBaseId")});
        }
    }

    /** metadata jsonb → faq standard_question（trim；对照 Chunk.FAQMetadata().StandardQuestion）。 */
    static String faqStandardQuestion(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(metadata).path("standard_question").asText("").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** metadata jsonb → generated_questions[0].question（对照 GetQuestionStrings 首个）。 */
    static String firstGeneratedQuestion(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            JsonNode qs = MAPPER.readTree(metadata).get("generated_questions");
            if (qs == null || !qs.isArray() || qs.isEmpty()) {
                return null;
            }
            JsonNode first = qs.get(0);
            String q = first.isObject() ? first.path("question").asText("") : first.asText("");
            return q == null ? "" : q;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object[]> roundRobin(Map<String, List<Object[]>> buckets, int limit) {
        List<String> keys = new ArrayList<>(buckets.keySet());
        Map<String, Integer> offsets = new LinkedHashMap<>();
        List<Object[]> out = new ArrayList<>();
        boolean picked = true;
        while (out.size() < limit && picked) {
            picked = false;
            for (String key : keys) {
                if (out.size() >= limit) {
                    break;
                }
                List<Object[]> qs = buckets.get(key);
                int idx = offsets.getOrDefault(key, 0);
                if (idx < qs.size()) {
                    out.add(qs.get(idx));
                    offsets.put(key, idx + 1);
                    picked = true;
                }
            }
        }
        return out;
    }

    /** 对照 finalizeStarterSuggestions（curated/hybrid/knowledge 三分支）。 */
    private ArrayNode finalize(List<Object[]> curated, List<Object[]> knowledge, String mode,
            int limit) {
        if (limit <= 0) {
            return MAPPER.createArrayNode();
        }
        switch (mode) {
            case AgentConfigJson.SUGGESTION_CURATED:
                return truncate(curated, limit);
            case AgentConfigJson.SUGGESTION_HYBRID:
                return mergeHybrid(curated, knowledge == null ? List.of() : knowledge, limit);
            default:
                return truncate(knowledge == null ? List.of() : knowledge, limit);
        }
    }

    /** 对照 mergeHybridStarterSuggestions：knowledge 槽 = ⌈limit/3⌉（limit>1），curated 优先。 */
    private ArrayNode mergeHybrid(List<Object[]> curated, List<Object[]> knowledge, int limit) {
        int knowledgeSlots = limit > 1 ? (limit + 1) / 3 : 0;
        int curatedSlots = limit - knowledgeSlots;
        List<Object[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendFrom(curated, curatedSlots, result, seen, limit);
        appendFrom(knowledge, knowledgeSlots, result, seen, limit);
        appendFrom(curated, -1, result, seen, limit);
        appendFrom(knowledge, -1, result, seen, limit);
        ArrayNode out = MAPPER.createArrayNode();
        for (Object[] q : result) {
            addQuestion(out, q);
        }
        return out;
    }

    private static void appendFrom(List<Object[]> items, int max, List<Object[]> result,
            Set<String> seen, int limit) {
        int added = 0;
        for (Object[] item : items) {
            if (result.size() == limit || (max >= 0 && added == max)) {
                return;
            }
            String key = ((String) item[0]).toLowerCase().trim();
            if (key.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            result.add(item);
            added++;
        }
    }

    private static void addQuestion(ArrayNode out, Object[] q) {
        ObjectNode n = out.addObject();
        n.put("question", (String) q[0]);
        n.put("source", (String) q[1]);
        String kbId = (String) q[2];
        if (kbId != null && !kbId.isEmpty()) {
            n.put("knowledge_base_id", kbId);
        }
    }

    private ArrayNode truncate(List<Object[]> questions, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        int n = Math.min(questions.size(), limit);
        for (int i = 0; i < n; i++) {
            addQuestion(out, questions.get(i));
        }
        return out;
    }

    public record TagScope(String knowledgeBaseId, List<String> tagIds) {}

    private record ResolvedTags(List<String> knowledgeBaseIds, List<String> knowledgeIds,
            Map<Long, List<String>> tagIdsByTenant) {}

    /** 对照 resolveSuggestionTagScopes：tag 行校验 + knowledge 展开。 */
    private ResolvedTags resolveTagScopes(List<TagScope> scopes) {
        Map<String, List<String>> byKb = new LinkedHashMap<>();
        for (TagScope s : scopes) {
            if (s.knowledgeBaseId() == null || s.knowledgeBaseId().isEmpty()) {
                continue;
            }
            for (String tagId : s.tagIds()) {
                if (tagId == null || tagId.isEmpty()) {
                    continue;
                }
                byKb.computeIfAbsent(s.knowledgeBaseId(), k -> new ArrayList<>()).add(tagId);
            }
        }
        if (byKb.isEmpty()) {
            return new ResolvedTags(List.of(), List.of(), new LinkedHashMap<>());
        }
        Map<Long, List<String>> kbGroups = groupKbIdsByEffectiveTenant(new ArrayList<>(byKb.keySet()));
        List<String> outKbIds = new ArrayList<>();
        List<String> outKnowledgeIds = new ArrayList<>();
        Map<Long, List<String>> tagIdsByTenant = new LinkedHashMap<>();
        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            for (String kbId : g.getValue()) {
                List<String> requested = mergeUnique(null, byKb.get(kbId));
                List<Map<String, Object>> tags = questionMapper.findTags(g.getKey(), requested);
                Set<String> requestedSet = new HashSet<>(requested);
                List<String> valid = new ArrayList<>();
                for (Map<String, Object> t : tags) {
                    if (kbId.equals(t.get("knowledgeBaseId"))
                            && requestedSet.contains((String) t.get("id"))) {
                        valid.add((String) t.get("id"));
                    }
                }
                if (valid.isEmpty()) {
                    continue;
                }
                outKbIds = mergeUnique(outKbIds, List.of(kbId));
                tagIdsByTenant.computeIfAbsent(g.getKey(), k -> new ArrayList<>())
                        .addAll(mergeUnique(null, valid));
                List<String> knowledge = questionMapper.listKnowledgeIdsByTagIds(
                        g.getKey(), kbId, valid);
                outKnowledgeIds = mergeUnique(outKnowledgeIds, knowledge);
            }
        }
        return new ResolvedTags(outKbIds, outKnowledgeIds, tagIdsByTenant);
    }

    /** 对照 groupKBIDsByEffectiveTenant：本空间 KB → 调用者租户；不可达 → 静默丢弃。 */
    private Map<Long, List<String>> groupKbIdsByEffectiveTenant(List<String> kbIds) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = questionMapper.findKbs(kbIds);
        Map<String, Long> tenantByKb = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            tenantByKb.put((String) row.get("id"), ((Number) row.get("tenantId")).longValue());
        }
        for (String kbId : kbIds) {
            Long kbTenant = tenantByKb.get(kbId);
            if (kbTenant == null) {
                continue;
            }
            out.computeIfAbsent(kbTenant, k -> new ArrayList<>()).add(kbId);
        }
        return out;
    }

    // ═══════════════════ 小工具 ═══════════════════

    private static ObjectNode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode n = MAPPER.readTree(raw);
            return n == null || n.isNull() || !n.isObject()
                    ? MAPPER.createObjectNode() : (ObjectNode) n;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** 对照 types.IsSyntheticUserID：system-{tenantId} 合成用户不落 created_by。 */
    private static boolean isSyntheticUserId(String uid) {
        return uid != null && uid.startsWith("system-");
    }

    private static List<String> orEmpty(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static List<String> flattenTagScopeIds(List<TagScope> scopes) {
        List<String> ids = null;
        if (scopes != null) {
            for (TagScope s : scopes) {
                ids = mergeUnique(ids, s.tagIds());
            }
        }
        return ids == null ? List.of() : ids;
    }

    private static List<String> intersect(List<String> values, List<String> allowed) {
        if (values == null || values.isEmpty() || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        Set<String> allowedSet = new HashSet<>(allowed);
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty() && allowedSet.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> excludeStrings(List<String> values, List<String> excluded) {
        if (values == null || values.isEmpty() || excluded == null || excluded.isEmpty()) {
            return values == null ? List.of() : values;
        }
        Set<String> excludedSet = new HashSet<>(excluded);
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty() && !excludedSet.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> mergeUnique(List<String> base, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        if (base != null) {
            for (String s : base) {
                if (s == null || s.isEmpty() || seen.contains(s)) {
                    continue;
                }
                seen.add(s);
                out.add(s);
            }
        }
        for (String s : extra) {
            if (s == null || s.isEmpty() || seen.contains(s)) {
                continue;
            }
            seen.add(s);
            out.add(s);
        }
        return out;
    }

    private static String currentLocale() {
        return com.ragagent.wiki.service.WikiLanguageSupport.defaultLanguage();
    }
}
