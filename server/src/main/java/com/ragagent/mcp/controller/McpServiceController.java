package com.ragagent.mcp.controller;

import java.util.ArrayList;
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
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import com.ragagent.mcp.domain.McpTestResult;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.dto.McpServiceCreateRequest;
import com.ragagent.mcp.dto.McpServiceResponse;
import com.ragagent.mcp.dto.McpToolApprovalPolicyRequest;
import com.ragagent.mcp.dto.RoleVisibility;
import com.ragagent.mcp.protocol.McpServiceUrls;
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
 * <p>所有成功响应都是 gin.H → JSON 键按字母序：{@code {"data":...,"success":true}} /
 * {@code {"message":...,"success":true}}。</p>
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
    }

    // ── 创建 ─────────────────────────────────────────────────────────────

    /** 对照 CreateMCPService — Admin+ */
    @PostMapping
    public ResponseEntity<?> createMCPService(
            @RequestBody(required = false) McpServiceCreateRequest req) {
        if (req == null) {
            // 对照 gin ShouldBindJSON 空 body → "EOF"
            throw BizException.badRequest("EOF");
        }
        long tenantId = requireTenant();
        McpService service = req.toService();
        service.setTenantId(tenantId);
        // GORM 的 `gorm:"default:true"` 语义（gorm callbacks/create.go:339-344）：
        // 非指针 bool 的零值会被**标签默认值**替换，并回写内存结构。于是 Go 侧
        // POST /mcp-services 无论传不传 enabled（哪怕显式传 false）落库与响应都是 true
        // ——创建路径根本无法产出 disabled 的服务。Java 的 primitive boolean 零值是 false，
        // 不补这一步就会漂；此处照抄 Go 的净效果。
        service.setEnabled(true);

        // 出站 URL 的 SSRF 校验（对照 handler L93-105）
        validateServiceUrlForSsrf(service.getUrl());
        try {
            McpServiceUrls.validateServiceOutboundUrls(service);
        } catch (RuntimeException e) {
            log.warn("SSRF validation failed for MCP service configuration: {}", e.getMessage());
            throw BizException.badRequest(e.getMessage());
        }

        try {
            mcpServiceService.createMCPService(service);
        } catch (BizException e) {
            log.error("Failed to create MCP service, service_name={}",
                    LogSanitizer.sanitize(service.getName()), e);
            throw BizException.internal("Failed to create MCP service: " + rawMessage(e));
        }

        // 响应用 McpServiceResponse：密钥字段在构造期就不存在，无需运行时脱敏
        return ok(envelope(McpServiceResponse.from(service, canViewIntegrationSecrets())));
    }

    // ── 列表 / 详情 ──────────────────────────────────────────────────────

    /** 对照 ListMCPServices — Viewer+ */
    @GetMapping
    public ResponseEntity<?> listMCPServices() {
        long tenantId = requireTenant();
        List<McpService> services;
        try {
            services = mcpServiceService.listMCPServices(tenantId);
        } catch (BizException e) {
            log.error("Failed to list MCP services, tenant_id={}", tenantId, e);
            throw BizException.internal("Failed to list MCP services: " + rawMessage(e));
        }
        return ok(envelope(mcpServiceResponses(tenantId, services)));
    }

    /** 对照 GetMCPService — Viewer+ */
    @GetMapping("/{id}")
    public ResponseEntity<?> getMCPService(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        McpService service;
        try {
            service = mcpServiceService.getMCPServiceByID(tenantId, sanitize(id));
        } catch (RuntimeException e) {
            log.warn("MCP service not found, service_id={}", sanitize(id));
            throw BizException.notFound("MCP service not found");
        }
        return ok(envelope(mcpServiceResponses(tenantId, List.of(service)).get(0)));
    }

    // ── 更新（handler 210 行单函数：存在性语义 + 凭据保护 + 连接失效 + DTO 组装） ──

    /**
     * 对照 UpdateMCPService — Admin+。
     *
     * <p>逐段对照 Go：标量字段用<b>存在性映射</b>（Go 的 updateFields），因为零值无法区分
     * "没传"与"显式清空"；非标量字段用类型断言，类型不符即静默跳过。
     * 秘密字段（auth_config.api_key / token）<b>永不</b>从主 PUT 读取，只记一条 deprecation 日志。</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateMCPService(@PathVariable("id") String id,
                                              @RequestBody(required = false) JsonNode updateData) {
        if (updateData == null) {
            throw BizException.badRequest("EOF");
        }
        if (!updateData.isObject() && !updateData.isNull()) {
            // 对照 Go json.Unmarshal 到 map[string]interface{} 失败
            throw BizException.badRequest("json: cannot unmarshal non-object into Go value of type map[string]interface {}");
        }
        long tenantId = requireTenant();
        String serviceId = sanitize(id);

        McpService service = new McpService();
        service.setId(serviceId);
        service.setTenantId(tenantId);

        // 记录哪些字段被显式更新（Go: updateFields）
        Map<String, Boolean> updateFields = new LinkedHashMap<>();

        if (updateData.has("usage_instructions")) {
            JsonNode raw = updateData.get("usage_instructions");
            String instructions = raw.isTextual() ? raw.asText().trim() : "";
            if (!raw.isTextual() || instructions.isEmpty()
                    || instructions.codePointCount(0, instructions.length()) > 16000) {
                throw BizException.badRequest(
                        "Usage instructions must contain between 1 and 16000 characters");
            }
            service.setUsageInstructions(instructions);
            updateFields.put("usage_instructions", true);
        }

        if (updateData.path("name").isTextual()) {
            service.setName(updateData.get("name").asText());
            updateFields.put("name", true);
        }
        if (updateData.path("description").isTextual()) {
            service.setDescription(updateData.get("description").asText());
            updateFields.put("description", true);
        }
        if (updateData.path("enabled").isBoolean()) {
            // 显式真/假都算更新（Go 的 if/else 两支等价）
            service.setEnabled(updateData.get("enabled").asBoolean());
            updateFields.put("enabled", true);
        }
        if (updateData.path("transport_type").isTextual()) {
            service.setTransportType(updateData.get("transport_type").asText());
        }
        if (updateData.path("url").isTextual() && !updateData.get("url").asText().isEmpty()) {
            service.setUrl(updateData.get("url").asText());
        } else if (updateData.has("url")) {
            // 显式 null / 空串：置 nil。⚠️ 与 Go 一样，应用层的 `if service.URL != nil` 会让
            // 这一步在落库时变成空操作——照抄 Go 的分层语义，不在这里"修好"。
            service.setUrl(null);
        }

        // 更新后的 URL 仍需 SSRF 校验
        validateServiceUrlForSsrf(service.getUrl());

        if (updateData.path("stdio_config").isObject()) {
            JsonNode stdioConfig = updateData.get("stdio_config");
            McpStdioConfig config = new McpStdioConfig();
            if (stdioConfig.path("command").isTextual()) {
                config.setCommand(stdioConfig.get("command").asText());
            }
            if (stdioConfig.path("args").isArray()) {
                // 对照 Go make([]string, len(args))：非字符串元素占位为空串（不是丢弃）
                JsonNode args = stdioConfig.get("args");
                List<String> list = new ArrayList<>(args.size());
                for (JsonNode arg : args) {
                    list.add(arg.isTextual() ? arg.asText() : "");
                }
                config.setArgs(list);
            }
            service.setStdioConfig(config);
        }
        if (updateData.path("env_vars").isObject()) {
            service.setEnvVars(stringMap(updateData.get("env_vars")));
        }
        if (updateData.path("headers").isObject()) {
            service.setHeaders(stringMap(updateData.get("headers")));
        }
        if (updateData.path("auth_config").isObject()) {
            JsonNode authConfig = updateData.get("auth_config");
            McpAuthConfig auth = new McpAuthConfig();
            // 秘密字段刻意不从主 PUT 读取：它们走 /credentials 子资源，
            // 这样改超时/启用之类的无关配置不可能误伤已存的凭据。
            // 客户端仍在发就记一条告警，便于发现陈旧的调用方。
            if (authConfig.has("api_key")) {
                log.warn("deprecated: api_key in PUT /mcp-services/{} body is ignored; "
                        + "use PUT /credentials instead", serviceId);
            }
            if (authConfig.has("token")) {
                log.warn("deprecated: token in PUT /mcp-services/{} body is ignored; "
                        + "use PUT /credentials instead", serviceId);
            }
            // CustomHeaders 是结构性配置（不是秘密）：nil 保持既有，非 nil 整体替换
            if (authConfig.path("custom_headers").isObject()) {
                auth.setCustomHeaders(stringMap(authConfig.get("custom_headers")));
            }
            // auth_type / scopes / auth_server_metadata_url 属非秘密 OAuth 配置，允许经主 PUT 切换
            if (authConfig.path("auth_type").isTextual()) {
                auth.setAuthType(McpAuthType.fromValue(authConfig.get("auth_type").asText()));
                updateFields.put("auth_type", true);
            }
            // api_key_header 是非秘密的结构配置（承载 api_key 的头名），与 custom_headers 同路
            if (authConfig.path("api_key_header").isTextual()) {
                auth.setApiKeyHeader(authConfig.get("api_key_header").asText());
                updateFields.put("api_key_header", true);
            }
            if (authConfig.path("scopes").isArray()) {
                List<String> scopes = new ArrayList<>();
                for (JsonNode s : authConfig.get("scopes")) {
                    if (s.isTextual()) {
                        scopes.add(s.asText());
                    }
                }
                auth.setScopes(scopes);
            }
            if (authConfig.path("auth_server_metadata_url").isTextual()) {
                auth.setAuthServerMetadataUrl(authConfig.get("auth_server_metadata_url").asText());
            }
            service.setAuthConfig(auth);
        }
        if (updateData.path("advanced_config").isObject()) {
            JsonNode advanced = updateData.get("advanced_config");
            McpAdvancedConfig config = new McpAdvancedConfig();
            // 对照 Go 的 float64 断言：JSON number → int；其它类型静默跳过
            if (advanced.path("timeout").isNumber()) {
                config.setTimeout(advanced.get("timeout").asInt());
            }
            if (advanced.path("retry_count").isNumber()) {
                config.setRetryCount(advanced.get("retry_count").asInt());
            }
            if (advanced.path("retry_delay").isNumber()) {
                config.setRetryDelay(advanced.get("retry_delay").asInt());
            }
            service.setAdvancedConfig(config);
        }

        try {
            McpServiceUrls.validateServiceOutboundUrls(service);
        } catch (RuntimeException e) {
            log.warn("SSRF validation failed for MCP service update: {}", e.getMessage());
            throw BizException.badRequest(e.getMessage());
        }

        try {
            mcpServiceService.updateMCPService(service, updateFields);
        } catch (BizException e) {
            log.error("Failed to update MCP service, service_id={}", serviceId, e);
            throw BizException.internal("Failed to update MCP service: " + rawMessage(e));
        }

        log.info("MCP service updated successfully: {}", serviceId);

        // 重新读回：拾取服务端的合并结果（CustomHeaders 保留等），并用"无秘密"的 DTO 响应
        McpService stored;
        try {
            stored = mcpServiceService.getMCPServiceByID(tenantId, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to fetch updated MCP service: " + rawMessage(e));
        }
        return ok(envelope(mcpServiceResponses(tenantId, List.of(stored)).get(0)));
    }

    // ── 删除 ─────────────────────────────────────────────────────────────

    /** 对照 DeleteMCPService — Admin+ */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteMCPService(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        try {
            mcpServiceService.deleteMCPService(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to delete MCP service, service_id={}", serviceId, e);
            throw BizException.internal("Failed to delete MCP service: " + rawMessage(e));
        }
        log.info("MCP service deleted successfully: {}", serviceId);
        // gin.H：message < success（字母序）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "MCP service deleted successfully");
        body.put("success", true);
        return ok(body);
    }

    // ── 连接测试 / 工具 / 资源 ────────────────────────────────────────────

    /**
     * 对照 TestMCPService — Admin+（会主动探测外部基础设施）。
     *
     * <p>与其它端点不同：连接失败也返回 <b>200</b>，把失败装进
     * {@code data.success=false} 的业务结果里，前端据此渲染测试面板。</p>
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testMCPService(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        log.info("Testing MCP service: {}", serviceId);

        McpTestResult result;
        try {
            result = mcpServiceService.testMCPService(tenantId, serviceId);
        } catch (RuntimeException e) {
            log.error("MCP service test failed, service_id={}", serviceId, e);
            return ok(envelope(McpTestResult.fail("Test failed: " + rawMessage(e))));
        }
        log.info("MCP service test completed: {}, success: {}", serviceId, result.isSuccess());
        return ok(envelope(result));
    }

    /** 对照 GetMCPServiceTools — Viewer+（不落库） */
    @GetMapping("/{id}/tools")
    public ResponseEntity<?> getMCPServiceTools(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        List<McpTool> tools;
        try {
            tools = mcpServiceService.getMCPServiceTools(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to get MCP service tools, service_id={}", serviceId, e);
            throw BizException.internal("Failed to get MCP service tools: " + rawMessage(e));
        }
        return ok(envelope(tools));
    }

    /** 对照 GetMCPServiceResources — Viewer+ */
    @GetMapping("/{id}/resources")
    public ResponseEntity<?> getMCPServiceResources(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String serviceId = sanitize(id);
        List<McpResource> resources;
        try {
            resources = mcpServiceService.getMCPServiceResources(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to get MCP service resources, service_id={}", serviceId, e);
            throw BizException.internal("Failed to get MCP service resources: " + rawMessage(e));
        }
        return ok(envelope(resources));
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
        return ok(envelope(snapshot));
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

    private List<McpServiceResponse> mcpServiceResponses(long tenantId, List<McpService> services) {
        List<McpServiceResponse> resp = McpServiceResponse.listOf(services, canViewIntegrationSecrets());
        if (services.isEmpty()) {
            return resp;
        }
        Map<String, McpMetadataSummary> summaries;
        try {
            summaries = mcpServiceService.listMCPMetadataSummaries(tenantId, services);
        } catch (RuntimeException e) {
            log.error("Failed to list MCP metadata summaries, tenant_id={}", tenantId, e);
            return resp;
        }
        McpServiceResponse.attachCatalogs(resp, services, summaries);
        return resp;
    }

    /** 对照 Go 各 handler 的 {@code c.GetUint64(TenantIDContextKey)} + 0 校验 */
    static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        long value = tenantId == null ? 0L : tenantId;
        if (value == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        return value;
    }

    private static boolean canViewIntegrationSecrets() {
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

    /** SSH 校验：出站 URL 必须在白名单/公网范围内（对照 handler L94-100） */
    private void validateServiceUrlForSsrf(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (SsrfGuard.SsrfException e) {
            log.warn("SSRF validation failed for MCP service URL: {}", e.getMessage());
            throw BizException.badRequest(ssrfGuard.formatSSRFError("MCP service URL", url, e));
        }
    }

    /** 对照 Go 的 map[string]interface{} → map[string]string（非字符串值丢弃） */
    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                out.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return out;
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
