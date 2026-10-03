package com.ragagent.storage.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.common.storage.StorageAllowList;
import com.ragagent.storage.dto.StorageBackendResponse;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.storage.service.StorageBackendService;
import org.springframework.http.HttpStatus;
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
 * 存储后端管理面：9 条路由；读 Viewer+ / 写与测试 Admin+。
 *
 * <p>**全 AppError 信封**（与 wsp/vs 的纯字符串 404 刻意不同）：404 = code 1003。
 * PUT **先绑 body 再进 service**（与 wsp 的 ownership-first 相反——
 * 未知 id + 坏 body 落 400 EOF）。TestRaw/TestByID 的连通失败是 **200** +
 * {@code {"success":false,"error":清洗后文案}}（storageTestErrorMessage：AppError 取
 * message、其余经 SanitizeStorageConnectivityError 映射）；校验/SSRF 失败走信封
 * （1000/1010）。storage handler **不校验租户缺失**（tenantId=0 时 list 为空、
 * get 404）。</p>
 */
@RestController
@RequestMapping("/api/v1/storage-backends")
public class StorageBackendController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackendService service;
    private final StorageAllowList allowList;

    public StorageBackendController(StorageBackendService service, StorageAllowList allowList) {
        this.service = service;
        this.allowList = allowList;
    }

    /** 请求体：name/provider required */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StorageBackendRequest(
            String name,
            String provider,
            StorageConfig config,
            String status) {}

    @GetMapping("/types")
    public ResponseEntity<?> types() {
        return ResponseEntity.ok(allowList.allowedList());
    }

    @PostMapping("/test")
    public ResponseEntity<?> testRaw(@RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        StorageBackendRequest req = bind(rawBody);
        StorageBackend backend = carrier(tenantId, req);
        try {
            service.validate(backend);
        } catch (BizException e) {
            throw e; // c.Error → 信封（code 1010）
        }
        try {
            service.test(backend);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(failureBody(e));
        }
        return ResponseEntity.ok(connectedBody(true));
    }

    /** 创建后端：201 + 裸资源。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        StorageBackendRequest req = bind(rawBody);
        StorageBackend backend = carrier(tenantId, req);
        service.create(backend);
        return ResponseEntity.status(HttpStatus.CREATED).body(response(backend));
    }

    /** 列表 = {items, defaultStorageBackendId}（游标外的附加字段形态）。 */
    @GetMapping
    public ResponseEntity<?> list() {
        long tenantId = tenantId();
        List<StorageBackendResponse> result = new ArrayList<>();
        for (StorageBackend b : service.listBackends(tenantId)) {
            result.add(response(b));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result);
        body.put("defaultStorageBackendId",
                tenantId == 0 ? null : service.tenantDefaultBackendId(tenantId));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        long tenantId = tenantId();
        StorageBackend backend = getOwned(tenantId, id);
        return ResponseEntity.ok(response(backend));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        // Go：先 ShouldBindJSON 再进 service（未知 id + 坏 body → 400，非 404）
        StorageBackendRequest req = bind(rawBody);
        StorageBackend backend = carrier(tenantId, req);
        backend.setId(id);
        service.update(backend);
        StorageBackend refreshed = getOwned(tenantId, id);
        return ResponseEntity.ok(response(refreshed));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") String id) {
        service.delete(tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<?> setDefault(@PathVariable("id") String id) {
        service.setDefault(tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testByID(@PathVariable("id") String id) {
        long tenantId = tenantId();
        StorageBackend backend = getOwned(tenantId, id);
        try {
            service.test(backend);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(failureBody(e));
        }
        return ResponseEntity.ok(connectedBody(true));
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private StorageBackend getOwned(long tenantId, String id) {
        StorageBackend backend = service.getBackend(tenantId, id);
        if (backend == null) {
            throw BizException.notFound("storage backend not found");
        }
        return backend;
    }

    private static long tenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0 : tenantId;
    }

    private static StorageBackend carrier(long tenantId, StorageBackendRequest req) {
        StorageBackend b = new StorageBackend();
        b.setTenantId(tenantId);
        b.setName(req.name());
        b.setProvider(req.provider());
        b.setConfig(req.config() == null
                ? MAPPER.valueToTree(new StorageConfig())
                : MAPPER.valueToTree(req.config()));
        b.setStatus(req.status() == null ? "" : req.status());
        return b;
    }

    private StorageBackendResponse response(StorageBackend b) {
        StorageBackendResponse r = new StorageBackendResponse();
        r.id = b.getId();
        r.tenantId = b.getTenantId() == null ? 0 : b.getTenantId();
        r.name = b.getName();
        r.provider = b.getProvider();
        r.config = service.maskedConfig(b);
        r.source = b.getSource();
        r.status = b.getStatus();
        r.legacyAlias = b.isLegacyAlias();
        r.createdAt = b.getCreatedAt();
        r.updatedAt = b.getUpdatedAt();
        r.deletedAt = b.getDeletedAt();
        return r;
    }

    /** 对照 storageTestErrorMessage：AppError 取 message，其余清洗（HTTP 状态保持 200） */
    private static Map<String, Object> failureBody(RuntimeException e) {
        String message;
        if (e instanceof BizException biz) {
            message = biz.appError().message();
        } else if (e instanceof StorageBackendService.ConnectorFailure cf) {
            message = StorageBackendService.sanitizeConnectivity(cf.getMessage());
        } else {
            message = StorageBackendService.sanitizeConnectivity(e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", false);
        body.put("error", message);
        return body;
    }

    /** 连通性测试的成功体：{connected:true}（与 failureBody 同形对称）。 */
    private static Map<String, Object> connectedBody(boolean connected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", connected);
        return body;
    }

    private static StorageBackendRequest bind(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest("No content to map due to end-of-input");
        }
        StorageBackendRequest req;
        try {
            req = MAPPER.readValue(rawBody, StorageBackendRequest.class);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
        List<String> missing = new ArrayList<>();
        if (req.name() == null || req.name().isEmpty()) {
            missing.add("Name");
        }
        if (req.provider() == null || req.provider().isEmpty()) {
            missing.add("Provider");
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String field : missing) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("Key: 'storageBackendRequest.").append(field)
                        .append("' Error:Field validation for '").append(field)
                        .append("' failed on the 'required' tag");
            }
            throw BizException.badRequest(sb.toString());
        }
        return req;
    }

}
