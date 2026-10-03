package com.ragagent.mcp.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import com.ragagent.mcp.domain.McpTestResult;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.dto.McpServiceCreateRequest;
import com.ragagent.mcp.dto.McpServiceResponse;
import com.ragagent.mcp.protocol.McpServiceUrls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * MCP 服务 CRUD 协作者（自 {@link McpServiceController} 拆出）：创建/列表/详情/更新/删除、
 * 连接测试、工具与资源读取。更新端点为存在性映射的 210 行单函数。
 * 持门面回引（ctrl）取 mcpServiceService/ssrfGuard；响应助手经门面类名调用。
 */
final class McpServiceCrudOps {

    private static final Logger log = LoggerFactory.getLogger(McpServiceCrudOps.class);

    private final McpServiceController ctrl;

    McpServiceCrudOps(McpServiceController ctrl) {
        this.ctrl = ctrl;
    }

    // ── 创建 ─────────────────────────────────────────────────────────────

    /** 对照 CreateMCPService — Admin+ */
    public ResponseEntity<?> createMCPService(
            @RequestBody(required = false) McpServiceCreateRequest req) {
        if (req == null) {
            // 对照 gin ShouldBindJSON 空 body → "EOF"
            throw BizException.badRequest("EOF");
        }
        long tenantId = McpServiceController.requireTenant();
        McpService service = req.toService();
        service.setTenantId(tenantId);
        // 落库语义：非指针 bool 的零值会被列默认值 true 替换并回写内存结构，
        // 因此 POST /mcp-services 无论传不传 enabled（哪怕显式传 false）落库与响应都是 true
        // ——创建路径根本无法产出 disabled 的服务。Java 的 primitive boolean 零值是 false，
        // 不补这一步行为就会漂移；这里显式对齐该效果。
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
            ctrl.mcpServiceService.createMCPService(service);
        } catch (BizException e) {
            log.error("Failed to create MCP service, service_name={}",
                    LogSanitizer.sanitize(service.getName()), e);
            throw BizException.internal("Failed to create MCP service: " + McpServiceController.rawMessage(e));
        }

        // 响应用 McpServiceResponse：密钥字段在构造期就不存在，无需运行时脱敏。
        // 创建类端点 → 201 + 资源视图
        return ResponseEntity.status(201).body(
                McpServiceResponse.from(service, McpServiceController.canViewIntegrationSecrets()));
    }

    // ── 列表 / 详情 ──────────────────────────────────────────────────────

    /** 对照 ListMCPServices — Viewer+ */
    public ResponseEntity<?> listMCPServices() {
        long tenantId = McpServiceController.requireTenant();
        List<McpService> services;
        try {
            services = ctrl.mcpServiceService.listMCPServices(tenantId);
        } catch (BizException e) {
            log.error("Failed to list MCP services, tenant_id={}", tenantId, e);
            throw BizException.internal("Failed to list MCP services: " + McpServiceController.rawMessage(e));
        }
        return McpServiceController.ok(mcpServiceResponses(tenantId, services));
    }

    /** 对照 GetMCPService — Viewer+ */
    public ResponseEntity<?> getMCPService(@PathVariable("id") String id) {
        long tenantId = McpServiceController.requireTenant();
        McpService service;
        try {
            service = ctrl.mcpServiceService.getMCPServiceByID(tenantId, McpServiceController.sanitize(id));
        } catch (RuntimeException e) {
            log.warn("MCP service not found, service_id={}", McpServiceController.sanitize(id));
            throw BizException.notFound("MCP service not found");
        }
        return McpServiceController.ok(mcpServiceResponses(tenantId, List.of(service)).get(0));
    }

    // ── 更新（handler 210 行单函数：存在性语义 + 凭据保护 + 连接失效 + DTO 组装） ──

    /**
     * 对照 UpdateMCPService — Admin+。
     *
     * <p>标量字段用<b>存在性映射</b>（updateFields），因为零值无法区分
     * "没传"与"显式清空"；非标量字段按类型判断，类型不符即静默跳过。
     * 秘密字段（auth_config.api_key / token）<b>永不</b>从主 PUT 读取，只记一条 deprecation 日志。</p>
     */
    public ResponseEntity<?> updateMCPService(@PathVariable("id") String id,
                                              @RequestBody(required = false) JsonNode updateData) {
        if (updateData == null) {
            throw BizException.badRequest("EOF");
        }
        if (!updateData.isObject() && !updateData.isNull()) {
            // 非 JSON 对象 → 400；错误文案是契约的一部分，保持原样
            throw BizException.badRequest("json: cannot unmarshal non-object into Go value of type map[string]interface {}");
        }
        long tenantId = McpServiceController.requireTenant();
        String serviceId = McpServiceController.sanitize(id);

        McpService service = new McpService();
        service.setId(serviceId);
        service.setTenantId(tenantId);

        // 记录哪些字段被显式更新（Go: updateFields）
        Map<String, Boolean> updateFields = new LinkedHashMap<>();

        if (updateData.has("usageInstructions")) {
            JsonNode raw = updateData.get("usageInstructions");
            String instructions = raw.isTextual() ? raw.asText().trim() : "";
            if (!raw.isTextual() || instructions.isEmpty()
                    || instructions.codePointCount(0, instructions.length()) > 16000) {
                throw BizException.badRequest(
                        "Usage instructions must contain between 1 and 16000 characters");
            }
            service.setUsageInstructions(instructions);
            updateFields.put("usageInstructions", true);
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
            // 显式 true/false 都算更新
            service.setEnabled(updateData.get("enabled").asBoolean());
            updateFields.put("enabled", true);
        }
        if (updateData.path("transportType").isTextual()) {
            service.setTransportType(updateData.get("transportType").asText());
        }
        if (updateData.path("url").isTextual() && !updateData.get("url").asText().isEmpty()) {
            service.setUrl(updateData.get("url").asText());
        } else if (updateData.has("url")) {
            // 显式 null / 空串：置 null。⚠️ 应用层"URL 非空才更新"的判断会让
            // 这一步在落库时变成空操作——既有分层语义如此，不在这里"修好"。
            service.setUrl(null);
        }

        // 更新后的 URL 仍需 SSRF 校验
        validateServiceUrlForSsrf(service.getUrl());

        if (updateData.path("stdioConfig").isObject()) {
            JsonNode stdioConfig = updateData.get("stdioConfig");
            McpStdioConfig config = new McpStdioConfig();
            if (stdioConfig.path("command").isTextual()) {
                config.setCommand(stdioConfig.get("command").asText());
            }
            if (stdioConfig.path("args").isArray()) {
                // 非字符串元素占位为空串（不是丢弃）
                JsonNode args = stdioConfig.get("args");
                List<String> list = new ArrayList<>(args.size());
                for (JsonNode arg : args) {
                    list.add(arg.isTextual() ? arg.asText() : "");
                }
                config.setArgs(list);
            }
            service.setStdioConfig(config);
        }
        if (updateData.path("envVars").isObject()) {
            service.setEnvVars(stringMap(updateData.get("envVars")));
        }
        if (updateData.path("headers").isObject()) {
            service.setHeaders(stringMap(updateData.get("headers")));
        }
        if (updateData.path("authConfig").isObject()) {
            JsonNode authConfig = updateData.get("authConfig");
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
            if (authConfig.path("customHeaders").isObject()) {
                auth.setCustomHeaders(stringMap(authConfig.get("customHeaders")));
            }
            // auth_type / scopes / auth_server_metadata_url 属非秘密 OAuth 配置，允许经主 PUT 切换
            if (authConfig.path("authType").isTextual()) {
                auth.setAuthType(McpAuthType.fromValue(authConfig.get("authType").asText()));
                updateFields.put("authType", true);
            }
            // api_key_header 是非秘密的结构配置（承载 api_key 的头名），与 custom_headers 同路
            if (authConfig.path("apiKeyHeader").isTextual()) {
                auth.setApiKeyHeader(authConfig.get("apiKeyHeader").asText());
                updateFields.put("apiKeyHeader", true);
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
            if (authConfig.path("authServerMetadataUrl").isTextual()) {
                auth.setAuthServerMetadataUrl(authConfig.get("authServerMetadataUrl").asText());
            }
            service.setAuthConfig(auth);
        }
        if (updateData.path("advancedConfig").isObject()) {
            JsonNode advanced = updateData.get("advancedConfig");
            McpAdvancedConfig config = new McpAdvancedConfig();
            // 仅接受 JSON number → int；其它类型静默跳过
            if (advanced.path("timeout").isNumber()) {
                config.setTimeout(advanced.get("timeout").asInt());
            }
            if (advanced.path("retryCount").isNumber()) {
                config.setRetryCount(advanced.get("retryCount").asInt());
            }
            if (advanced.path("retryDelay").isNumber()) {
                config.setRetryDelay(advanced.get("retryDelay").asInt());
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
            ctrl.mcpServiceService.updateMCPService(service, updateFields);
        } catch (BizException e) {
            log.error("Failed to update MCP service, service_id={}", serviceId, e);
            throw BizException.internal("Failed to update MCP service: " + McpServiceController.rawMessage(e));
        }

        log.info("MCP service updated successfully: {}", serviceId);

        // 重新读回：拾取服务端的合并结果（CustomHeaders 保留等），并用"无秘密"的 DTO 响应
        McpService stored;
        try {
            stored = ctrl.mcpServiceService.getMCPServiceByID(tenantId, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to fetch updated MCP service: " + McpServiceController.rawMessage(e));
        }
        return McpServiceController.ok(mcpServiceResponses(tenantId, List.of(stored)).get(0));
    }

    // ── 删除 ─────────────────────────────────────────────────────────────

    /** 对照 DeleteMCPService — Admin+ */
    public ResponseEntity<?> deleteMCPService(@PathVariable("id") String id) {
        long tenantId = McpServiceController.requireTenant();
        String serviceId = McpServiceController.sanitize(id);
        try {
            ctrl.mcpServiceService.deleteMCPService(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to delete MCP service, service_id={}", serviceId, e);
            throw BizException.internal("Failed to delete MCP service: " + McpServiceController.rawMessage(e));
        }
        log.info("MCP service deleted successfully: {}", serviceId);
        // 同步完成的删除 → 204（无响应体）
        return ResponseEntity.noContent().build();
    }

    // ── 连接测试 / 工具 / 资源 ────────────────────────────────────────────

    /**
     * 对照 TestMCPService — Admin+（会主动探测外部基础设施）。
     *
     * <p>与其它端点不同：连接失败也返回 <b>200</b>，把失败装进
     * {@code data.success=false} 的业务结果里，前端据此渲染测试面板。</p>
     */
    public ResponseEntity<?> testMCPService(@PathVariable("id") String id) {
        long tenantId = McpServiceController.requireTenant();
        String serviceId = McpServiceController.sanitize(id);
        log.info("Testing MCP service: {}", serviceId);

        McpTestResult result;
        try {
            result = ctrl.mcpServiceService.testMCPService(tenantId, serviceId);
        } catch (RuntimeException e) {
            log.error("MCP service test failed, service_id={}", serviceId, e);
            return McpServiceController.ok(McpTestResult.fail("Test failed: " + McpServiceController.rawMessage(e)));
        }
        log.info("MCP service test completed: {}, success: {}", serviceId, result.isSuccess());
        return McpServiceController.ok(result);
    }

    /** 对照 GetMCPServiceTools — Viewer+（不落库） */
    public ResponseEntity<?> getMCPServiceTools(@PathVariable("id") String id) {
        long tenantId = McpServiceController.requireTenant();
        String serviceId = McpServiceController.sanitize(id);
        List<McpTool> tools;
        try {
            tools = ctrl.mcpServiceService.getMCPServiceTools(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to get MCP service tools, service_id={}", serviceId, e);
            throw BizException.internal("Failed to get MCP service tools: " + McpServiceController.rawMessage(e));
        }
        return McpServiceController.ok(tools);
    }

    /** 对照 GetMCPServiceResources — Viewer+ */
    public ResponseEntity<?> getMCPServiceResources(@PathVariable("id") String id) {
        long tenantId = McpServiceController.requireTenant();
        String serviceId = McpServiceController.sanitize(id);
        List<McpResource> resources;
        try {
            resources = ctrl.mcpServiceService.getMCPServiceResources(tenantId, serviceId);
        } catch (BizException e) {
            log.error("Failed to get MCP service resources, service_id={}", serviceId, e);
            throw BizException.internal("Failed to get MCP service resources: " + McpServiceController.rawMessage(e));
        }
        return McpServiceController.ok(resources);
    }
    private List<McpServiceResponse> mcpServiceResponses(long tenantId, List<McpService> services) {
        List<McpServiceResponse> resp = McpServiceResponse.listOf(services, McpServiceController.canViewIntegrationSecrets());
        if (services.isEmpty()) {
            return resp;
        }
        Map<String, McpMetadataSummary> summaries;
        try {
            summaries = ctrl.mcpServiceService.listMCPMetadataSummaries(tenantId, services);
        } catch (RuntimeException e) {
            log.error("Failed to list MCP metadata summaries, tenant_id={}", tenantId, e);
            return resp;
        }
        McpServiceResponse.attachCatalogs(resp, services, summaries);
        return resp;
    }
    /** SSH 校验：出站 URL 必须在白名单/公网范围内（对照 handler L94-100） */
    private void validateServiceUrlForSsrf(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            ctrl.ssrfGuard.validateURLForSSRF(url);
        } catch (SsrfGuard.SsrfException e) {
            log.warn("SSRF validation failed for MCP service URL: {}", e.getMessage());
            throw BizException.badRequest(ctrl.ssrfGuard.formatSSRFError("MCP service URL", url, e));
        }
    }
    /** 只取字符串值：非字符串值丢弃 */
    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                out.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return out;
    }
}
