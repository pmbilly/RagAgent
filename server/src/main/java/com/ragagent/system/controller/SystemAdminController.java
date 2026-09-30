package com.ragagent.system.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeType;
import com.ragagent.auth.apikey.domain.TenantAPIKeyCreateResponse;
import com.ragagent.auth.apikey.domain.TenantAPIKeyResponse;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.system.dto.SystemDtos;
import com.ragagent.system.service.SystemAdminUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/system/admin 组（对照 Go RegisterSystemAdminRoutes + SystemHandler 的
 * P0 用户管理 / 平台 API Key / P1 系统设置 / 运行时队列 / 配额批量应用）。
 *
 * <p>整组 SystemAdmin 守卫（{@code RbacInterceptor.addSystemAdminRule}）；
 * 审计埋点（promote/revoke/reset/create/api-key/quota）全部落 tenant_id=0 的
 * 平台行（best-effort，与 Go 的 {@code _ = h.auditSvc.Log(...)} 一致）。</p>
 *
 * <p><b>响应形态（golden 钉住，三种并存）</b>：</p>
 * <ul>
 *   <li>纯字符串错误 {@code {"error":"..."}}（c.JSON 直写，400/404/409/500）；
 *       binding 失败的 message 是 go-playground validator / Go json 解析器原文
 *       （如 {@code Key: 'RevokeSystemAdminRequest.UserID' ... 'required' tag}）；</li>
 *   <li>平台 API Key 的校验错误走 <b>AppError 信封</b>（c.Error → 1010/1000/1003，
 *       message 固定、原文在 details）；</li>
 *   <li>settings 读写返回<b>裸行</b>（无 {"data":...} 包装，axios 拦截器约定）；
 *       api-keys 列表是 {"data":[...],"success":true}（gin.H 字母序：data&lt;success）。</li>
 * </ul>
 *
 * <p><b>runtime/queues 是 Lite 形态</b>（对照 Go noopTaskInspector，**确定性翻译**非降级）：
 * GetRuntimeQueues → available=false + queues=[]（asynq 深度在进程内队列下不存在）；
 * ListRuntimeTasks → {@code {available:false,tasks:[],page_size:N,has_more:false}}；
 * mutate/purge → 503 "Task queue is unavailable"。错误分支（未知队列/state/action）
 * 与模式无关，逐字对照 Go。</p>
 */
@RestController
@RequestMapping("/api/v1/system/admin")
public class SystemAdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SystemAdminUserService users;
    private final com.ragagent.system.service.SystemSettingService settings;
    private final TenantAPIKeyService apiKeyService;
    private final TenantMapper tenantMapper;
    private final AuditLogService auditService;

    public SystemAdminController(SystemAdminUserService users,
                                 com.ragagent.system.service.SystemSettingService settings,
                                 TenantAPIKeyService apiKeyService,
                                 TenantMapper tenantMapper,
                                 AuditLogService auditService) {
        this.users = users;
        this.settings = settings;
        this.apiKeyService = apiKeyService;
        this.tenantMapper = tenantMapper;
        this.auditService = auditService;
    }

    // ── P0：系统管理员升降级 ──────────────────────────────────────────────

    /** 对照 PromoteUserToSystemAdminRequest（user_id 与 email 二选一，user_id 优先）。 */
    public record PromoteRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
            @com.fasterxml.jackson.annotation.JsonProperty("email") String email) {
    }

    @PostMapping("/promote")
    public ResponseEntity<?> promote(@RequestBody(required = false) String rawBody) {
        String userId = null;
        String email = null;
        if (rawBody == null || rawBody.isBlank()) {
            // Go 的 ShouldBindJSON 空 body → EOF
            throw PlainErrorException.badRequest("Invalid request: EOF");
        }
        try {
            PromoteRequest req = MAPPER.readValue(rawBody, PromoteRequest.class);
            userId = req.userId();
            email = req.email();
        } catch (Exception e) {
            throw PlainErrorException.badRequest(
                    "Invalid request: " + GoJsonBindError.message(rawBody, e.getMessage()));
        }
        String uid = userId == null ? "" : userId.trim();
        String mail = email == null ? "" : email.trim();
        if (uid.isEmpty() && mail.isEmpty()) {
            throw PlainErrorException.badRequest("Either user_id or email is required");
        }
        User user = uid.isEmpty() ? users.getUserByEmail(mail) : users.getUserById(uid);
        if (user == null) {
            throw PlainErrorException.notFound("User not found");
        }
        if (user.isIsSystemAdmin()) {
            users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_PROMOTED, user, Map.of(
                    "target_email", user.getEmail(),
                    "target_username", user.getUsername(),
                    "idempotent", true));
            return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
        }
        User promoted = users.promote(user);
        users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_PROMOTED, promoted, Map.of(
                "target_email", promoted.getEmail(),
                "target_username", promoted.getUsername(),
                "idempotent", false));
        return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(promoted));
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@RequestBody(required = false) String rawBody) {
        String userId = null;
        if (rawBody == null || rawBody.isBlank()) {
            userId = null;
        } else {
            try {
                JsonNode node = MAPPER.readTree(rawBody);
                if (node.isObject() && node.has("user_id") && !node.get("user_id").isNull()) {
                    userId = node.get("user_id").asText();
                }
            } catch (Exception e) {
                throw PlainErrorException.badRequest(
                        "Invalid request: " + GoJsonBindError.message(rawBody, e.getMessage()));
            }
        }
        if (userId == null || userId.isEmpty()) {
            // Go binding:"required" 的 validator 原文（空 body 同样命中 required）
            throw PlainErrorException.badRequest("Invalid request: "
                    + "Key: 'RevokeSystemAdminRequest.UserID' Error:Field validation for 'UserID' "
                    + "failed on the 'required' tag");
        }
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        User user = users.revoke(userId, callerId);
        if (!user.isIsSystemAdmin()) {
            // ErrUserNotSystemAdmin → 幂等 200（changed=false 审计）
            users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_REVOKED, user, Map.of(
                    "target_email", user.getEmail(),
                    "target_username", user.getUsername(),
                    "changed", false));
            return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
        }
        users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_REVOKED, user, Map.of(
                "target_email", user.getEmail(),
                "target_username", user.getUsername(),
                "changed", true));
        return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
    }

    @GetMapping("/list")
    public ResponseEntity<SystemDtos.SystemAdminListResponse> listAdmins(
            @RequestParam(name = "offset", required = false) String offset,
            @RequestParam(name = "limit", required = false) String limit) {
        // best-effort 分页解析：非法值回落默认（不 400）
        int off = 0;
        int lim = 50;
        if (offset != null && !offset.isEmpty()) {
            try {
                int n = Integer.parseInt(offset);
                if (n >= 0) {
                    off = n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (limit != null && !limit.isEmpty()) {
            try {
                int n = Integer.parseInt(limit);
                if (n > 0) {
                    lim = n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (lim > 200) {
            lim = 200;
        }
        SystemAdminUserService.AdminPage page = users.listSystemAdmins(off, lim);
        List<SystemDtos.UserInfoResponse> infos = new ArrayList<>();
        for (User u : page.users()) {
            infos.add(SystemDtos.UserInfoResponse.from(u));
        }
        return ResponseEntity.ok(new SystemDtos.SystemAdminListResponse(page.total(), infos));
    }

    // ── 用户管理 ──────────────────────────────────────────────────────────

    public record ResetPasswordRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("email") String email,
            @com.fasterxml.jackson.annotation.JsonProperty("new_password") String newPassword) {
    }

    @PostMapping("/users/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody(required = false) String rawBody) {
        ResetPasswordRequest req = null;
        try {
            req = rawBody == null ? null : MAPPER.readValue(rawBody, ResetPasswordRequest.class);
        } catch (Exception ignored) {
            // fall through → generic 400（Go 的 bind 错误统一这条文案）
        }
        if (req == null || req.email() == null || req.email().isEmpty()
                || req.newPassword() == null || req.newPassword().isEmpty()
                || !SystemAdminUserService.isValidEmail(req.email())) {
            throw PlainErrorException.badRequest("Invalid password reset request");
        }
        String email = req.email().trim();
        String policyError = users.validatePasswordPolicy(req.newPassword(), users.complexPasswordEnabled());
        if (policyError != null) {
            throw PlainErrorException.badRequest(policyError);
        }
        User user = users.getUserByEmail(email);
        if (user == null) {
            throw PlainErrorException.notFound("User not found");
        }
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        if (callerId.equals(user.getId())) {
            throw PlainErrorException.badRequest("Cannot reset your own password here");
        }
        users.adminResetPassword(user, req.newPassword());
        users.emitAdminAudit(AuditAction.SYSTEM_USER_PASSWORD_RESET, user, Map.of(
                "target_email", user.getEmail(),
                "target_username", user.getUsername(),
                "sessions_revoked", true));
        return ResponseEntity.ok(orderedMessage("Password reset successfully"));
    }

    /** {"message":"..."}——单键 map，字母序无歧义。 */
    private static Map<String, Object> orderedMessage(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return body;
    }

    public record CreateUserRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("username") String username,
            @com.fasterxml.jackson.annotation.JsonProperty("email") String email,
            @com.fasterxml.jackson.annotation.JsonProperty("password") String password) {
    }

    @PostMapping("/users/create")
    public ResponseEntity<?> createUser(@RequestBody(required = false) String rawBody) {
        CreateUserRequest req = null;
        boolean malformed = false;
        try {
            req = rawBody == null ? null : MAPPER.readValue(rawBody, CreateUserRequest.class);
        } catch (Exception ignored) {
            malformed = true; // Go 的 bind 错误（含 EOF/解析错误）统一这条文案
        }
        if (malformed || req == null
                || req.username() == null || req.username().isEmpty()
                || req.email() == null || req.email().isEmpty()
                || !SystemAdminUserService.isValidEmail(req.email())
                || req.username().length() < 2 || req.username().length() > 50) {
            throw PlainErrorException.badRequest("Invalid user creation request");
        }
        boolean passwordPresent = false;
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            passwordPresent = node.isObject() && node.has("password") && !node.get("password").isNull();
        } catch (Exception ignored) {
            // malformed 已在上面拦截
        }
        SystemAdminUserService.CreateResult result = users.adminCreateUser(
                req.username(), req.email(), req.password(), passwordPresent,
                users.resolveDefaultTenantMode());
        if (result instanceof SystemAdminUserService.CreateResult.Idempotent idem) {
            users.emitAdminAudit(AuditAction.SYSTEM_USER_CREATED, idem.user(), Map.of(
                    "target_email", idem.user().getEmail(),
                    "target_username", idem.user().getUsername(),
                    "password_generated", false,
                    "idempotent", true));
            return ResponseEntity.ok(new SystemDtos.CreateUserResponse(
                    SystemDtos.UserInfoResponse.from(idem.user()), null));
        }
        var created = (SystemAdminUserService.CreateResult.Created) result;
        users.emitAdminAudit(AuditAction.SYSTEM_USER_CREATED, created.user(), Map.of(
                "target_email", created.user().getEmail(),
                "target_username", created.user().getUsername(),
                "password_generated", !created.generatedPassword().isEmpty(),
                "idempotent", false));
        return ResponseEntity.status(HttpStatus.CREATED).body(new SystemDtos.CreateUserResponse(
                SystemDtos.UserInfoResponse.from(created.user()),
                created.generatedPassword().isEmpty() ? null : created.generatedPassword()));
    }

    // ── 平台 API Key ──────────────────────────────────────────────────────

    /** 对照 platformAPIKeyCreateRequest：expires_at_unix 是 epoch 秒（不是 RFC3339）。 */
    public record PlatformAPIKeyCreateRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
            @com.fasterxml.jackson.annotation.JsonProperty("capabilities") List<String> capabilities,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_at_unix") Long expiresAtUnix) {
    }

    @GetMapping("/api-keys")
    public ResponseEntity<Map<String, Object>> listPlatformKeys() {
        List<TenantAPIKeyResponse> response = new ArrayList<>();
        for (var key : apiKeyService.listPlatform()) {
            response.add(masked(TenantAPIKeyResponse.from(key), key.getApiKey()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", response);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api-keys")
    public ResponseEntity<?> createPlatformKey(@RequestBody(required = false) String rawBody) {
        PlatformAPIKeyCreateRequest req;
        try {
            req = rawBody == null ? null : MAPPER.readValue(rawBody, PlatformAPIKeyCreateRequest.class);
        } catch (Exception e) {
            // Go：NewValidationError("Invalid request data").WithDetails(err.Error())
            throw new BizException(AppError.validation("Invalid request data")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (req == null || req.name() == null || req.name().trim().isEmpty()) {
            throw validation("name is required");
        }
        List<String> capabilities = req.capabilities() == null ? List.of() : req.capabilities();
        List<String> normalized = APIKeyCapability.normalizeAll(capabilities);
        if (normalized.isEmpty() || normalized.size() != capabilities.size()) {
            throw validation("valid capabilities are required");
        }
        java.time.OffsetDateTime expiresAt = null;
        if (req.expiresAtUnix() != null) {
            expiresAt = java.time.Instant.ofEpochSecond(req.expiresAtUnix())
                    .atOffset(java.time.ZoneOffset.UTC);
            if (!expiresAt.toInstant().isAfter(java.time.Instant.now())) {
                throw validation("expires_at_unix must be in the future");
            }
        }
        var result = apiKeyService.create(new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                0L, APIKeyScopeType.PLATFORM, req.name().trim(), false, null, normalized, expiresAt));
        TenantAPIKeyResponse item = masked(TenantAPIKeyResponse.from(result.apiKey()), result.token());
        emitAPIKeyAudit(AuditAction.SYSTEM_API_KEY_CREATED, result.apiKey().getId(),
                result.apiKey().getCapabilities());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", TenantAPIKeyCreateResponse.of(item, result.token()));
        body.put("success", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<?> deletePlatformKey(@PathVariable("keyId") String keyId) {
        long id;
        try {
            id = Long.parseLong(keyId);
        } catch (NumberFormatException e) {
            id = 0;
        }
        if (id == 0) {
            throw new BizException(AppError.badRequest("Invalid API key ID"));
        }
        try {
            apiKeyService.revokePlatform(id);
        } catch (RuntimeException e) {
            throw new BizException(AppError.notFound("Platform API key not found"));
        }
        emitAPIKeyAudit(AuditAction.SYSTEM_API_KEY_REVOKED, id, List.of());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 对照 maskManagedAPIKey：<=12 位 → "***"；否则 first7 + "..." + last4。 */
    private static TenantAPIKeyResponse masked(TenantAPIKeyResponse item, String token) {
        String t = token == null ? "" : token.trim();
        String masked = t.length() <= 12 ? "***" : t.substring(0, 7) + "..." + t.substring(t.length() - 4);
        return new TenantAPIKeyResponse(item.id(), item.scopeType(), item.name(), masked,
                item.fullAccess(), item.knowledgeBaseIds(), item.capabilities(),
                item.lastUsedAt(), item.expiresAt(), item.createdAt());
    }

    /** 对照 emitAPIKeyAudit（details: scope_type/capabilities；target_type=api_key）。 */
    private void emitAPIKeyAudit(String action, long keyId, List<String> capabilities) {
        var details = new LinkedHashMap<String, Object>();
        details.put("scope_type", APIKeyScopeType.PLATFORM);
        details.put("capabilities", capabilities);
        AuditLog entry = new AuditLog();
        entry.setTenantId(0L);
        entry.setActorUserId(TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId());
        entry.setActorRole(SystemAdminUserService.systemAuditActorRole());
        entry.setAction(action);
        entry.setTargetType("api_key");
        entry.setTargetId(String.valueOf(keyId));
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(MAPPER.valueToTree(details));
        auditService.logBestEffort(entry);
    }

    /** Go NewValidationError → code 1010（信封形态，message 固定 "Invalid request data"？——
     *  golden 实测 message=原文，details=null：validation 工厂即原文形态）。 */
    private static BizException validation(String message) {
        return new BizException(AppError.validation(message));
    }

    // ── P1：系统设置 ──────────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<List<Object>> listSettings() {
        List<Object> out = new ArrayList<>();
        for (var row : settings.list()) {
            out.add(normalizeRow(row));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/settings/{key}")
    public ResponseEntity<?> getSetting(@PathVariable("key") String key) {
        try {
            return ResponseEntity.ok(normalizeRow(settings.get(key)));
        } catch (IllegalArgumentException e) {
            throw PlainErrorException.badRequest(e.getMessage());
        }
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<?> updateSetting(@PathVariable("key") String key,
                                           @RequestBody(required = false) String rawBody) {
        JsonNode req;
        if (rawBody == null || rawBody.isBlank()) {
            throw PlainErrorException.badRequest("Invalid request: EOF");
        }
        try {
            req = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw PlainErrorException.badRequest(
                    "Invalid request: " + GoJsonBindError.message(rawBody, e.getMessage()));
        }
        if (!req.isObject() || !req.has("value") || req.get("value").isNull()) {
            throw PlainErrorException.badRequest("value is required");
        }
        try {
            return ResponseEntity.ok(normalizeRow(settings.update(key, req.get("value"))));
        } catch (IllegalArgumentException e) {
            throw PlainErrorException.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/settings/{key}")
    public ResponseEntity<?> resetSetting(@PathVariable("key") String key) {
        try {
            settings.reset(key);
        } catch (IllegalArgumentException e) {
            throw PlainErrorException.badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 虚拟行的 id 归一为 0（Go uint64 零值输出 0，不是 null）。 */
    private static com.ragagent.system.domain.SystemSetting normalizeRow(
            com.ragagent.system.domain.SystemSetting row) {
        if (row.getId() == null) {
            row.setId(0L);
        }
        return row;
    }

    // ── 运行时队列（Lite，确定性翻译） ────────────────────────────────────

    /** Go QueueDefinitions 的队列名（isKnownRuntimeQueue 的判定集）。 */
    private static final List<String> KNOWN_QUEUES = List.of(
            "default", "chat_attachment", "postprocess", "summary", "multimodal",
            "graph", "question", "memory", "sync", "low", "wiki");

    @GetMapping("/runtime/queues")
    public ResponseEntity<SystemDtos.RuntimeQueuesResponse> runtimeQueues() {
        // 对照 ResolveWorkerPoolConcurrency：每池 concurrency = setting/env/默认 的正数折叠
        int core = positive("asynq.core_concurrency", "WEKNORA_ASYNQ_CORE_CONCURRENCY", 8);
        int postProcess = positive("asynq.postprocess_concurrency",
                "WEKNORA_ASYNQ_POSTPROCESS_CONCURRENCY", 2);
        int enrichment = positive("asynq.enrichment_concurrency",
                "WEKNORA_ASYNQ_ENRICHMENT_CONCURRENCY", 12);
        int maintenance = positive("asynq.maintenance_concurrency",
                "WEKNORA_ASYNQ_MAINTENANCE_CONCURRENCY", 4);
        int shared = positive("asynq.shared_concurrency", "WEKNORA_ASYNQ_SHARED_CONCURRENCY", 6);
        int wiki = positive("asynq.wiki_concurrency", "WEKNORA_WIKI_ASYNQ_CONCURRENCY", 8);
        int upstreamTotal = core + postProcess + enrichment + maintenance + shared;
        // queue_count = 各池的 QueueDefinitions 条目数 / 共享池 = SharedWeight>0 的条目数
        List<SystemDtos.RuntimeWorkerPool> pools = List.of(
                new SystemDtos.RuntimeWorkerPool("core", core, 2, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("postprocess", postProcess, 1, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("enrichment", enrichment, 5, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("maintenance", maintenance, 2, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("shared", shared, 7, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("wiki", wiki, 1, 0, 0, 0, 0));
        // Lite：QueueStats → (nil,false,nil) → available=false + queues=[]；
        // 本地限流器 RuntimeStats 恒可用（available=true）且进程内无已获取信号量 → []
        return ResponseEntity.ok(new SystemDtos.RuntimeQueuesResponse(
                false, upstreamTotal, upstreamTotal, wiki, pools,
                List.of(), true, List.of(), java.time.Instant.now().getEpochSecond()));
    }

    /** 对照 ResolveWorkerPoolConcurrency 的 positive 折叠（<1 → fallback）。 */
    private int positive(String key, String env, int fallback) {
        long v = settings.getInt(key, env, fallback);
        return v < 1 ? fallback : (int) v;
    }

    @GetMapping("/runtime/queues/{queue}/tasks")
    public ResponseEntity<?> listRuntimeTasks(@PathVariable("queue") String queue,
                                              @RequestParam(name = "state", required = false) String state,
                                              @RequestParam(name = "page_size", required = false) String pageSize) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw PlainErrorException.badRequest("Unknown task queue");
        }
        if (state == null || !List.of("pending", "active", "scheduled", "retry", "archived", "completed")
                .contains(state)) {
            throw PlainErrorException.badRequest("Unknown task state");
        }
        // 对照 runtimeTaskPageSize：默认 20；<1 → 20；>100 → 100；非法 → 20
        int size;
        try {
            size = pageSize == null ? 20 : Integer.parseInt(pageSize);
        } catch (NumberFormatException e) {
            size = 20;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        // Lite：noopTaskInspector 不实现 RuntimeTaskInspector → available=false 空页
        return ResponseEntity.ok(new SystemDtos.RuntimeTasksResponse(false, List.of(), size, false, null));
    }

    @PostMapping("/runtime/queues/{queue}/tasks/{taskId}/actions/{action}")
    public ResponseEntity<?> mutateRuntimeTask(@PathVariable("queue") String queue,
                                               @PathVariable("taskId") String taskId,
                                               @PathVariable("action") String action) {
        if (!KNOWN_QUEUES.contains(queue) || taskId.isEmpty()) {
            throw PlainErrorException.badRequest("Invalid queue or task ID");
        }
        // Lite：类型断言失败 → 503（Go mutateRuntimeTask 的 !supported 分支）
        throw new PlainErrorException(503, "Task queue is unavailable");
    }

    @DeleteMapping("/runtime/queues/{queue}/archived")
    public ResponseEntity<?> purgeArchived(@PathVariable("queue") String queue) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw PlainErrorException.badRequest("Invalid queue");
        }
        throw new PlainErrorException(503, "Task queue is unavailable");
    }

    // ── 配额批量应用 ──────────────────────────────────────────────────────

    @PostMapping("/tenants/apply-default-storage-quota")
    public ResponseEntity<?> applyDefaultStorageQuota() {
        long gb = settings.getInt("tenant.default_storage_quota_gb",
                "WEKNORA_TENANT_DEFAULT_STORAGE_QUOTA_GB", 10);
        if (gb <= 0) {
            gb = 10;
        }
        long quotaBytes = gb * 1024 * 1024 * 1024;
        int affected = tenantMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Tenant>()
                        .set(Tenant::getStorageQuota, quotaBytes));
        var details = new LinkedHashMap<String, Object>();
        details.put("quota_bytes", quotaBytes);
        details.put("quota_gb", gb);
        details.put("affected", affected);
        AuditLog entry = new AuditLog();
        entry.setTenantId(0L);
        entry.setActorUserId(TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId());
        entry.setActorRole(SystemAdminUserService.systemAuditActorRole());
        entry.setAction(AuditAction.SYSTEM_SETTING_CHANGED);
        entry.setTargetType("tenant_storage_quota");
        entry.setTargetId("all");
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(MAPPER.valueToTree(details));
        auditService.logBestEffort(entry);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("affected", affected);
        body.put("quota_bytes", quotaBytes);
        body.put("quota_gb", gb);
        return ResponseEntity.ok(body);
    }
}
