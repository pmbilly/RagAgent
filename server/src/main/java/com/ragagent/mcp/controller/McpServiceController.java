package com.ragagent.mcp.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.dto.McpServiceCreateRequest;
import com.ragagent.mcp.dto.McpToolApprovalPolicyRequest;
import com.ragagent.mcp.dto.RoleVisibility;
import com.ragagent.mcp.service.McpMetadataException;
import com.ragagent.mcp.service.McpMetadataService;
import com.ragagent.mcp.service.McpServiceService;
import com.ragagent.mcp.service.McpToolApprovalService;
import com.ragagent.model.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 服务 HTTP 层（对照 Go internal/handler/mcp_service.go 的 MCPServiceHandler，
 * 以及 mcp_metadata.go 与 mcp_usage_instructions.go 的方法）。
 *
 * <p><b>路由与所需角色</b>（主会话在 WebConfig 里注册，与 Go routes_infra.go:136-200 一致）：</p>
 * <pre>
 * POST   /api/v1/mcp-services                              Admin+
 * GET    /api/v1/mcp-services                              Viewer+
 * GET    /api/v1/mcp-services/{id}                         Viewer+
 * PUT    /api/v1/mcp-services/{id}                         Admin+
 * DELETE /api/v1/mcp-services/{id}                         Admin+
 * POST   /api/v1/mcp-services/{id}/test                    Admin+
 * GET    /api/v1/mcp-services/{id}/tools                   Viewer+
 * GET    /api/v1/mcp-services/{id}/resources               Viewer+
 * GET    /api/v1/mcp-services/{id}/metadata                Viewer+
 * POST   /api/v1/mcp-services/{id}/metadata/refresh        Viewer+（静态鉴权在 handler 内升到 Admin+）
 * POST   /api/v1/mcp-services/{id}/usage-instructions/generate  Admin+
 * GET    /api/v1/mcp-services/{id}/tool-approvals           Viewer+
 * PUT    /api/v1/mcp-services/{id}/tool-approvals/{tool_name}  Admin+
 * </pre>
 * <p>凭据子资源见 {@link McpCredentialsController}；{@code /agent/tool-approvals/{pending_id}}
 * 见 {@link AgentToolApprovalController}（Go 里该方法也在 mcp_service.go，但挂在 /agent 组）。</p>
 *
 * <p><b>响应形态（§14.9n M1 换锚后）</b>：服务资源面（create/list/get/update/delete/test/
 * tools/resources/metadata/usage-instructions）返回**裸对象或裸数组**（§2.1），创建 201、
 * 删除 204；仅**工具审批面**仍带 {@code {data,success}} 信封（属 M4 批次）。</p>
 */
@RestController
@RequestMapping("/api/v1/mcp-services")
public class McpServiceController {

    private static final Logger log = LoggerFactory.getLogger(McpServiceController.class);


    final McpServiceService mcpServiceService;
    final McpMetadataService mcpMetadataService;
    final McpToolApprovalService mcpToolApprovalService;
    final SsrfGuard ssrfGuard;
    final ModelService modelService;
    final ConcurrencyGovernor concurrencyGovernor;
    final Optional<OllamaService> ollamaService;

    /** 使用说明生成协作者（对照 Go mcp_usage_instructions.go 段）。 */
    final McpUsageInstructionsOps usageOps;

    /** MCP 服务 CRUD 协作者（对照 Go 服务面端点）。 */
    final McpServiceCrudOps crudOps;

    public McpServiceController(McpServiceService mcpServiceService,
                                McpMetadataService mcpMetadataService,
                                McpToolApprovalService mcpToolApprovalService,
                                SsrfGuard ssrfGuard,
                                ModelService modelService,
                                ConcurrencyGovernor concurrencyGovernor,
                                Optional<OllamaService> ollamaService) {
        this.mcpServiceService = mcpServiceService;
        this.mcpMetadataService = mcpMetadataService;
        this.mcpToolApprovalService = mcpToolApprovalService;
        this.ssrfGuard = ssrfGuard;
        this.modelService = modelService;
        this.concurrencyGovernor = concurrencyGovernor;
        this.ollamaService = ollamaService;
        this.usageOps = new McpUsageInstructionsOps(this);
        this.crudOps = new McpServiceCrudOps(this);
    }


    @PostMapping
    public ResponseEntity<?> createMCPService(
            @RequestBody(required = false) McpServiceCreateRequest req) {
        return crudOps.createMCPService(req);
    }

    @GetMapping
    public ResponseEntity<?> listMCPServices() {
        return crudOps.listMCPServices();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMCPService(@PathVariable("id") String id) {
        return crudOps.getMCPService(id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateMCPService(@PathVariable("id") String id,
                                              @RequestBody(required = false) JsonNode updateData) {
        return crudOps.updateMCPService(id, updateData);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteMCPService(@PathVariable("id") String id) {
        return crudOps.deleteMCPService(id);
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testMCPService(@PathVariable("id") String id) {
        return crudOps.testMCPService(id);
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<?> getMCPServiceTools(@PathVariable("id") String id) {
        return crudOps.getMCPServiceTools(id);
    }

    @GetMapping("/{id}/resources")
    public ResponseEntity<?> getMCPServiceResources(@PathVariable("id") String id) {
        return crudOps.getMCPServiceResources(id);
    }


    // ── 目录快照（mcp_metadata.go） ───────────────────────────────────────

    /** 对照 GetMCPMetadata — Viewer+；只读数据库，不连接上游 */
    @GetMapping("/{id}/metadata")
    public ResponseEntity<?> getMCPMetadata(@PathVariable("id") String id) {
        return mcpMetadata(id, false);
    }

    /**
     * 对照 RefreshMCPMetadata — Viewer+（静态鉴权在 handler 内升到 Admin+）。
     *
     * <p>路由保持 Viewer+ 是为了让 OAuth 用户在聊天里授权后能存自己的快照；
     * 静态鉴权写的是租户共享快照，故额外要求管理员。</p>
     */
    @PostMapping("/{id}/metadata/refresh")
    public ResponseEntity<?> refreshMCPMetadata(@PathVariable("id") String id) {
        return mcpMetadata(id, true);
    }

    private ResponseEntity<?> mcpMetadata(String id, boolean refresh) {
        long tenant = requireTenant();
        String serviceId = sanitize(id);

        // ⚠️ 服务存在性校验与 Admin 门禁必须**在**重映射 try 之外：
        // Go 在这两处直接 c.Error(...) 后 return，不经过 mcpMetadataAppError。
        // 若把它们放进 try，自己抛出的 403 会被 default 分支改写成 400。
        if (refresh) {
            McpService service;
            try {
                service = mcpServiceService.getMCPServiceByID(tenant, serviceId);
            } catch (RuntimeException e) {
                log.error("MCP metadata refresh: service lookup failed, service_id={}", serviceId, e);
                throw McpMetadataException.serviceNotFound();
            }
            if (!isOAuth(service) && !mayWriteSharedMCPMetadata()) {
                throw BizException.forbidden(
                        "Refreshing a shared MCP directory requires an administrator");
            }
        }

        McpMetadata snapshot;
        try {
            snapshot = refresh
                    ? mcpMetadataService.refreshMCPMetadata(tenant, serviceId)
                    : mcpMetadataService.getMCPMetadata(tenant, serviceId);
        } catch (RuntimeException e) {
            log.error("MCP metadata {} failed, service_id={}", refresh ? "refresh" : "read",
                    serviceId, e);
            throw mcpMetadataAppError(e, refresh);
        }
        // 从未同步 → 裸 JSON null（§2.1；Spring 对 null body 会发空正文，故显式给 NullNode）
        return ResponseEntity.ok(snapshot == null
                ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : snapshot);
    }

    /**
     * 对照 Go {@code mayWriteSharedMCPMetadata}：静态鉴权目录的额外门禁。
     *
     * <p>API key 已经过了 manage-MCP 能力校验（Go 直接放行）；Java 阶段 1 没有 API key
     * 主体，故只剩「系统管理员」与「Admin+ 角色」两条——这是**收紧**，不会放行更多。</p>
     */
    private static boolean mayWriteSharedMCPMetadata() {
        if (TenantContext.isSystemAdmin()) {
            return true;
        }
        return RoleVisibility.roleFromContext().hasPermission(TenantRole.ADMIN);
    }

    /** 对照 Go {@code mcpMetadataAppError}：哨兵 error → AppError 的映射 */
    static BizException mcpMetadataAppError(RuntimeException err, boolean refresh) {
        if (err instanceof McpMetadataException me) {
            return switch (me.kind()) {
                case SERVICE_NOT_FOUND -> BizException.notFound("MCP service not found");
                case PRINCIPAL_REQUIRED -> BizException.unauthorized(
                        "OAuth metadata requires an authenticated user");
                case STORAGE_UNAVAILABLE -> new BizException(
                        AppError.serviceUnavailable("MCP metadata storage is unavailable"));
                case CONNECTION_CHANGED -> BizException.conflict(
                        "MCP connection changed during refresh; save the configuration and sync again");
                case TOO_LARGE, INVALID_TOOLS -> BizException.badRequest(
                        "MCP directory is invalid or too large");
                case OTHER -> refresh
                        ? BizException.badRequest(
                        "Failed to refresh MCP tools. Check the connection and try again.")
                        : BizException.internal("Failed to read MCP metadata");
            };
        }
        return refresh
                ? BizException.badRequest("Failed to refresh MCP tools. Check the connection and try again.")
                : BizException.internal("Failed to read MCP metadata");
    }


    @PostMapping("/{id}/usage-instructions/generate")
    public ResponseEntity<?> generateMCPUsageInstructions(@PathVariable("id") String id,
                                                          @RequestBody(required = false) JsonNode body) {
        return usageOps.generateMCPUsageInstructions(id, body);
    }

    /** 薄委托：见 {@link McpUsageInstructionsOps#buildMCPUsageInput}。 */
    static String buildMCPUsageInput(McpService service, McpMetadata snapshot,
                                     List<McpToolApproval> policies) {
        return McpUsageInstructionsOps.buildMCPUsageInput(service, snapshot, policies);
    }

    /** 薄委托：见 {@link McpUsageInstructionsOps#mcpUsageExcerpt}。 */
    static String mcpUsageExcerpt(String value, int limit) {
        return McpUsageInstructionsOps.mcpUsageExcerpt(value, limit);
    }

    // ── 工具审批策略 ─────────────────────────────────────────────────────

    /** 对照 ListMCPToolApprovals — Viewer+ */
    @GetMapping("/{id}/tool-approvals")
    public ResponseEntity<?> listMCPToolApprovals(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        List<McpToolApproval> rows;
        try {
            rows = mcpToolApprovalService.listByService(tenantId, serviceId);
        } catch (RuntimeException e) {
            // 区分"服务不存在"与内部错误，让客户端拿到准确状态码而不是一概 404
            if (isNotFound(e)) {
                throw BizException.notFound(rawMessage(e));
            }
            log.error("Failed to list MCP tool approvals, service_id={}", serviceId, e);
            throw BizException.internal(rawMessage(e));
        }
        return ok(envelope(rows));
    }

    /**
     * 对照 SetMCPToolApproval — Admin+。
     *
     * <p>路由名沿用历史上"只设审批"的端点；现在同时支持 enabled。
     * 两个字段至少提供一个，省略的字段保持原值。</p>
     */
    @PutMapping("/{id}/tool-approvals/{tool_name}")
    public ResponseEntity<?> setMCPToolApproval(@PathVariable("id") String id,
                                                @PathVariable("tool_name") String toolName,
                                                @RequestBody(required = false) McpToolApprovalPolicyRequest body) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        if (body == null) {
            throw BizException.badRequest("EOF");
        }
        // Gin 已对路径参数做过 URL 解码；这里不要再解一次，
        // 否则名字里带字面 "%" 的工具名会被破坏（对照 Go 注释）。
        if (body.requireApproval() == null && body.enabled() == null) {
            throw BizException.badRequest("require_approval or enabled is required");
        }
        try {
            mcpToolApprovalService.setPolicy(tenantId, serviceId, toolName,
                    body.requireApproval(), body.enabled());
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                throw BizException.notFound(rawMessage(e));
            }
            throw BizException.internal(rawMessage(e));
        }
        return ok(successOnly());
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────


    /** 对照 Go 各 handler 的 {@code c.GetUint64(TenantIDContextKey)} + 0 校验 */
    static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        long value = tenantId == null ? 0L : tenantId;
        if (value == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        return value;
    }

    static boolean canViewIntegrationSecrets() {
        return RoleVisibility.canViewIntegrationSecrets();
    }

    private static boolean isOAuth(McpService service) {
        return service.getAuthConfig() != null && service.getAuthConfig().isOAuth();
    }

    /** 对照 Go handler 里 {@code err.Error()}：取业务文案而非 Java 的包装串 */
    static String rawMessage(RuntimeException e) {
        if (e instanceof BizException be) {
            return be.appError().message();
        }
        return e.getMessage() == null ? "" : e.getMessage();
    }

    /** 对照 Go {@code strings.Contains(err.Error(), "not found")} 的判别 */
    private static boolean isNotFound(RuntimeException e) {
        if (e instanceof BizException be) {
            return be.appError().httpCode() == 404;
        }
        return e.getMessage() != null && e.getMessage().contains("not found");
    }



    /** 成功响应：HTTP 200 + gin.H 信封（Go 的 {@code c.JSON(http.StatusOK, ...)}） */
    static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    /** gin.H：{"data":..., "success":true}（字母序 data < success） */
    static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    /** gin.H：{"success":true} */
    private static Map<String, Object> successOnly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    static String sanitize(String value) {
        return LogSanitizer.sanitize(value);
    }
}
