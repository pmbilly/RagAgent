package com.ragagent.system.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.ragagent.common.web.NonNullBody;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.system.dto.SystemDtos;
import com.ragagent.system.service.SystemAdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * /api/v1/system/admin 组：用户管理 / 平台 API Key / 系统设置 / 运行时队列 / 配额批量应用。
 *
 * <p>整组 SystemAdmin 守卫；审计埋点（promote/revoke/reset/create/api-key/quota）全部落
 * tenant_id=0 的平台行（best-effort）。</p>
 *
 * <p><b>响应形态</b>：裸资源对象（无 {@code data}/{@code success} 包装）；
 * 动作成功（重置密码 / 删除密钥 / 重置设置）→ 204；错误一律 AppError 信封
 * （请求校验用显式 message，如 {@code userId: 不能为空}）。</p>
 *
 * <p><b>runtime/queues 是 Lite 形态</b>（进程内队列，确定性翻译非降级）：
 * available=false + queues=[]；mutate/purge → 503 "Task queue is unavailable"。</p>
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

    /** promote 请求（userId 与 email 二选一，userId 优先）。 */
    public record PromoteRequest(String userId, String email) {
    }

    @PostMapping("/promote")
    public ResponseEntity<SystemDtos.UserInfoResponse> promote(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) PromoteRequest req) {
        String uid = req.userId() == null ? "" : req.userId().trim();
        String mail = req.email() == null ? "" : req.email().trim();
        if (uid.isEmpty() && mail.isEmpty()) {
            throw new BizException(AppError.badRequest("Either userId or email is required"));
        }
        User user = uid.isEmpty() ? users.getUserByEmail(mail) : users.getUserById(uid);
        if (user == null) {
            throw new BizException(AppError.notFound("User not found"));
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

    /** revoke 请求。 */
    public record RevokeRequest(@NotBlank(message = "userId: 不能为空") String userId) {
    }

    @PostMapping("/revoke")
    public ResponseEntity<SystemDtos.UserInfoResponse> revoke(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) RevokeRequest req) {
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        User user = users.revoke(req.userId(), callerId);
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

    /** 密码重置请求。 */
    public record ResetPasswordRequest(
            @NotBlank(message = "email: 不能为空") String email,
            @NotBlank(message = "newPassword: 不能为空") String newPassword) {
    }

    @PostMapping("/users/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    ResetPasswordRequest req) {
        String email = req.email().trim();
        if (!SystemAdminUserService.isValidEmail(email)) {
            throw new BizException(AppError.badRequest("Invalid password reset request"));
        }
        String policyError = users.validatePasswordPolicy(req.newPassword(), users.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.badRequest(policyError));
        }
        User user = users.getUserByEmail(email);
        if (user == null) {
            throw new BizException(AppError.notFound("User not found"));
        }
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        if (callerId.equals(user.getId())) {
            throw new BizException(AppError.badRequest("Cannot reset your own password here"));
        }
        users.adminResetPassword(user, req.newPassword());
        users.emitAdminAudit(AuditAction.SYSTEM_USER_PASSWORD_RESET, user, Map.of(
                "target_email", user.getEmail(),
                "target_username", user.getUsername(),
                "sessions_revoked", true));
        return ResponseEntity.noContent().build();
    }

    /** 创建用户请求（username 2-50 字符；password 缺省/为 null 时服务端生成）。 */
    public record CreateUserRequest(
            @NotBlank(message = "username: 不能为空") String username,
            @NotBlank(message = "email: 不能为空") String email,
            String password) {
    }

    @PostMapping("/users/create")
    public ResponseEntity<SystemDtos.CreateUserResponse> createUser(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) CreateUserRequest req) {
        if (!SystemAdminUserService.isValidEmail(req.email())
                || req.username().length() < 2 || req.username().length() > 50) {
            throw new BizException(AppError.badRequest("Invalid user creation request"));
        }
        boolean passwordPresent = req.password() != null;
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

    /** 平台 API Key 创建请求（expiresAtUnix 是 epoch 秒）。 */
    public record PlatformAPIKeyCreateRequest(
            @NotBlank(message = "name: 不能为空") String name,
            List<String> capabilities,
            Long expiresAtUnix) {
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<TenantAPIKeyResponse>> listPlatformKeys() {
        List<TenantAPIKeyResponse> response = new ArrayList<>();
        for (var key : apiKeyService.listPlatform()) {
            response.add(masked(TenantAPIKeyResponse.from(key), key.getApiKey()));
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api-keys")
    public ResponseEntity<TenantAPIKeyCreateResponse> createPlatformKey(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    PlatformAPIKeyCreateRequest req) {
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
                throw validation("expiresAtUnix must be in the future");
            }
        }
        var result = apiKeyService.create(new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                0L, APIKeyScopeType.PLATFORM, req.name().trim(), false, null, normalized, expiresAt));
        TenantAPIKeyResponse item = masked(TenantAPIKeyResponse.from(result.apiKey()), result.token());
        emitAPIKeyAudit(AuditAction.SYSTEM_API_KEY_CREATED, result.apiKey().getId(),
                result.apiKey().getCapabilities());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TenantAPIKeyCreateResponse.of(item, result.token()));
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
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<com.ragagent.system.domain.SystemSetting> getSetting(
            @PathVariable("key") String key) {
        try {
            return ResponseEntity.ok(normalizeRow(settings.get(key)));
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    /** 设置更新请求（value 必填）。 */
    public record UpdateSettingRequest(@NotNull(message = "value: 不能为空") Object value) {
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<com.ragagent.system.domain.SystemSetting> updateSetting(
            @PathVariable("key") String key,
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    UpdateSettingRequest req) {
        try {
            return ResponseEntity.ok(
                    normalizeRow(settings.update(key, MAPPER.valueToTree(req.value()))));
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    @DeleteMapping("/settings/{key}")
    public ResponseEntity<Void> resetSetting(@PathVariable("key") String key) {
        try {
            settings.reset(key);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<SystemDtos.RuntimeTasksResponse> listRuntimeTasks(
            @PathVariable("queue") String queue,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "pageSize", required = false) String pageSize) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw new BizException(AppError.badRequest("Unknown task queue"));
        }
        if (state == null || !List.of("pending", "active", "scheduled", "retry", "archived", "completed")
                .contains(state)) {
            throw new BizException(AppError.badRequest("Unknown task state"));
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
    public ResponseEntity<Void> mutateRuntimeTask(@PathVariable("queue") String queue,
                                               @PathVariable("taskId") String taskId,
                                               @PathVariable("action") String action) {
        if (!KNOWN_QUEUES.contains(queue) || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid queue or task ID"));
        }
        // Lite：进程内队列不支持任务变更 → 503
        throw new BizException(AppError.serviceUnavailable("Task queue is unavailable"));
    }

    @DeleteMapping("/runtime/queues/{queue}/archived")
    public ResponseEntity<Void> purgeArchived(@PathVariable("queue") String queue) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw new BizException(AppError.badRequest("Invalid queue"));
        }
        throw new BizException(AppError.serviceUnavailable("Task queue is unavailable"));
    }

    // ── 配额批量应用 ──────────────────────────────────────────────────────

    /** 配额批量应用结果（affected = 被重置的租户数）。 */
    public record StorageQuotaApplyResponse(int affected, long quotaBytes, long quotaGb) {
    }

    @PostMapping("/tenants/apply-default-storage-quota")
    public ResponseEntity<StorageQuotaApplyResponse> applyDefaultStorageQuota() {
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
        return ResponseEntity.ok(new StorageQuotaApplyResponse(affected, quotaBytes, gb));
    }
}
