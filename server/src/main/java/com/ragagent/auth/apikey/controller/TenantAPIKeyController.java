package com.ragagent.auth.apikey.controller;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.domain.TenantAPIKeyCreateResponse;
import com.ragagent.auth.apikey.domain.TenantAPIKeyRequest;
import com.ragagent.auth.apikey.domain.TenantAPIKeyResponse;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.apikey.service.TenantAPIKeyValidator;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.knowledge.KnowledgeBaseFacts;
import com.ragagent.common.knowledge.KnowledgeBaseGateway;
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
 * 租户 API Key 管理端点（对照 Go internal/handler/tenant.go 的
 * {@code ListAPIKeys} L660-677 / {@code CreateAPIKey} L679-723 /
 * {@code UpdateAPIKey} L725-763 / {@code DeleteAPIKey} L765-782）。
 *
 * <p>路由（对照 Go router/routes_auth_tenant.go L97-100，均由 {@code WebConfig}
 * 注册 RBAC 规则）：</p>
 * <ul>
 *   <li>{@code GET    /api/v1/tenants/{id}/api-keys} — Owner+</li>
 *   <li>{@code POST   /api/v1/tenants/{id}/api-keys} — Owner+</li>
 *   <li>{@code PUT    /api/v1/tenants/{id}/api-keys/{key_id}} — Owner+</li>
 *   <li>{@code DELETE /api/v1/tenants/{id}/api-keys/{key_id}} — Owner+</li>
 * </ul>
 *
 * <p>另有两条 Go 路由守卫（本模块不注册，见任务报告）：</p>
 * <ul>
 *   <li>{@code g.PathTenantMatch()}：URL 的 {@code :id} 必须等于调用者的活动租户
 *       （跨空间超管除外）。Java 侧需在 {@code WebConfig} 给
 *       {@code /api/v1/tenants/&#42;/api-keys/&#42;&#42;} 加一条租户匹配校验；</li>
 *   <li><b>API Key 主体对这四个端点 default-deny</b>——它们没走 {@code apiKeyRoute}
 *       包装，所以门禁查不到策略即 403。这正是 Go 测试
 *       {@code TestAPIKeyGateDeniesTenantKeyManagementPaths} 钉住的契约
 *       （否则一把 Key 能给自己扩权）。Java 侧靠"不登记策略"天然满足。</li>
 * </ul>
 *
 * <h2>响应形态（逐字段对照 Go 的 gin.H）</h2>
 * <ul>
 *   <li>三个成功响应都是 {@code {"data": ..., "success": true}}（data &lt; success，
 *       字母序与 Go 的 map 输出一致）；</li>
 *   <li>{@code DELETE} 是 {@code {"success": true}}——<b>没有 data 键</b>；</li>
 *   <li>创建返回 <b>201</b>（不是 200），且 data 里带一次性明文 {@code token}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants/{id}/api-keys")
public class TenantAPIKeyController {

    private static final Logger log = LoggerFactory.getLogger(TenantAPIKeyController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TenantAPIKeyService apiKeyService;
    private final KnowledgeBaseGateway knowledgeBaseGateway;

    public TenantAPIKeyController(TenantAPIKeyService apiKeyService, KnowledgeBaseGateway knowledgeBaseGateway) {
        this.apiKeyService = apiKeyService;
        this.knowledgeBaseGateway = knowledgeBaseGateway;
    }

    // ── 列表 ──

    /** 对照 {@code ListAPIKeys}：返回**裸数组**（空列表输出 {@code []}，不是 null）。 */
    @GetMapping
    public ResponseEntity<List<TenantAPIKeyResponse>> list(@PathVariable("id") String rawId) {
        long tenantId = parseWorkspaceIdOrBadRequest(rawId);
        List<TenantAPIKeyResponse> data = new ArrayList<>();
        for (TenantAPIKey key : apiKeyService.listByTenant(tenantId)) {
            data.add(TenantAPIKeyResponse.from(key));
        }
        return ResponseEntity.ok(data);
    }

    // ── 创建 ──

    /**
     * 对照 {@code CreateAPIKey}：201 + {@code data.token}（明文只此一次）。
     *
     * <p>校验顺序照抄 Go：workspace ID → JSON 绑定 → {@code validateTenantAPIKeyRequest}
     * → {@code expiresAtUnix} 必须在未来 → 服务层。</p>
     */
    @PostMapping
    public ResponseEntity<TenantAPIKeyCreateResponse> create(
            @PathVariable("id") String rawId,
            @RequestBody(required = false) String rawBody) {
        long tenantId = parseWorkspaceIdOrBadRequest(rawId);
        TenantAPIKeyRequest req = parseBody(rawBody);
        TenantAPIKeyValidator.validate(req, tenantId, this::lookupKbTenantId);

        OffsetDateTime expiresAt = null;
        if (req.expiresAtUnix() != null) {
            OffsetDateTime candidate = Instant.ofEpochSecond(req.expiresAtUnix()).atOffset(ZoneOffset.UTC);
            if (!candidate.toInstant().isAfter(Instant.now())) {
                throw new BizException(AppError.validation("expiresAtUnix must be in the future"));
            }
            expiresAt = candidate;
        }

        TenantAPIKeyService.CreateResult result;
        try {
            result = apiKeyService.create(new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                    tenantId, null, req.name(), req.fullAccess(),
                    req.knowledgeBaseIds(), req.capabilities(), expiresAt));
        } catch (RuntimeException e) {
            log.warn("[apikey] create failed for tenant={}: {}", tenantId, e.toString());
            throw new BizException(AppError.internal("Failed to create API key").withDetails(e.getMessage()));
        }

        return ResponseEntity.status(201).body(TenantAPIKeyCreateResponse.of(
                TenantAPIKeyResponse.from(result.apiKey()), result.token()));
    }

    // ── 更新 ──

    /**
     * 对照 {@code UpdateAPIKey}。
     *
     * <p><b>两个刻意的差异，别"顺手修正"</b>：</p>
     * <ol>
     *   <li>Go **没有** {@code expiresAtUnix 必须在未来} 的校验（只有 Create 有）；
     *       而且过期时间**不转 UTC 之外的加工**就交给服务层
     *       （服务层再统一 UTC 化，见 {@code TenantAPIKeyService.update}）；</li>
     *   <li>服务层任何错误都被映射成 <b>404 {@code API key not found}</b>——
     *       包括"name is required"这类校验错。这是 Go 的写法
     *       （{@code if err != nil { c.Error(errors.NewNotFoundError("API key not found")) }}），
     *       已如实照抄。</li>
     * </ol>
     */
    @PutMapping("/{key_id}")
    public ResponseEntity<TenantAPIKeyResponse> update(@PathVariable("id") String rawId,
                                                      @PathVariable("key_id") String rawKeyId,
                                                      @RequestBody(required = false) String rawBody) {
        long tenantId = parseWorkspaceIdOrBadRequest(rawId);
        long keyId = parseKeyIdOrBadRequest(rawKeyId);
        TenantAPIKeyRequest req = parseBody(rawBody);
        TenantAPIKeyValidator.validate(req, tenantId, this::lookupKbTenantId);

        OffsetDateTime expiresAt = null;
        if (req.expiresAtUnix() != null) {
            expiresAt = Instant.ofEpochSecond(req.expiresAtUnix()).atOffset(ZoneOffset.UTC);
        }

        TenantAPIKey updated;
        try {
            updated = apiKeyService.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                    tenantId, keyId, req.name(), req.fullAccess(),
                    req.knowledgeBaseIds(), req.capabilities(), expiresAt));
        } catch (RuntimeException e) {
            // 对照 Go：任何服务层错误都落到 404（含 TenantAPIKeyNotFoundException）
            throw new BizException(AppError.notFound("API key not found"));
        }
        return ResponseEntity.ok(TenantAPIKeyResponse.from(updated));
    }

    // ── 删除（软撤销） ──

    /** 对照 {@code DeleteAPIKey}：响应体只有 {@code {"success": true}}。 */
    @DeleteMapping("/{key_id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String rawId,
                                       @PathVariable("key_id") String rawKeyId) {
        long tenantId = parseWorkspaceIdOrBadRequest(rawId);
        long keyId = parseKeyIdOrBadRequest(rawKeyId);
        try {
            apiKeyService.revoke(tenantId, keyId);
        } catch (TenantAPIKeyNotFoundException e) {
            throw new BizException(AppError.notFound("API key not found"));
        }
        return ResponseEntity.noContent().build();
    }

    // ── 辅助 ──

    /**
     * 对照 {@code strconv.ParseUint(c.Param("id"), 10, 64)}：
     * 只接受十进制无符号整数（Go 的 ParseUint **不接受**正负号），溢出也算失败。
     */
    private static long parseWorkspaceIdOrBadRequest(String raw) {
        Long parsed = parseUint(raw);
        if (parsed == null) {
            throw new BizException(AppError.badRequest("Invalid workspace ID"));
        }
        return parsed;
    }

    /** 对照 Update/Delete 的 {@code key_id} 解析：失败**或为 0** 都是 400。 */
    private static long parseKeyIdOrBadRequest(String raw) {
        Long parsed = parseUint(raw);
        if (parsed == null || parsed == 0L) {
            throw new BizException(AppError.badRequest("Invalid API key ID"));
        }
        return parsed;
    }

    private static Long parseUint(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            return null; // 溢出（Go 的 ParseUint 同样报错）
        }
    }

    /**
     * 对照 ShouldBindJSON：空 body → details {@code "EOF"}；非法 JSON → 解析器消息
     * （与登录端点用的同一套处理，见 {@code AuthController.parseBody}）。
     */
    private static TenantAPIKeyRequest parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidRequestData("EOF");
        }
        try {
            TenantAPIKeyRequest req = MAPPER.readValue(rawBody, TenantAPIKeyRequest.class);
            return req == null ? new TenantAPIKeyRequest(null, false, null, null, null) : req;
        } catch (Exception e) {
            throw invalidRequestData(e.getMessage());
        }
    }

    private static BizException invalidRequestData(String details) {
        return new BizException(AppError.validation("Invalid request data").withDetails(details));
    }

    /**
     * KB 归属查询（对照 Go 传给 {@code validateTenantAPIKeyRequest} 的
     * {@code kbService.GetKnowledgeBaseByID}）。
     *
     * <p><b>刻意不按租户过滤</b>：校验需要区分"查不到"（400）与
     * "存在但属于别的空间"（403），所以查询只按 id + 未软删，
     * 租户比较交给 {@link TenantAPIKeyValidator}。</p>
     */
    private Long lookupKbTenantId(String knowledgeBaseId) {
        KnowledgeBaseFacts kb = knowledgeBaseGateway.findFacts(knowledgeBaseId);
        return kb == null ? null : kb.tenantId();
    }
}
