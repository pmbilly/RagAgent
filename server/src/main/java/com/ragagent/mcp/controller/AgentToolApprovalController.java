package com.ragagent.mcp.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.approval.ApprovalException;
import com.ragagent.agent.approval.Decision;
import com.ragagent.agent.approval.Gate;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处理待审批的 MCP 工具调用（对照 Go internal/handler/mcp_service.go:646-733 的
 * {@code ResolveToolApproval}）。
 *
 * <p>Go 里该方法挂在 MCPServiceHandler 上，但路由注册在 {@code /agent} 组下
 * （routes_infra.go:197，Viewer+，不开放给 API key——人工交互流程不做 API key 声明），
 * 故 Java 侧独立成一个控制器，路径前缀与 Go 一致。</p>
 *
 * <p><b>为什么是 Viewer+ 而不是 Admin+</b>：审批卡片出现在调用者自己发起的 agent 会话里，
 * 收紧到 Admin+ 会把唯一有上下文做判断的人挡在门外；门禁保持"租户内任意成员"，
 * 真正的越权防线是 gate 内的 tenant/user 校验。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentToolApprovalController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolApprovalController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对照 Go {@code h.toolApprovalGate}；未接线时为 empty（Go 里是 nil） */
    private final Optional<Gate> toolApprovalGate;

    public AgentToolApprovalController(Optional<Gate> toolApprovalGate) {
        this.toolApprovalGate = toolApprovalGate;
    }

    /** 对照 ResolveToolApproval — Viewer+ */
    @PostMapping("/tool-approvals/{pending_id}")
    public ResponseEntity<?> resolveToolApproval(@PathVariable("pending_id") String pendingId,
                                                 @RequestBody(required = false) ResolveToolApprovalRequest body) {
        Long tenantId = TenantContext.currentTenantId();
        long tenant = tenantId == null ? 0L : tenantId;
        if (tenant == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        Gate gate = toolApprovalGate.orElse(null);
        if (gate == null) {
            throw BizException.internal("Tool approval gate is not configured");
        }
        if (body == null) {
            throw BizException.badRequest("EOF");
        }

        Decision decision = decisionFrom(body);

        // 对照 Go：principal.StorageID()；无 principal 时回落到 user_id 组成的 web_user
        String gateUserId = McpPrincipal.storageId(McpPrincipal.fromContext());
        // 前置拒绝没有已认证主体的调用。gate 的按主体鉴权本身是 fail-close 的，
        // 但在这里给出 401 能更清楚地指出"鉴权中间件没有填充上下文"。
        if (gateUserId == null || gateUserId.trim().isEmpty()) {
            throw BizException.unauthorized("authenticated user required to resolve tool approval");
        }

        try {
            gate.resolve(tenant, gateUserId, pendingId, decision);
        } catch (ApprovalException e) {
            // 四个哨兵 → 404/400 + Go 的原文案（另一 agent 给出的映射表）
            switch (e.kind()) {
                case PENDING_NOT_FOUND -> throw BizException.notFound(
                        "pending approval not found or already completed");
                case ALREADY_RESOLVED -> throw BizException.badRequest(
                        "pending approval already resolved (timeout / cancel raced your action)");
                case TENANT_MISMATCH -> throw BizException.badRequest("workspace mismatch");
                case USER_MISMATCH -> throw BizException.badRequest(
                        "user mismatch: only the session owner may resolve this approval");
                default -> {
                    log.error("Failed to resolve tool approval, pending_id={}", pendingId, e);
                    throw BizException.internal(e.getMessage() == null ? "" : e.getMessage());
                }
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        return ResponseEntity.ok(ok);
    }

    /**
     * 对照 Go 的 decision 分支。
     *
     * <p>{@code modified_args} 只接受<b>非 null 的 JSON 对象</b>：Go 里 "null"（4 字节）
     * 能通过 {@code len(trimmed) > 0} 检查，让下游工具拿到 nil 参数表、静默丢掉原始参数，
     * 所以这里前置拒绝。</p>
     */
    private static Decision decisionFrom(ResolveToolApprovalRequest body) {
        // 对照 Go: dec := approval.Decision{Reason: body.Reason}——两个分支都带 reason
        String reason = body.reason() == null ? "" : body.reason();
        String rawDecision = body.decision();
        if ("approve".equals(rawDecision)) {
            JsonNode modified = body.modifiedArgs();
            // 对照 Go strings.TrimSpace(string(body.ModifiedArgs))：缺失与 JSON null 等价
            if (modified == null || modified.isNull()) {
                return new Decision(true, null, reason, false, false);
            }
            // 对照 Go：反序列化进 map[string]interface{} 失败即 400。
            // 注意空对象 {} 是合法的"改用空参数表"（Go 的 probe 非 nil 即可通过）。
            if (!modified.isObject()) {
                throw BizException.badRequest("modified_args must be a non-null JSON object");
            }
            // 重新序列化成 raw JSON 交给 gate（Go 传的是原始字节）
            return new Decision(true, writeJson(modified), reason, false, false);
        }
        if ("reject".equals(rawDecision)) {
            return new Decision(false, null, reason, false, false);
        }
        throw BizException.badRequest("decision must be approve or reject");
    }

    private static String writeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw BizException.badRequest("modified_args must be a non-null JSON object");
        }
    }
}
