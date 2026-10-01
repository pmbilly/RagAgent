package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.tenant.TenantRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * POST /tenants 创建租户协作者（对照 Go CreateTenant tenant.go L226-513，
 * 自 {@link TenantCatalogController} 机械搬出）：自助/超管双路径、配额预检与
 * TOCTOU 复检、owner 引导与 tenantless 回填、auto_create_api_key 兼容。
 * 持门面回引取各 service 依赖（可变面归门面）。
 */
final class TenantCreateOps {

    private static final Logger log = LoggerFactory.getLogger(TenantCreateOps.class);

    private final TenantCatalogController service;

    TenantCreateOps(TenantCatalogController service) {
        this.service = service;
    }

    /** 对照 defaultMaxOwnedTenantsPerUser（tenant.go L195）。 */
    private static final int DEFAULT_MAX_OWNED_PER_USER = 10;

    // ── POST /tenants（对照 CreateTenant，tenant.go L226-513） ──────────────

    public ResponseEntity<?> createTenant(
            @RequestBody(required = false) String rawBody) {
        User caller = service.userService.getCurrentUser();
        if (caller == null) {
            throw new BizException(AppError.unauthorized("authentication required"));
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        boolean platformCaller = scope != null && scope.isPlatform();
        boolean catalogManager = caller.isCanAccessAllTenants() || platformCaller;

        // 部署级自助开关（对照 resolveTenantSelfServiceCreationEnabled 的三层解析：
        // 以 config 为底，SystemSettingGateway 再叠 DB/env）
        if (!catalogManager && !service.systemSettingService.getBool(
                "tenant.self_service_creation_enabled",
                "WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED",
                service.tenantProperties.isSelfServiceCreationEnabled())) {
            throw new BizException(new AppError(
                    ErrorCode.TENANT_CREATION_DISABLED.value(),
                    "self-service workspace creation is disabled; join a workspace by invitation",
                    null, 403));
        }

        Tenant tenantData;
        if (catalogManager) {
            // 超管/平台 Key：全字段兼容路径（对照 ShouldBindJSON(&types.Tenant)）
            tenantData = TenantBindSupport.bindBody(rawBody, Tenant.class, "Invalid request parameters");
            if (tenantData == null) {
                tenantData = new Tenant(); // body "null" → Go 零值绑定
            }
            tenantData.setId(null); // 主键恒由 DB 生成（Go: tenantData.ID = 0）
        } else {
            CreateTenantRequest req = TenantBindSupport.bindBody(rawBody, CreateTenantRequest.class,
                    "Invalid request parameters");
            List<String> bindingErrors = validateCreateBinding(req);
            if (!bindingErrors.isEmpty()) {
                throw TenantBindSupport.invalidParams("Invalid request parameters", String.join("\n", bindingErrors));
            }
            // 配额预检（对照 L296-321）：cap>0 且 owner 计数 ≥ cap → 429
            int cap = resolveMaxOwnedTenantsPerUser();
            if (cap > 0) {
                int owned = 0;
                for (TenantMember m : service.memberService.listByUser(caller.getId())) {
                    if (m != null && TenantRole.OWNER.value().equals(m.getRole())) {
                        owned++;
                    }
                }
                if (owned >= cap) {
                    throw quotaExceeded();
                }
            }
            tenantData = new Tenant();
            tenantData.setName(TenantBindSupport.trimGo(req.name()));
            tenantData.setDescription(TenantBindSupport.trimGo(req.description()));
        }

        // 默认配额（对照 L334-351）：StorageQuota≤0 → settings 的 GB 值（≤0 再回 10）
        if (tenantData.getStorageQuota() == null || tenantData.getStorageQuota() <= 0) {
            long gb = service.systemSettingService.getInt(
                    "tenant.default_storage_quota_gb",
                    "WEKNORA_TENANT_DEFAULT_STORAGE_QUOTA_GB",
                    10);
            if (gb <= 0) {
                gb = 10;
            }
            tenantData.setStorageQuota(gb * 1024 * 1024 * 1024);
        }

        Tenant created;
        try {
            created = service.tenantService.createTenant(tenantData);
        } catch (IllegalArgumentException e) {
            // service 的非 AppError 错误（如空名）→ 500（对照 L363-373）
            throw new BizException(AppError.internal("Failed to create workspace")
                    .withDetails(e.getMessage()));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to create workspace")
                    .withDetails(e.getMessage()));
        }

        // Owner 引导（对照 L383-397）：失败回滚租户。平台 Key 不建成员（Go: !platformCaller）
        if (!platformCaller) {
            try {
                service.memberService.ensureOwner(caller.getId(), created.getId());
            } catch (RuntimeException e) {
                service.tenantService.deleteTenant(created.getId());
                throw new BizException(AppError.internal("Failed to finalise workspace ownership")
                        .withDetails(e.getMessage()));
            }
            // TOCTOU 复检（对照 L399-434）：提交后再数一遍，超帽回滚
            if (!caller.isCanAccessAllTenants()) {
                int cap = resolveMaxOwnedTenantsPerUser();
                if (cap > 0) {
                    int ownedNow = 0;
                    for (TenantMember m : service.memberService.listByUser(caller.getId())) {
                        if (m != null && TenantRole.OWNER.value().equals(m.getRole())) {
                            ownedNow++;
                        }
                    }
                    if (ownedNow > cap) {
                        service.memberService.removeMember(caller.getId(), created.getId());
                        service.tenantService.deleteTenant(created.getId());
                        throw quotaExceeded();
                    }
                }
            }
        }

        // tenantless 用户首个空间回填（对照 L437-452）：失败回滚成员+租户
        if (caller.getTenantId() == 0 && !platformCaller) {
            caller.setTenantId(created.getId());
            try {
                service.userService.updateUser(caller);
            } catch (RuntimeException e) {
                if (!platformCaller) {
                    try {
                        service.memberService.removeMember(caller.getId(), created.getId());
                    } catch (RuntimeException ignored) {
                        // 对照 Go 的 `_ = h.memberService.RemoveMember(...)`：尽力回滚
                    }
                }
                service.tenantService.deleteTenant(created.getId());
                throw new BizException(AppError.internal("Failed to finalise default workspace")
                        .withDetails(e.getMessage()));
            }
        }

        // auto_create_api_key 兼容路径（对照 L468-490）：失败不拖垮创建
        Object data = created;
        if (!platformCaller && service.systemSettingService.getBool(
                "tenant.auto_create_api_key", "WEKNORA_TENANT_AUTO_CREATE_API_KEY", false)) {
            try {
                var result = service.apiKeyService.create(
                        new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                                created.getId(), null, "default", true, null, null, null));
                data = tenantWithApiKey(created, result.token());
            } catch (RuntimeException e) {
                log.warn("[tenant] auto-create default API key failed for tenant {}: {}",
                        created.getId(), e.toString());
            }
        }

        return ResponseEntity.status(201).body(data);
    }

    /**
     * 对照 tenantWithAPIKey（L496-510）：tenant 序列化为 map 再加 api_key。
     * Go 的 map[string]any 序列化**各层键都按字母序**——递归深排序复刻。
     */
    private Object tenantWithApiKey(Tenant tenant, String token) {
        JsonNode node = service.springMapper.valueToTree(tenant);
        Object sorted = deepSortKeys(node);
        @SuppressWarnings("unchecked")
        TreeMap<String, Object> m = (TreeMap<String, Object>) sorted;
        m.put("api_key", token);
        return m;
    }

    /** 递归把 ObjectNode 转 TreeMap（字母序），数组保序，标量留 JsonNode */
    private static Object deepSortKeys(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), deepSortKeys(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(deepSortKeys(n)));
            return out;
        }
        return node;
    }

    /** 对照 resolveMaxOwnedTenantsPerUser（L198-212）：cfg 底座 → 三层解析 */
    private int resolveMaxOwnedTenantsPerUser() {
        long fallback = DEFAULT_MAX_OWNED_PER_USER;
        if (service.tenantProperties.maxOwnedPerUser() != null && service.tenantProperties.maxOwnedPerUser() != 0) {
            fallback = service.tenantProperties.maxOwnedPerUser();
        }
        return (int) service.systemSettingService.getInt(
                "tenant.max_owned_per_user", "WEKNORA_TENANT_MAX_OWNED_PER_USER", fallback);
    }

    private static BizException quotaExceeded() {
        return new BizException(AppError.tooManyRequests(
                "reached self-service workspace quota; contact an administrator to raise the limit"));
    }

    /** 复刻 createTenantRequest 的 binding：name required,min=1,max=128；description max=512（rune 计） */
    private static List<String> validateCreateBinding(CreateTenantRequest req) {
        List<String> errors = new ArrayList<>();
        String name = req == null ? null : req.name();
        if (name == null || name.isEmpty()) {
            errors.add(TenantBindSupport.bindingError("createTenantRequest", "Name", "required"));
        } else {
            int len = name.codePointCount(0, name.length());
            if (len < 1) {
                errors.add(TenantBindSupport.bindingError("createTenantRequest", "Name", "min"));
            } else if (len > 128) {
                errors.add(TenantBindSupport.bindingError("createTenantRequest", "Name", "max"));
            }
        }
        String description = req == null ? null : req.description();
        if (description != null && description.codePointCount(0, description.length()) > 512) {
            errors.add(TenantBindSupport.bindingError("createTenantRequest", "Description", "max"));
        }
        return errors;
    }

    /** 自助路径的请求载体（对照 createTenantRequest，handler/tenant.go L88-91） */
    static final class CreateTenantRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("name")
        String name;
        @com.fasterxml.jackson.annotation.JsonProperty("description")
        String description;

        String name() { return name; }
        String description() { return description; }
    }
}
