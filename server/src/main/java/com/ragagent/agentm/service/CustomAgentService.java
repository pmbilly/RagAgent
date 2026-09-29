package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.service.KnowledgeBaseService;

/**
 * agents CRUD 家族 service（对照 Go internal/application/service/custom_agent.go +
 * internal/handler/custom_agent.go 的可复刻子集）。
 *
 * <p>已知降级（均不在 golden/A-B 场景内，报告与测试注释均有声明）：</p>
 * <ul>
 *   <li>imService.DeleteChannelsByAgent：已接线（2026-09-24）——删除 agent 时软删其
 *       IM 渠道并停止运行中的适配器（对照 Go handler/custom_agent.go L431 →
 *       im/service.go L3243-3261）。</li>
 *   <li>suggested-questions 的 wiki fallback（ListRecentForSuggestions）未实现。</li>
 *   <li>kb_selection_mode=all 的能力过滤只实现 quick-answer 的 vector/keyword any-of
 *       基础面，allowed_tools→capability 派生表未移植。</li>
 * </ul>
 */
@Service
public class CustomAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 suggestionDefaultLimit / suggestionMaxLimit。 */

    private final CustomAgentMapper agentMapper;
    private final AgentQuestionMapper questionMapper;
    private final com.ragagent.auth.service.UserService userService;
    private final KnowledgeBaseService kbService;
    private final BuiltinAgentRegistry registry;
    /**
     * IM 渠道清理（对照 Go handler 的 {@code h.imService.DeleteChannelsByAgent}）。
     * ObjectProvider 延迟解析：ImService 直接依赖本类（其字段 agentService），
     * 构造期硬注入会成环。
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.im.service.ImService> imServiceProvider;

    /** 推荐问题流协作者。 */
    private final AgentSuggestedQuestions suggestedQuestions;

    public CustomAgentService(CustomAgentMapper agentMapper,
            AgentQuestionMapper questionMapper,
            com.ragagent.auth.service.UserService userService,
            KnowledgeBaseService kbService,
            BuiltinAgentRegistry registry,
            org.springframework.beans.factory.ObjectProvider<
                    com.ragagent.im.service.ImService> imServiceProvider) {
        this.agentMapper = agentMapper;
        this.questionMapper = questionMapper;
        this.userService = userService;
        this.kbService = kbService;
        this.registry = registry;
        this.imServiceProvider = imServiceProvider;
        this.suggestedQuestions = new AgentSuggestedQuestions(this, agentMapper, questionMapper);
    }

    /** 包内协作者访问面。 */
    KnowledgeBaseService kbService() {
        return kbService;
    }

    static long tenantId() {
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
            ObjectNode cfg = AgentConfigJson.ensureDefaults(AgentSuggestedQuestions.parse(row.getConfig()));
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
        // ⚠️ 2026-09-23 修复：合成行必须带上 config 字符串——运行时消费面
        // （KnowledgeQaController.resolveAgent / SandboxTerminalController）只取 row
        // 并重新 parse row.getConfig()，不落 config 会让无 DB 行的租户拿到空配置
        //（agent_mode/allowed_tools/kb_selection_mode 等全丢）。Go 的 GetAgentByID
        // 在无 DB 行时返回的是带完整 Config 的物化 agent，行为等价。
        JsonNode cfgNode = built.get("config");
        if (cfgNode != null && !cfgNode.isNull()) {
            virtual.setConfig(cfgNode.toString());
        }
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
            ObjectNode cfg = AgentConfigJson.ensureDefaults(AgentSuggestedQuestions.parse(row.getConfig()));
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

        // disabled_own_agent_ids 随空间分享裁撤：恒空列表（信封键保留以稳契约）
        enrichCreatorNames(result);
        return new ListResult(result, List.of());
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
        if (uid != null && !AgentSuggestedQuestions.isSyntheticUserId(uid)) {
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
            applyLocalization(id, existing, AgentSuggestedQuestions.currentLocale());
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
        applyLocalization(id, created, AgentSuggestedQuestions.currentLocale());
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

        // 对照 Go handler/custom_agent.go L431：imService.DeleteChannelsByAgent(id, tenantID)
        // —— 软删该 agent 的全部 IM 渠道并停止运行中的适配器，避免概览列表与运行中的
        // 适配器比 agent 活得更久（此前 Java 侧为 no-op）。
        com.ragagent.im.service.ImService imService = imServiceProvider.getIfAvailable();
        if (imService != null) {
            imService.deleteChannelsByAgent(id, tenant);
        }
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
        if (uid != null && !AgentSuggestedQuestions.isSyntheticUserId(uid)) {
            created.setCreatedBy(uid);
        } else {
            created.setCreatedBy("");
        }
        ObjectNode cfg = (ObjectNode) source.config().deepCopy();
        AgentConfigJson.ensureDefaults(cfg);
        agentMapper.insertAgent(created);
        return new Result(created, cfg);
    }

    // ═══════════════════ suggested-questions(委托) ═══════════════════

    public ArrayNode getSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<AgentSuggestedQuestions.TagScope> tagScopes, int limit,
            String locale) {
        return suggestedQuestions.getSuggestedQuestions(agentId, kbIds, knowledgeIds, tagScopes, limit,
                locale);
    }

    public ArrayNode getKnowledgeSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<AgentSuggestedQuestions.TagScope> tagScopes, int limit,
            String locale) {
        return suggestedQuestions.getKnowledgeSuggestedQuestions(agentId, kbIds, knowledgeIds, tagScopes,
                limit, locale);
    }
}
