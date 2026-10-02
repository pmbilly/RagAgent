package com.ragagent.auth.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.web.GoJsonBindError;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * tenants CRUD 协作者（对照 Go handler/tenant.go W5a 段 L541-659 / L1123-1181，
 * 自 {@link TenantCatalogController} 机械搬出）：单空间列表、读取/更新/删除与
 * updateTenantRequest 的手写绑定（Go UnmarshalTypeError 文案复刻）。
 * 持门面回引取 tenantService。
 */
final class TenantCrudOps {

    private final TenantCatalogController service;

    TenantCrudOps(TenantCatalogController service) {
        this.service = service;
    }

    // ── W5a：tenants CRUD 4 条（对照 handler/tenant.go L541-659 / L1123-1181） ──

    /**
     * GET /tenants（对照 ListTenants，L1165-1181）：返回**调用者活动空间**的单元素
     * 列表（不是全量目录——全量在 /tenants/all）。上下文无租户 → 401
     * "Authentication required"。Go 路由**无角色门**（只有全局 Auth），Java 侧
     * 同样不登记 RBAC 规则。
     */
    public Map<String, Object> listTenants() {
        Long tenantId = TenantContext.currentTenantId();
        Tenant tenant = tenantId == null || tenantId == 0 ? null : service.tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.unauthorized("Authentication required"));
        }
        List<TenantResponse> items = new ArrayList<>();
        items.add(TenantResponse.from(tenant, contextRoleHasAdmin()));
        return Map.of("items", items);
    }

    /**
     * GET /tenants/{id}（对照 GetTenant，L541-578）。URL :id 的合法性由
     * PathTenantMatch 在中间件层先行校验/拒绝（handler 里的 "Invalid workspace ID"
     * 400 是不可达死代码，约定 §9）；租户缺失 → 500 "Failed to retrieve workspace"
     * details "record not found"（Go 的 repo 错误不是 AppError）。
     */
    public TenantResponse getTenant(@PathVariable("id") String id) {
        Tenant tenant = loadTenantOr500(Long.parseLong(id.trim()), "Failed to retrieve workspace");
        return TenantResponse.from(tenant, contextRoleHasAdmin());
    }

    /**
     * PUT /tenants/{id}（对照 UpdateTenant，L581-657）：白名单只开 name/description
     *（指针区分"未携带"与"显式空串"）。绑定失败 400 "Invalid request data"+details；
     * name trim 后空 → 400 "name cannot be blank"；其余复用 kv 分发器的 500 形态。
     */
    public TenantResponse updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        UpdateTenantRequest req = bindUpdateTenantRequest(rawBody);
        Tenant existing = loadTenantOr500(Long.parseLong(id.trim()), "Failed to load workspace");
        // 注意：绑定（400 语义层）先于租户加载——Go handler 同序

        if (req.name != null) {
            String trimmed = TenantBindSupport.trimGo(req.name);
            if (trimmed.isEmpty()) {
                throw new BizException(AppError.validation("name cannot be blank"));
            }
            existing.setName(trimmed);
        }
        if (req.description != null) {
            existing.setDescription(TenantBindSupport.trimGo(req.description));
        }

        try {
            service.tenantService.updateTenant(existing);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to update workspace")
                    .withDetails(e.getMessage()));
        }
        return TenantResponse.from(existing, contextRoleHasAdmin());
    }

    /** 对照 updateTenantRequest（tenant.go L102-105）：name omitempty,min=1,max=128；description omitempty,max=512。 */
    static final class UpdateTenantRequest {
        @JsonProperty("name")
        String name;
        @JsonProperty("description")
        String description;
    }

    /**
     * 绑定 + validator（rune 计长；失败字段按 struct 序 join("\n")）。
     * 类型错给 Go UnmarshalTypeError 原文（具名 struct →
     * "updateTenantRequest.name of type string"——golden w5a-tenant-put-badjson 钉住）。
     */
    private UpdateTenantRequest bindUpdateTenantRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw TenantBindSupport.invalidParams("Invalid request data", "EOF");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = TenantBindSupport.MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw TenantBindSupport.invalidParams("Invalid request data",
                    GoJsonBindError.message(rawBody, e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            if (root == null || root.isNull()) {
                return new UpdateTenantRequest();
            }
            throw TenantBindSupport.invalidParams("Invalid request data",
                    "json: cannot unmarshal " + TenantBindSupport.goJsonKind(root) + " into Go value of type "
                            + "struct { Name *string \"json:\\\"name\\\" binding:\\\"omitempty,min=1,max=128\\\"\"; "
                            + "Description *string \"json:\\\"description\\\" binding:\\\"omitempty,max=512\\\"\" }");
        }
        String typeError = TenantBindSupport.goStringField(root, "name");
        if (typeError == null) {
            typeError = TenantBindSupport.goStringField(root, "description");
        }
        if (typeError != null) {
            throw TenantBindSupport.invalidParams("Invalid request data", typeError);
        }
        UpdateTenantRequest req = new UpdateTenantRequest();
        req.name = root.get("name") == null || root.get("name").isNull() ? null : root.get("name").asText();
        req.description = root.get("description") == null || root.get("description").isNull()
                ? null : root.get("description").asText();
        List<String> errors = new ArrayList<>();
        if (req.name != null) {
            int len = req.name.codePointCount(0, req.name.length());
            if (len < 1) {
                errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Name", "min"));
            } else if (len > 128) {
                errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Name", "max"));
            }
        }
        if (req.description != null && req.description.codePointCount(0, req.description.length()) > 512) {
            errors.add(TenantBindSupport.bindingError("updateTenantRequest", "Description", "max"));
        }
        if (!errors.isEmpty()) {
            throw TenantBindSupport.invalidParams("Invalid request data", String.join("\n", errors));
        }
        return req;
    }


    /**
     * DELETE /tenants/{id}（对照 DeleteTenant，L1123-1162）：repo 层软删成员+租户、
     * 删不存在的 id 同样成功 → 204。
     */
    public org.springframework.http.ResponseEntity<Void> deleteTenant(@PathVariable("id") String id) {
        try {
            service.tenantService.deleteTenant(Long.parseLong(id.trim()));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to delete workspace")
                    .withDetails(e.getMessage()));
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** GET/PUT 共用：租户缺失 → 500 + details "record not found"（对照 GetTenantByID 的 gorm 原文透传）。 */
    private Tenant loadTenantOr500(long id, String message) {
        Tenant tenant = service.tenantService.getTenantById(id);
        if (tenant == null) {
            throw new BizException(AppError.internal(message).withDetails("record not found"));
        }
        return tenant;
    }

    /** 对照 dto.NewTenantResponse 的 includeSecrets = RoleFromContext ≥ admin。 */
    private static boolean contextRoleHasAdmin() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }
}
