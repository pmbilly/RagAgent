package com.ragagent.agentm.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.dto.AgentResponses;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.agentm.service.AgentPlaceholders;
import com.ragagent.agentm.service.AgentTypePresets;
import com.ragagent.agentm.service.BuiltinAgentRegistry;
import com.ragagent.agentm.service.CustomAgentService;
import com.ragagent.agentm.service.CustomAgentService.TagScope;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.web.GoJsonBindError;

/**
 * agents CRUD 家族路由（对照 Go internal/handler/custom_agent.go +
 * internal/router/routes_agent.go RegisterCustomAgentRoutes）。
 *
 * <p>守卫映射（对照 Go rbacGuards）：写路由 OwnedAgentOrAdmin（无角色门、判定在
 * handler——Java 在 controller 内做：行存在且非 Admin+ 且非创建者 → 403 纯字符串；
 * 行不存在 → 放行给 handler 出 404）；读/列表 Viewer 下限由 RbacInterceptor 承担。</p>
 */
@RestController
public class AgentController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CustomAgentService service;
    private final AgentPlaceholders placeholders;
    private final AgentTypePresets typePresets;
    private final com.ragagent.agentm.mapper.CustomAgentMapper agentMapper;

    public AgentController(CustomAgentService service, AgentPlaceholders placeholders,
            AgentTypePresets typePresets,
            com.ragagent.agentm.mapper.CustomAgentMapper agentMapper) {
        this.service = service;
        this.placeholders = placeholders;
        this.typePresets = typePresets;
        this.agentMapper = agentMapper;
    }

    // ── 请求体（Go CreateAgentRequest/UpdateAgentRequest：仅 name 带 required）──
    private record AgentRequest(String name, String description, String avatar, JsonNode config) {}

    // ── GET /agents ──
    @GetMapping("/api/v1/agents")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "creator", required = false) String creator,
            HttpServletRequest req) {
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.listAgents(creator == null ? "" : creator.toLowerCase().trim(), locale);
        List<Object> rows = new ArrayList<>();
        for (var r : result.agents()) {
            rows.add(AgentResponses.agent(r));
        }
        return ResponseEntity.ok(AgentResponses.listEnvelope(rows, result.disabledOwnIds()));
    }

    // ── GET /agents/placeholders ──
    @GetMapping("/api/v1/agents/placeholders")
    public ResponseEntity<Map<String, Object>> placeholders() {
        return ResponseEntity.ok(AgentResponses.dataEnvelope(placeholders.data()));
    }

    // ── GET /agents/type-presets ──
    @GetMapping("/api/v1/agents/type-presets")
    public ResponseEntity<Map<String, Object>> typePresets(HttpServletRequest req) {
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        return ResponseEntity.ok(AgentResponses.dataEnvelope(typePresets.list(locale)));
    }

    // ── POST /agents ──
    @PostMapping("/api/v1/agents")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) String rawBody) {
        AgentRequest parsed = bindAgentRequest(rawBody, "CreateAgentRequest");
        ObjectNode cfg = configNode(parsed);
        authorizeKnowledgeScope(cfg);
        var result = service.createAgent(parsed.name(), parsed.description(), parsed.avatar(), cfg);
        return ResponseEntity.status(201).body(AgentResponses.dataEnvelope(AgentResponses.agent(result)));
    }

    // ── GET /agents/:id ──
    @GetMapping("/api/v1/agents/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id,
            HttpServletRequest req) {
        requireNonEmpty(id);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.getAgentByID(id, locale);
        return ResponseEntity.ok(AgentResponses.dataEnvelope(AgentResponses.agent(result)));
    }

    // ── PUT /agents/:id ──
    @PutMapping("/api/v1/agents/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody, HttpServletRequest req) {
        requireNonEmpty(id);
        AgentRequest parsed = bindAgentRequest(rawBody, "UpdateAgentRequest");
        checkAgentOwnership(id, req);
        ObjectNode cfg = configNode(parsed);
        authorizeKnowledgeScope(cfg);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.updateAgent(id, parsed.name(), parsed.description(), parsed.avatar(),
                cfg, locale);
        return ResponseEntity.ok(AgentResponses.dataEnvelope(AgentResponses.agent(result)));
    }

    // ── DELETE /agents/:id ──
    @DeleteMapping("/api/v1/agents/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") String id,
            HttpServletRequest req) {
        requireNonEmpty(id);
        checkAgentOwnership(id, req);
        service.deleteAgent(id);
        return ResponseEntity.ok(AgentResponses.deletedEnvelope());
    }

    // ── POST /agents/:id/copy ──
    @PostMapping("/api/v1/agents/{id}/copy")
    public ResponseEntity<Map<String, Object>> copy(@PathVariable("id") String id,
            HttpServletRequest req) {
        requireNonEmpty(id);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        // Go：先 GetAgentByID（404 路径），再对 source config 做 API-Key scope 校验
        var source = service.getAgentByID(id, locale);
        authorizeKnowledgeScope(source.config());
        var result = service.copyAgent(id, locale);
        return ResponseEntity.status(201).body(AgentResponses.dataEnvelope(AgentResponses.agent(result)));
    }

    // ── GET /agents/:id/suggested-questions ──
    @GetMapping("/api/v1/agents/{id}/suggested-questions")
    public ResponseEntity<Map<String, Object>> suggestedQuestions(@PathVariable("id") String id,
            @RequestParam(value = "knowledge_base_ids", required = false) String kbIdsStr,
            @RequestParam(value = "knowledge_ids", required = false) String knowledgeIdsStr,
            @RequestParam(value = "tag_scopes", required = false) String tagScopesStr,
            @RequestParam(value = "limit", required = false) String limitStr,
            HttpServletRequest req) {
        requireNonEmpty(id);
        List<String> kbIds = splitCsv(kbIdsStr);
        List<String> knowledgeIds = splitCsv(knowledgeIdsStr);
        List<TagScope> tagScopes = new ArrayList<>();
        if (tagScopesStr != null && !tagScopesStr.trim().isEmpty()) {
            try {
                JsonNode arr = MAPPER.readTree(tagScopesStr);
                if (arr.isArray()) {
                    for (JsonNode e : arr) {
                        List<String> tagIds = new ArrayList<>();
                        JsonNode ids = e.get("tag_ids");
                        if (ids != null && ids.isArray()) {
                            for (JsonNode t : ids) {
                                tagIds.add(t.asText(""));
                            }
                        }
                        tagScopes.add(new TagScope(e.path("knowledge_base_id").asText(""), tagIds));
                    }
                } else {
                    throw new IllegalArgumentException("not an array");
                }
            } catch (Exception ex) {
                throw new BizException(AppError.badRequest("tag_scopes must be valid JSON"));
            }
        }
        int limit = 0;
        if (limitStr != null && !limitStr.isEmpty()) {
            try {
                int parsed = Integer.parseInt(limitStr.trim());
                if (parsed > 0) {
                    limit = parsed;
                }
            } catch (NumberFormatException ignored) {
                // Go：Atoi 失败 → limit 保持 0（"unspecified"）
            }
        }
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var questions = service.getSuggestedQuestions(id, kbIds, knowledgeIds, tagScopes, limit,
                locale);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("questions", questions);
        return ResponseEntity.ok(AgentResponses.dataEnvelope(data));
    }

    // ── handler 私有段 ──

    private static void requireNonEmpty(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Agent ID cannot be empty"));
        }
    }

    /**
     * Go ShouldBindJSON 的等价绑定：name required（Create）→ gin validator 文案；
     * JSON 语法错误 → GoJsonBindError 的 Go 原文消息。两个请求体都把它拼进
     * "Invalid request parameters" 的 details。
     */
    private static AgentRequest bindAgentRequest(String rawBody, String structName) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalidParams("EOF");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw invalidParams(GoJsonBindError.message(rawBody, e.getMessage()));
        }
        String name = node == null || node.get("name") == null || node.get("name").isNull()
                ? "" : node.get("name").asText("");
        if ("CreateAgentRequest".equals(structName) && name.isEmpty()) {
            throw invalidParams("Key: 'CreateAgentRequest.Name' "
                    + "Error:Field validation for 'Name' failed on the 'required' tag");
        }
        String description = node != null && node.get("description") != null
                && !node.get("description").isNull() ? node.get("description").asText("") : "";
        String avatar = node != null && node.get("avatar") != null
                && !node.get("avatar").isNull() ? node.get("avatar").asText("") : "";
        JsonNode config = node != null && node.get("config") != null && node.get("config").isObject()
                ? node.get("config") : MAPPER.createObjectNode();
        return new AgentRequest(name, description, avatar, config);
    }

    private static BizException invalidParams(String details) {
        return new BizException(AppError.badRequest("Invalid request parameters").withDetails(details));
    }

    private static ObjectNode configNode(AgentRequest parsed) {
        JsonNode cfg = parsed.config();
        return cfg == null || cfg.isNull() || !cfg.isObject()
                ? MAPPER.createObjectNode() : (ObjectNode) cfg;
    }

    /** 对照 authorizeAgentKnowledgeScope（API-Key 受限 scope 的 KB 白名单校验）。 */
    private static void authorizeKnowledgeScope(ObjectNode cfg) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return;
        }
        String mode = cfg.path("kb_selection_mode").asText("").toLowerCase().trim();
        switch (mode) {
            case "none" -> {
            }
            case "all" -> throw new BizException(AppError.forbidden(
                    "API key scope does not allow agents that use all knowledge bases"));
            case "selected" -> TenantAPIKeyScope.authorizeKnowledgeBases(strings(cfg, "knowledge_bases"));
            default -> {
                List<String> kbs = strings(cfg, "knowledge_bases");
                if (!kbs.isEmpty()) {
                    TenantAPIKeyScope.authorizeKnowledgeBases(kbs);
                }
            }
        }
    }


    /** 对照 OwnedAgentOrAdmin（路由守卫的 controller 内落地）：行存在且非 Admin+ 且非创建者 → 403。 */
    private void checkAgentOwnership(String id, HttpServletRequest req) {
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role == null ? "" : role)
                .hasPermission(TenantRole.ADMIN);
        if (admin) {
            return;
        }
        // 行存在性：查询交给 mapper（软删过滤同 Go）。行不存在时放行
        // （对照 ErrResourceNotFound 透传，handler 出 404）。
        Long tenant = TenantContext.currentTenantId();
        var row = agentMapper.getByIDAndTenant(id, tenant == null ? 0 : tenant);
        if (row != null) {
            String creator = row.getCreatedBy() == null ? "" : row.getCreatedBy();
            String uid = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
            if (creator.isEmpty() || !creator.equals(uid)) {
                throw GuardForbiddenException.mustOwnResourceOrHaveRole();
            }
        }
    }

    private static List<String> strings(ObjectNode cfg, String field) {
        List<String> out = new ArrayList<>();
        JsonNode n = cfg.get(field);
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.asText(""));
            }
        }
        return out;
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
