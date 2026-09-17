package com.ragagent.mcp.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.dto.CredentialsResponse;
import com.ragagent.mcp.service.McpServiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 服务凭据子资源（对照 Go internal/handler/mcp_credentials.go 的
 * MCPCredentialsHandler）。把凭据写入从主资源更新里拆出来带来三点收益：
 *
 * <ol>
 *   <li>主 PUT 正文<b>永不</b>携带秘密——从契约层面消灭"掩码值回传覆盖已存密钥"这类 bug，
 *       而不是靠运行时的"看到掩码就保留"防御；</li>
 *   <li>保存 MCP 编辑弹窗（改超时/启用等）不可能误伤或作废一个可用的凭据，
 *       凭据操作显式且原子；</li>
 *   <li>"是否已配置"的元数据搭在主资源响应上（{@code McpServiceResponse.credentials}），
 *       不需要额外的 GET 端点——这里只有 PUT 和 DELETE。</li>
 * </ol>
 *
 * <p><b>攻击面收敛</b>：本控制器<b>只</b>持有 {@link McpServiceService}，
 * 构造签名刻意不接受任何其它依赖（对照 Go 的 {@code NewMCPCredentialsHandler(svc)}）。</p>
 *
 * <p>路由（WebConfig 注册，均 Admin+）：
 * {@code PUT /api/v1/mcp-services/{id}/credentials}、
 * {@code DELETE /api/v1/mcp-services/{id}/credentials/{field}}。</p>
 */
@RestController
@RequestMapping("/api/v1/mcp-services")
public class McpCredentialsController {

    private static final Logger log = LoggerFactory.getLogger(McpCredentialsController.class);

    private final McpServiceService svc;

    public McpCredentialsController(McpServiceService svc) {
        this.svc = svc;
    }

    /**
     * PUT /credentials 的请求体（对照 Go {@code mcpCredentialsPutRequest}）。
     *
     * <p>两个字段都是包装类型（Go 的 {@code *string}），以便 handler 区分
     * "未提供"（保持原值）与"提供了但为空串"（空操作；要删除请用 DELETE）。
     * 非空值替换已存的秘密。</p>
     */
    public record McpCredentialsPutRequest(
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("token") String token) {
    }

    /**
     * 写入（创建或替换）一个或多个凭据字段，并触发连接回收，
     * 使下一次上游调用立即用上新凭据 — Admin+。
     */
    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
                                 @RequestBody(required = false) McpCredentialsPutRequest req) {
        long tenantId = requireTenant();
        String serviceId = LogSanitizer.sanitize(id);
        if (req == null) {
            req = new McpCredentialsPutRequest(null, null);
        }

        // 无事可做——但不报 400（这是良性的空操作）：读出当前状态原样返回，
        // 客户端会把它当成一次正常的保存。
        if (req.apiKey() == null && req.token() == null) {
            McpService service;
            try {
                service = svc.getMCPServiceByID(tenantId, serviceId);
            } catch (RuntimeException e) {
                throw BizException.notFound("MCP service not found");
            }
            if (service == null) {
                throw BizException.notFound("MCP service not found");
            }
            return ok(envelope(CredentialsResponse.of(
                    configured(service, true), configured(service, false))));
        }

        McpService updated;
        try {
            updated = svc.updateMCPCredentials(tenantId, serviceId, req.apiKey(), req.token());
        } catch (RuntimeException e) {
            log.error("failed to update credentials, service_id={}", serviceId, e);
            throw BizException.internal("failed to update credentials: " + rawMessage(e));
        }
        return ok(envelope(CredentialsResponse.of(
                configured(updated, true), configured(updated, false))));
    }

    /**
     * 删除单个凭据字段。可识别的字段只有 {@code api_key} 与 {@code token}；
     * 成功返回 204（即使该字段本来就是空的——幂等）— Admin+。
     */
    @DeleteMapping("/{id}/credentials/{field}")
    public ResponseEntity<?> deleteField(@PathVariable("id") String id,
                                         @PathVariable("field") String field) {
        long tenantId = requireTenant();
        String serviceId = LogSanitizer.sanitize(id);
        if (!"api_key".equals(field) && !"token".equals(field)) {
            // 字段名白名单：只认这两个，其它值一律拒绝
            throw BizException.badRequest(
                    "unknown credential field: " + LogSanitizer.sanitize(field));
        }
        try {
            svc.clearMCPCredential(tenantId, serviceId, field);
        } catch (RuntimeException e) {
            log.error("failed to clear credential, service_id={}, field={}", serviceId, field, e);
            throw BizException.internal("failed to clear credential: " + rawMessage(e));
        }
        return ResponseEntity.noContent().build();
    }

    /** 对照 Go：{@code svc.AuthConfig != nil && svc.AuthConfig.APIKey != ""} */
    private static boolean configured(McpService service, boolean apiKey) {
        if (service.getAuthConfig() == null) {
            return false;
        }
        String value = apiKey ? service.getAuthConfig().getApiKey()
                : service.getAuthConfig().getToken();
        return value != null && !value.isEmpty();
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        long value = tenantId == null ? 0L : tenantId;
        if (value == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        return value;
    }

    private static String rawMessage(RuntimeException e) {
        if (e instanceof BizException be) {
            return be.appError().message();
        }
        return e.getMessage() == null ? "" : e.getMessage();
    }

    /** 成功响应：HTTP 200 + gin.H 信封（Go 的 {@code c.JSON(http.StatusOK, ...)}） */
    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    /** gin.H：{"data":..., "success":true}（字母序） */
    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }
}
