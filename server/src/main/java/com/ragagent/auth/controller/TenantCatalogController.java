package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.apikey.domain.APIKeyCapability;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.domain.tenantconfig.ParserEngineConfig;
import com.ragagent.auth.domain.tenantconfig.RetrievalConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.auth.domain.tenantconfig.TenantConfigRedaction;
import com.ragagent.auth.domain.tenantconfig.WebSearchConfig;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.config.TenantProperties;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.storage.StorageAllowList;
import com.ragagent.system.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go handler/tenant.go 的跨空间租户目录 + KV 配置分发器（波 2 扫尾批 3，
 * routes_auth_tenant.go L53-76 五条路由）：
 *
 * <ul>
 *   <li>GET  /tenants/all     —— ListAllTenants（L1192-1216），viewer 形态裁剪</li>
 *   <li>GET  /tenants/search  —— SearchTenants（L1218-1280），分页 + keyword/tenant_id</li>
 *   <li>POST /tenants         —— CreateTenant（L226-513）：自助/超管双路径、配额、
 *       owner 引导、tenantless 回填、auto_create_api_key 兼容</li>
 *   <li>GET/PUT /tenants/kv/{key} —— KV 分发器（L1304-1395），6 个 DB-backed key；
 *       prompt-templates 推迟（需 vendor Go 的 10 个 yaml + Language 中间件，
 *       本批 Java 落 default → 400，A/B 列 EXPECTED DIFF，见约定 §9）</li>
 * </ul>
 *
 * <p>跨空间守卫（all/search）在 {@code RbacInterceptor.addCrossTenantRule}；
 * 角色下限（kv GET=Viewer+、PUT=Admin+）在 {@code WebConfig}；
 * 三条敏感 key 的 admin 门在本类 {@link #canViewIntegrationSecrets()}。</p>
 */
@RestController
public class TenantCatalogController {

    private static final Logger log = LoggerFactory.getLogger(TenantCatalogController.class);

    /** 请求绑定 mapper：Go json.Unmarshal 忽略未知字段（FAIL_ON_UNKNOWN off）；
     *  JavaTimeModule 供全字段路径（types.Tenant 含 created_at 等）往返 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /** 对照 defaultMaxOwnedTenantsPerUser（tenant.go L195）。 */
    private static final int DEFAULT_MAX_OWNED_PER_USER = 10;

    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final UserService userService;
    private final SystemSettingService systemSettingService;
    private final TenantAPIKeyService apiKeyService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TenantProperties tenantProperties;
    private final SsrfGuard ssrfGuard;
    private final StorageAllowList storageAllowList;
    /** Spring 全局 mapper（带 JacksonConfig 的 OffsetDateTime→本地时区序列化），
     *  仅供 tenantWithApiKey 把实体转成与 Go 字节同形态的时间串 */
    private final ObjectMapper springMapper;

    public TenantCatalogController(TenantService tenantService,
                                   TenantMemberService memberService,
                                   UserService userService,
                                   SystemSettingService systemSettingService,
                                   TenantAPIKeyService apiKeyService,
                                   KnowledgeBaseService knowledgeBaseService,
                                   TenantProperties tenantProperties,
                                   SsrfGuard ssrfGuard,
                                   StorageAllowList storageAllowList,
                                   ObjectMapper springMapper) {
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.userService = userService;
        this.systemSettingService = systemSettingService;
        this.apiKeyService = apiKeyService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.tenantProperties = tenantProperties;
        this.ssrfGuard = ssrfGuard;
        this.storageAllowList = storageAllowList;
        this.springMapper = springMapper;
    }

    // ── GET /tenants/all（对照 ListAllTenants） ─────────────────────────────

    @GetMapping("/api/v1/tenants/all")
    public Map<String, Object> listAllTenants() {
        List<TenantResponse> items = new ArrayList<>();
        for (Tenant t : tenantService.listAllTenants()) {
            // 对照 NewTenantResponsesCrossTenant：恒按 Viewer 裁剪（调用方的
            // 自家租户角色不能解锁别家秘密）
            items.add(TenantResponse.from(t, false));
        }
        return TenantMemberController.envelope(Map.of("items", items));
    }

    // ── GET /tenants/search（对照 SearchTenants） ───────────────────────────

    @GetMapping("/api/v1/tenants/search")
    public Map<String, Object> searchTenants(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "tenant_id", required = false) String tenant_id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String page_size) {
        // 对照 handler 的解析：tenant_id 解析失败→0 忽略；page<1→1；page_size<1→20、>100→100
        long tenantId = 0;
        if (tenant_id != null && !tenant_id.isEmpty()) {
            try {
                tenantId = Long.parseUnsignedLong(tenant_id.trim());
            } catch (NumberFormatException e) {
                tenantId = 0;
            }
        }
        int pageNo = parseIntOr(page, 1);
        if (pageNo < 1) {
            pageNo = 1;
        }
        int pageSize = parseIntOr(page_size, 20);
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        TenantService.TenantSearchPage result =
                tenantService.searchTenants(keyword == null ? "" : keyword, tenantId, pageNo, pageSize);
        List<TenantResponse> items = new ArrayList<>();
        for (Tenant t : result.tenants()) {
            items.add(TenantResponse.from(t, false));
        }
        // Go gin.H 序列化键按字母序：items, page, page_size, total
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        data.put("page", pageNo);
        data.put("page_size", pageSize);
        data.put("total", result.total());
        return TenantMemberController.envelope(data);
    }

    private static int parseIntOr(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── POST /tenants（对照 CreateTenant，tenant.go L226-513） ──────────────

    @PostMapping("/api/v1/tenants")
    public ResponseEntity<Map<String, Object>> createTenant(
            @RequestBody(required = false) String rawBody) {
        User caller = userService.getCurrentUser();
        if (caller == null) {
            throw new BizException(AppError.unauthorized("authentication required"));
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        boolean platformCaller = scope != null && scope.isPlatform();
        boolean catalogManager = caller.isCanAccessAllTenants() || platformCaller;

        // 部署级自助开关（对照 resolveTenantSelfServiceCreationEnabled 的三层解析：
        // 以 config 为底，SystemSettingService 再叠 DB/env）
        if (!catalogManager && !systemSettingService.getBool(
                "tenant.self_service_creation_enabled",
                "WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED",
                tenantProperties.isSelfServiceCreationEnabled())) {
            throw new BizException(new AppError(
                    ErrorCode.TENANT_CREATION_DISABLED.value(),
                    "self-service workspace creation is disabled; join a workspace by invitation",
                    null, 403));
        }

        Tenant tenantData;
        if (catalogManager) {
            // 超管/平台 Key：全字段兼容路径（对照 ShouldBindJSON(&types.Tenant)）
            tenantData = bindBody(rawBody, Tenant.class, "Invalid request parameters");
            if (tenantData == null) {
                tenantData = new Tenant(); // body "null" → Go 零值绑定
            }
            tenantData.setId(null); // 主键恒由 DB 生成（Go: tenantData.ID = 0）
        } else {
            CreateTenantRequest req = bindBody(rawBody, CreateTenantRequest.class,
                    "Invalid request parameters");
            List<String> bindingErrors = validateCreateBinding(req);
            if (!bindingErrors.isEmpty()) {
                throw invalidParams("Invalid request parameters", String.join("\n", bindingErrors));
            }
            // 配额预检（对照 L296-321）：cap>0 且 owner 计数 ≥ cap → 429
            int cap = resolveMaxOwnedTenantsPerUser();
            if (cap > 0) {
                int owned = 0;
                for (TenantMember m : memberService.listByUser(caller.getId())) {
                    if (m != null && TenantRole.OWNER.value().equals(m.getRole())) {
                        owned++;
                    }
                }
                if (owned >= cap) {
                    throw quotaExceeded();
                }
            }
            tenantData = new Tenant();
            tenantData.setName(trimGo(req.name()));
            tenantData.setDescription(trimGo(req.description()));
        }

        // 默认配额（对照 L334-351）：StorageQuota≤0 → settings 的 GB 值（≤0 再回 10）
        if (tenantData.getStorageQuota() == null || tenantData.getStorageQuota() <= 0) {
            long gb = systemSettingService.getInt(
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
            created = tenantService.createTenant(tenantData);
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
                memberService.ensureOwner(caller.getId(), created.getId());
            } catch (RuntimeException e) {
                tenantService.deleteTenant(created.getId());
                throw new BizException(AppError.internal("Failed to finalise workspace ownership")
                        .withDetails(e.getMessage()));
            }
            // TOCTOU 复检（对照 L399-434）：提交后再数一遍，超帽回滚
            if (!caller.isCanAccessAllTenants()) {
                int cap = resolveMaxOwnedTenantsPerUser();
                if (cap > 0) {
                    int ownedNow = 0;
                    for (TenantMember m : memberService.listByUser(caller.getId())) {
                        if (m != null && TenantRole.OWNER.value().equals(m.getRole())) {
                            ownedNow++;
                        }
                    }
                    if (ownedNow > cap) {
                        memberService.removeMember(caller.getId(), created.getId());
                        tenantService.deleteTenant(created.getId());
                        throw quotaExceeded();
                    }
                }
            }
        }

        // tenantless 用户首个空间回填（对照 L437-452）：失败回滚成员+租户
        if (caller.getTenantId() == 0 && !platformCaller) {
            caller.setTenantId(created.getId());
            try {
                userService.updateUser(caller);
            } catch (RuntimeException e) {
                if (!platformCaller) {
                    try {
                        memberService.removeMember(caller.getId(), created.getId());
                    } catch (RuntimeException ignored) {
                        // 对照 Go 的 `_ = h.memberService.RemoveMember(...)`：尽力回滚
                    }
                }
                tenantService.deleteTenant(created.getId());
                throw new BizException(AppError.internal("Failed to finalise default workspace")
                        .withDetails(e.getMessage()));
            }
        }

        // auto_create_api_key 兼容路径（对照 L468-490）：失败不拖垮创建
        Object data = created;
        if (!platformCaller && systemSettingService.getBool(
                "tenant.auto_create_api_key", "WEKNORA_TENANT_AUTO_CREATE_API_KEY", false)) {
            try {
                var result = apiKeyService.create(
                        new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                                created.getId(), null, "default", true, null, null, null));
                data = tenantWithApiKey(created, result.token());
            } catch (RuntimeException e) {
                log.warn("[tenant] auto-create default API key failed for tenant {}: {}",
                        created.getId(), e.toString());
            }
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return ResponseEntity.status(201).body(body);
    }

    /**
     * 对照 tenantWithAPIKey（L496-510）：tenant 序列化为 map 再加 api_key。
     * Go 的 map[string]any 序列化**各层键都按字母序**——递归深排序复刻。
     */
    private Object tenantWithApiKey(Tenant tenant, String token) {
        JsonNode node = springMapper.valueToTree(tenant);
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
        if (tenantProperties.maxOwnedPerUser() != null && tenantProperties.maxOwnedPerUser() != 0) {
            fallback = tenantProperties.maxOwnedPerUser();
        }
        return (int) systemSettingService.getInt(
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
            errors.add(bindingError("createTenantRequest", "Name", "required"));
        } else {
            int len = name.codePointCount(0, name.length());
            if (len < 1) {
                errors.add(bindingError("createTenantRequest", "Name", "min"));
            } else if (len > 128) {
                errors.add(bindingError("createTenantRequest", "Name", "max"));
            }
        }
        String description = req == null ? null : req.description();
        if (description != null && description.codePointCount(0, description.length()) > 512) {
            errors.add(bindingError("createTenantRequest", "Description", "max"));
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

    // ── W5a：tenants CRUD 4 条（对照 handler/tenant.go L541-659 / L1123-1181） ──

    /**
     * GET /tenants（对照 ListTenants，L1165-1181）：返回**调用者活动空间**的单元素
     * 列表（不是全量目录——全量在 /tenants/all）。上下文无租户 → 401
     * "Authentication required"。Go 路由**无角色门**（只有全局 Auth），Java 侧
     * 同样不登记 RBAC 规则。
     */
    @GetMapping("/api/v1/tenants")
    public Map<String, Object> listTenants() {
        Long tenantId = TenantContext.currentTenantId();
        Tenant tenant = tenantId == null || tenantId == 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.unauthorized("Authentication required"));
        }
        List<TenantResponse> items = new ArrayList<>();
        items.add(TenantResponse.from(tenant, contextRoleHasAdmin()));
        return TenantMemberController.envelope(Map.of("items", items));
    }

    /**
     * GET /tenants/{id}（对照 GetTenant，L541-578）。URL :id 的合法性由
     * PathTenantMatch 在中间件层先行校验/拒绝（handler 里的 "Invalid workspace ID"
     * 400 是不可达死代码，约定 §9）；租户缺失 → 500 "Failed to retrieve workspace"
     * details "record not found"（Go 的 repo 错误不是 AppError）。
     */
    @GetMapping("/api/v1/tenants/{id}")
    public Map<String, Object> getTenant(@PathVariable("id") String id) {
        Tenant tenant = loadTenantOr500(Long.parseLong(id.trim()), "Failed to retrieve workspace");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", TenantResponse.from(tenant, contextRoleHasAdmin()));
        body.put("success", true);
        return body;
    }

    /**
     * PUT /tenants/{id}（对照 UpdateTenant，L581-657）：白名单只开 name/description
     *（指针区分"未携带"与"显式空串"）。绑定失败 400 "Invalid request data"+details；
     * name trim 后空 → 400 "name cannot be blank"；其余复用 kv 分发器的 500 形态。
     */
    @PutMapping("/api/v1/tenants/{id}")
    public Map<String, Object> updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        UpdateTenantRequest req = bindUpdateTenantRequest(rawBody);
        Tenant existing = loadTenantOr500(Long.parseLong(id.trim()), "Failed to load workspace");
        // 注意：绑定（400 语义层）先于租户加载——Go handler 同序

        if (req.name != null) {
            String trimmed = trimGo(req.name);
            if (trimmed.isEmpty()) {
                throw new BizException(AppError.validation("name cannot be blank"));
            }
            existing.setName(trimmed);
        }
        if (req.description != null) {
            existing.setDescription(trimGo(req.description));
        }

        try {
            tenantService.updateTenant(existing);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to update workspace")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", TenantResponse.from(existing, contextRoleHasAdmin()));
        body.put("success", true);
        return body;
    }

    /** 对照 updateTenantRequest（tenant.go L102-105）：name omitempty,min=1,max=128；description omitempty,max=512。 */
    static final class UpdateTenantRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("name")
        String name;
        @com.fasterxml.jackson.annotation.JsonProperty("description")
        String description;
    }

    /**
     * 绑定 + validator（rune 计长；失败字段按 struct 序 join("\n")）。
     * 类型错给 Go UnmarshalTypeError 原文（具名 struct →
     * "updateTenantRequest.name of type string"——golden w5a-tenant-put-badjson 钉住）。
     */
    private UpdateTenantRequest bindUpdateTenantRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams("Invalid request data", "EOF");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw invalidParams("Invalid request data",
                    GoJsonBindError.message(rawBody, e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            if (root == null || root.isNull()) {
                return new UpdateTenantRequest();
            }
            throw invalidParams("Invalid request data",
                    "json: cannot unmarshal " + goJsonKind(root) + " into Go value of type "
                            + "struct { Name *string \"json:\\\"name\\\" binding:\\\"omitempty,min=1,max=128\\\"\"; "
                            + "Description *string \"json:\\\"description\\\" binding:\\\"omitempty,max=512\\\"\" }");
        }
        String typeError = goStringField(root, "name");
        if (typeError == null) {
            typeError = goStringField(root, "description");
        }
        if (typeError != null) {
            throw invalidParams("Invalid request data", typeError);
        }
        UpdateTenantRequest req = new UpdateTenantRequest();
        req.name = root.get("name") == null || root.get("name").isNull() ? null : root.get("name").asText();
        req.description = root.get("description") == null || root.get("description").isNull()
                ? null : root.get("description").asText();
        List<String> errors = new ArrayList<>();
        if (req.name != null) {
            int len = req.name.codePointCount(0, req.name.length());
            if (len < 1) {
                errors.add(bindingError("updateTenantRequest", "Name", "min"));
            } else if (len > 128) {
                errors.add(bindingError("updateTenantRequest", "Name", "max"));
            }
        }
        if (req.description != null && req.description.codePointCount(0, req.description.length()) > 512) {
            errors.add(bindingError("updateTenantRequest", "Description", "max"));
        }
        if (!errors.isEmpty()) {
            throw invalidParams("Invalid request data", String.join("\n", errors));
        }
        return req;
    }

    /** Go json.Decoder 的值种别（UnmarshalTypeError 文案用）。 */
    private static String goJsonKind(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "bool";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        return "number";
    }

    /** string 字段类型检查；违规返回 Go UnmarshalTypeError 原文，否则 null。 */
    private static String goStringField(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isTextual()) {
            return null;
        }
        return "json: cannot unmarshal " + goJsonKind(node)
                + " into Go struct field updateTenantRequest." + field + " of type string";
    }

    /**
     * DELETE /tenants/{id}（对照 DeleteTenant，L1123-1162）：repo 层软删成员+租户、
     * 删不存在的 id 同样成功 → 恒 200 {"message","success"}。
     */
    @DeleteMapping("/api/v1/tenants/{id}")
    public Map<String, Object> deleteTenant(@PathVariable("id") String id) {
        try {
            tenantService.deleteTenant(Long.parseLong(id.trim()));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to delete workspace")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "Workspace deleted successfully");
        body.put("success", true);
        return body;
    }

    /** GET/PUT 共用：租户缺失 → 500 + details "record not found"（对照 GetTenantByID 的 gorm 原文透传）。 */
    private Tenant loadTenantOr500(long id, String message) {
        Tenant tenant = tenantService.getTenantById(id);
        if (tenant == null) {
            throw new BizException(AppError.internal(message).withDetails("record not found"));
        }
        return tenant;
    }

    /** 对照 dto.NewTenantResponse 的 includeSecrets = RoleFromContext ≥ admin。 */
    private static boolean contextRoleHasAdmin() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }

    // ── KV 分发器（对照 GetTenantKV / UpdateTenantKV，L1304-1395） ──────────

    @GetMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> getTenantKV(@PathVariable String key) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> TenantMemberController.envelope(getWebSearch());
            case "parser-engine-config" -> TenantMemberController.envelope(getParserEngine());
            case "storage-engine-config" -> TenantMemberController.envelope(getStorageEngine());
            case "chat-history-config" -> TenantMemberController.envelope(getChatHistory());
            case "retrieval-config" -> TenantMemberController.envelope(getRetrieval());
            case "memory-config" -> TenantMemberController.envelope(getMemory());
            // prompt-templates 推迟（类注释）；Go PUT 分发器本来就没有它 → 同样 400
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    @PutMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> updateTenantKV(@PathVariable String key,
                                              @RequestBody(required = false) String rawBody) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> putWebSearch(rawBody);
            case "parser-engine-config" -> putParserEngine(rawBody);
            case "storage-engine-config" -> putStorageEngine(rawBody);
            case "chat-history-config" -> putChatHistory(rawBody);
            case "retrieval-config" -> putRetrieval(rawBody);
            case "memory-config" -> putMemory(rawBody);
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    /**
     * 对照分发器前置的敏感 key 门（L1309-1315 / L1363-1369）+
     * dto.CanViewIntegrationSecrets：角色 ≥ admin，或 API Key 具备
     * full-access / manage_tenant_settings。
     */
    private void requireIntegrationSecretsIfSensitive(String key) {
        if (!"web-search-config".equals(key)
                && !"parser-engine-config".equals(key)
                && !"storage-engine-config".equals(key)) {
            return;
        }
        if (canViewIntegrationSecrets()) {
            return;
        }
        throw new BizException(AppError.forbidden("integration configuration requires admin access"));
    }

    private boolean canViewIntegrationSecrets() {
        TenantRole role = TenantRole.fromString(TenantContext.currentRole());
        if (role != null && role.hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return false;
        }
        return scope.fullAccess() || scope.hasCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS);
    }

    /** 对照各子 handler 的 TenantInfoFromContext 检查：上下文无租户 → 400 */
    private Tenant requireContextTenant() {
        Long tenantId = TenantContext.currentTenantId();
        Tenant tenant = tenantId == null || tenantId == 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.badRequest("Workspace is empty"));
        }
        return tenant;
    }

    /**
     * Jsonb 列 → 强类型（对照 GORM Scan 语义）：SQL NULL → null；
     * jsonb 'null' → 已分配的零值对象（Go 对非 NULL 列先分配再 Unmarshal，
     * "null" 落在零值 struct 上）；对象 → 按字段绑定。
     */
    private static <T> T parseConfig(JsonNode node, Class<T> type) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isNull()) {
                return type.getDeclaredConstructor().newInstance();
            }
            return MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode tenant config jsonb: " + e.getMessage(), e);
        }
    }

    /** PUT 失败分支统一形态：BizException(AppError) 透传，其余 → 500 + details */
    private RuntimeException updateFailed(String message, RuntimeException e) {
        if (e instanceof BizException be) {
            return be;
        }
        return new BizException(AppError.internal(message).withDetails(e.getMessage()));
    }

    /** 对照 gin.H{"success","data","message"}：字母序 data, message, success */
    private static Map<String, Object> envelopeWithMessage(Object data, String message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("data", data);
        body.put("message", message);
        body.put("success", true);
        return body;
    }

    // ── web-search-config（对照 L1398-1472） ────────────────────────────────

    private WebSearchConfig getWebSearch() {
        Tenant tenant = requireContextTenant();
        return TenantConfigRedaction.webSearchForResponse(
                parseConfig(tenant.getWebSearchConfig(), WebSearchConfig.class));
    }

    private Map<String, Object> putWebSearch(String rawBody) {
        WebSearchConfig cfg = bindBody(rawBody, WebSearchConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new WebSearchConfig();
        }
        Tenant tenant = requireContextTenant();
        WebSearchConfig merged = TenantConfigRedaction.mergeWebSearch(
                cfg, parseConfig(tenant.getWebSearchConfig(), WebSearchConfig.class));
        if (merged.getMaxResults() < 1 || merged.getMaxResults() > 50) {
            throw new BizException(AppError.badRequest("max_results must be between 1 and 50"));
        }
        tenant.setWebSearchConfig(MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace web search config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.webSearchForResponse(merged),
                "Web search configuration updated successfully");
    }

    // ── parser-engine-config（对照 L1474-1533） ─────────────────────────────

    private ParserEngineConfig getParserEngine() {
        Tenant tenant = requireContextTenant();
        ParserEngineConfig data = TenantConfigRedaction.parserEngineForResponse(
                parseConfig(tenant.getParserEngineConfig(), ParserEngineConfig.class));
        return data == null ? new ParserEngineConfig() : data;
    }

    private Map<String, Object> putParserEngine(String rawBody) {
        ParserEngineConfig cfg = bindBody(rawBody, ParserEngineConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new ParserEngineConfig();
        }
        Tenant tenant = requireContextTenant();
        ParserEngineConfig merged = TenantConfigRedaction.mergeParserEngine(
                cfg, parseConfig(tenant.getParserEngineConfig(), ParserEngineConfig.class));
        validateParserEngineOutboundUrls(merged);
        tenant.setParserEngineConfig(MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace parser engine config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.parserEngineForResponse(merged), "解析引擎配置已更新");
    }

    /** 对照 validateParserEngineOutboundURLs（L1904-1930）：四处 URL 过 SSRF */
    private void validateParserEngineOutboundUrls(ParserEngineConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (!cfg.getMineruEndpoint().trim().isEmpty()) {
            ssrfCheck("mineru_endpoint", cfg.getMineruEndpoint().trim());
        }
        if (!cfg.getMineruVlmServerUrl().trim().isEmpty()) {
            ssrfCheck("mineru_vlm_server_url", cfg.getMineruVlmServerUrl().trim());
        }
        if (!cfg.getOdlHybridUrl().trim().isEmpty()) {
            ssrfCheck("odl_hybrid_url", cfg.getOdlHybridUrl().trim());
        }
        if (!cfg.getPaddleOcrVlEndpoint().trim().isEmpty()) {
            ssrfCheck("paddleocr_vl_endpoint", cfg.getPaddleOcrVlEndpoint().trim());
        }
    }

    private void ssrfCheck(String field, String url) {
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (RuntimeException e) {
            throw new BizException(AppError.validation(
                    field + " failed SSRF validation: " + e.getMessage()));
        }
    }

    // ── storage-engine-config（对照 L1536-1598） ────────────────────────────

    private StorageEngineConfig getStorageEngine() {
        Tenant tenant = requireContextTenant();
        StorageEngineConfig data = TenantConfigRedaction.storageEngineForResponse(
                parseConfig(tenant.getStorageEngineConfig(), StorageEngineConfig.class));
        return data == null ? new StorageEngineConfig() : data;
    }

    private Map<String, Object> putStorageEngine(String rawBody) {
        StorageEngineConfig cfg = bindBody(rawBody, StorageEngineConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new StorageEngineConfig();
        }
        // 对照 L1556-1570：provider 归一 → 缺省取 firstAllowed → 白名单校验（在租户检查之前）
        String provider = cfg.getDefaultProvider().trim().toLowerCase(java.util.Locale.ROOT);
        if (provider.isEmpty()) {
            provider = firstAllowedStorageProvider();
        }
        if (provider.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "No storage provider is allowed by STORAGE_ALLOW_LIST"));
        }
        if (!storageAllowList.isAllowed(provider)) {
            throw new BizException(AppError.badRequest(
                    "Storage provider is not allowed by STORAGE_ALLOW_LIST"));
        }
        cfg.setDefaultProvider(provider);
        Tenant tenant = requireContextTenant();
        StorageEngineConfig merged = TenantConfigRedaction.mergeStorageEngine(
                cfg, parseConfig(tenant.getStorageEngineConfig(), StorageEngineConfig.class));
        tenant.setStorageEngineConfig(MAPPER.valueToTree(merged));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace storage engine config", e);
        }
        return envelopeWithMessage(
                TenantConfigRedaction.storageEngineForResponse(merged), "存储引擎配置已更新");
    }

    /** 对照 firstAllowedStorageProvider：白名单序的第一个允许项（全允许时为 local） */
    private String firstAllowedStorageProvider() {
        List<String> allowed = storageAllowList.allowedList();
        return allowed.isEmpty() ? "" : allowed.get(0);
    }

    // ── chat-history-config（对照 L1634-1729） ──────────────────────────────

    private ChatHistoryConfig getChatHistory() {
        Tenant tenant = requireContextTenant();
        ChatHistoryConfig data = parseConfig(tenant.getChatHistoryConfig(), ChatHistoryConfig.class);
        return data == null ? new ChatHistoryConfig() : data;
    }

    private Map<String, Object> putChatHistory(String rawBody) {
        ChatHistoryConfig req = bindBody(rawBody, ChatHistoryConfig.class, "Invalid request data");
        if (req == null) {
            req = new ChatHistoryConfig();
        }
        Tenant tenant = requireContextTenant();
        ChatHistoryConfig existing = parseConfig(tenant.getChatHistoryConfig(), ChatHistoryConfig.class);

        // 对照 L1680-1693：重建对象（knowledge_base_id 不受客户端控制），
        // 嵌入模型未变时沿用存量 KB
        ChatHistoryConfig cfg = new ChatHistoryConfig();
        cfg.setEnabled(req.isEnabled());
        cfg.setEmbeddingModelId(req.getEmbeddingModelId());
        if (existing != null && !existing.getKnowledgeBaseId().isEmpty()
                && existing.getEmbeddingModelId().equals(req.getEmbeddingModelId())) {
            cfg.setKnowledgeBaseId(existing.getKnowledgeBaseId());
        }

        // 对照 L1696-1716：enabled + 有模型 + 无 KB → 自动建隐藏 KB
        if (cfg.isEnabled() && !cfg.getEmbeddingModelId().isEmpty()
                && cfg.getKnowledgeBaseId().isEmpty()) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName("__chat_history__");
            kb.setType("document");
            kb.setIsTemporary(true);
            kb.setDescription("Auto-managed knowledge base for chat history message indexing");
            kb.setEmbeddingModelId(cfg.getEmbeddingModelId());
            KnowledgeBase createdKb;
            try {
                createdKb = knowledgeBaseService.createKnowledgeBase(kb);
            } catch (RuntimeException e) {
                throw new BizException(AppError.internal("Failed to create chat history knowledge base")
                        .withDetails(e.getMessage()));
            }
            cfg.setKnowledgeBaseId(createdKb.getId());
        }

        tenant.setChatHistoryConfig(MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update chat history config", e);
        }
        return envelopeWithMessage(cfg, "Chat history configuration updated successfully");
    }

    // ── retrieval-config（对照 L1731-1806） ─────────────────────────────────

    private RetrievalConfig getRetrieval() {
        Tenant tenant = requireContextTenant();
        RetrievalConfig data = parseConfig(tenant.getRetrievalConfig(), RetrievalConfig.class);
        return data == null ? new RetrievalConfig() : data;
    }

    private Map<String, Object> putRetrieval(String rawBody) {
        RetrievalConfig cfg = bindBody(rawBody, RetrievalConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new RetrievalConfig();
        }
        // 对照 L1759-1784：五段范围校验（在租户检查之前）
        if (cfg.getVectorThreshold() < 0 || cfg.getVectorThreshold() > 1) {
            throw new BizException(AppError.badRequest("vector_threshold must be between 0 and 1"));
        }
        if (cfg.getKeywordThreshold() < 0 || cfg.getKeywordThreshold() > 1) {
            throw new BizException(AppError.badRequest("keyword_threshold must be between 0 and 1"));
        }
        if (cfg.getRerankThreshold() < -10 || cfg.getRerankThreshold() > 10) {
            throw new BizException(AppError.badRequest("rerank_threshold must be between -10 and 10"));
        }
        if (cfg.getEmbeddingTopK() < 0 || cfg.getEmbeddingTopK() > 200) {
            throw new BizException(AppError.badRequest("embedding_top_k must be between 0 and 200"));
        }
        if (cfg.getRerankTopK() < 0 || cfg.getRerankTopK() > 200) {
            throw new BizException(AppError.badRequest("rerank_top_k must be between 0 and 200"));
        }
        Tenant tenant = requireContextTenant();
        tenant.setRetrievalConfig(MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update retrieval config", e);
        }
        return envelopeWithMessage(cfg, "Retrieval configuration updated successfully");
    }

    // ── memory-config（对照 L1808-1901；MemoryConfig 本体在 memory 模块） ────

    private MemoryConfig getMemory() {
        Tenant tenant = requireContextTenant();
        MemoryConfig data = parseConfig(tenant.getMemoryConfig(), MemoryConfig.class);
        if (data == null) {
            data = new MemoryConfig();
        }
        data.normalize();
        return data;
    }

    private Map<String, Object> putMemory(String rawBody) {
        MemoryConfig cfg = bindBody(rawBody, MemoryConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new MemoryConfig();
        }
        // 对照 L1839-1879：七段校验（在 Normalize 与租户检查之前）
        String writeMode = cfg.getWriteMode();
        if (!writeMode.isEmpty()
                && !MemoryConfig.WRITE_MODE_EXPLICIT_ONLY.equals(writeMode)
                && !MemoryConfig.WRITE_MODE_AUTO.equals(writeMode)) {
            throw new BizException(AppError.badRequest("write_mode must be explicit_only or auto"));
        }
        if (cfg.getMaxItems() < 0 || cfg.getMaxItems() > MemoryConfig.MAX_ITEMS_CAP) {
            throw new BizException(AppError.badRequest("max_items must be between 0 and 2000"));
        }
        if (cfg.getExtractDelaySeconds() < 0
                || cfg.getExtractDelaySeconds() > MemoryConfig.MAX_EXTRACT_DELAY_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extract_delay_seconds must be between 0 and " + MemoryConfig.MAX_EXTRACT_DELAY_SECONDS));
        }
        if (cfg.getExtractMinIntervalSeconds() < 0
                || cfg.getExtractMinIntervalSeconds() > MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extract_min_interval_seconds must be between 0 and "
                            + MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS));
        }
        if (cfg.getEmbeddingModelId().length() > 64) {
            throw new BizException(AppError.badRequest("embedding_model_id is too long"));
        }
        if (cfg.getInterestThreshold() < 0
                || cfg.getInterestThreshold() > MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD) {
            // Go 原文就是 "between 1 and ..."（下界写死 1，尽管校验允许 0）——照抄
            throw new BizException(AppError.badRequest(
                    "interest_threshold must be between 1 and " + MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD));
        }
        if (cfg.getExtractInstructions().codePointCount(0, cfg.getExtractInstructions().length())
                > MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES) {
            throw new BizException(AppError.badRequest(
                    "extract_instructions must be at most " + MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES
                            + " characters"));
        }
        cfg.normalize();
        Tenant tenant = requireContextTenant();
        tenant.setMemoryConfig(MAPPER.valueToTree(cfg));
        try {
            tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update memory config", e);
        }
        return envelopeWithMessage(cfg, "Memory configuration updated successfully");
    }

    // ── 绑定与错误形态（对照 AuthController 的既有模式） ─────────────────────

    /**
     * 对照 c.ShouldBindJSON：空 body → details "EOF"；语法/类型错误 →
     * Go 风格文案（{@link GoJsonBindError}）。body 是 JSON null 字面量时
     * Go 做零值绑定不报错——Jackson readValue 返回 null，调用方按零值处理。
     */
    private static <T> T bindBody(String rawBody, Class<T> type, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams(message, "EOF");
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidParams(message, GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    private static String bindingError(String structName, String field, String tag) {
        String key = structName == null || structName.isEmpty() ? field : structName + "." + field;
        return "Key: '" + key + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }

    /** 对照 NewValidationError(message).WithDetails(err.Error()) */
    private static BizException invalidParams(String message, String details) {
        return new BizException(AppError.validation(message).withDetails(details));
    }

    /** Go strings.TrimSpace 等价（Unicode 空白） */
    private static String trimGo(String s) {
        return s == null ? "" : s.strip();
    }
}
