package com.ragagent.websearch.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProvider;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.websearch.dto.WebSearchProviderResponse;
import com.ragagent.websearch.dto.WebSearchProviderTypes;
import com.ragagent.websearch.provider.EmptyTestResults;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;
import com.ragagent.websearch.service.WebSearchProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go {@code handler.WebSearchProviderHandler}（internal/handler/web_search_provider.go，
 * routes_infra.go L221-245 的 10 条路由；角色门：读 Viewer+ / 写 Admin+）。
 *
 * <p>错误形态分层（§9「波 2 chunk」教训——逐端点照抄，不做统一映射）：</p>
 * <ul>
 *   <li>租户缺失 → 401 纯字符串 {@code {"success":false,"error":"unauthorized: workspace context missing"}}
 *       （gin.H 字母序 error &lt; success）；</li>
 *   <li>绑定失败 → 400 AppError 信封 code 1000（EOF / validator 原文，多字段按 struct 序
 *       {@code \n} 连接）；</li>
 *   <li>getOwned 404 → **纯字符串** {@code {"error":"web search provider not found","success":false}}；</li>
 *   <li>service 业务失败（create/update/delete-credentials）→ **500** 信封 code 1007 + 原文；</li>
 *   <li>test 端点的测试失败 → **200** 纯字符串 {@code {"error": err.Error(), "success": false}}
 *       （doTestSearch 产的是普通 error，无 AppError 双前缀）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/web-search-providers")
public class WebSearchProviderController {

    private static final Logger log = LoggerFactory.getLogger(WebSearchProviderController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebSearchProviderService service;
    private final WebSearchProviderRegistry registry;

    public WebSearchProviderController(WebSearchProviderService service,
                                       WebSearchProviderRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    // ── /types（Viewer+）：静态元数据 ──────────────────────────────────

    @GetMapping("/types")
    public ResponseEntity<?> listProviderTypes() {
        return ResponseEntity.ok(WebSearchProviderTypes.all());
    }

    // ── POST /test（Admin+）：原始凭据连通性 ───────────────────────────

    /** 对照 TestProviderRequest：provider required；parameters 可选 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestProviderRequest(
            String provider,
            WebSearchProviderParams parameters) {}

    @PostMapping("/test")
    public ResponseEntity<?> testProviderRaw(@RequestBody(required = false) String rawBody) {
        TestProviderRequest req = bind(rawBody, TestProviderRequest.class, "TestProviderRequest");
        if (req.provider() == null || req.provider().isEmpty()) {
            throw validatorError("TestProviderRequest", "Provider");
        }
        doTestSearch(req.provider(), req.parameters());
        return ResponseEntity.ok(connectedBody());
    }

    // ── CRUD ───────────────────────────────────────────────────────────

    /** 对照 CreateProviderRequest：name/provider required（validator 多字段 struct 序拼接） */
    @PostMapping
    public ResponseEntity<?> createProvider(@RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        JsonNode body = parseBody(rawBody);
        String name = textOrNull(body, "name");
        String provider = textOrNull(body, "provider");
        List<String> missing = new java.util.ArrayList<>();
        if (name == null) {
            missing.add("Name");
        }
        if (provider == null) {
            missing.add("Provider");
        }
        if (!missing.isEmpty()) {
            throw validatorError("CreateProviderRequest", missing.toArray(new String[0]));
        }
        WebSearchProvider providerEntity = new WebSearchProvider();
        providerEntity.setId(UUID.randomUUID().toString());
        providerEntity.setTenantId(tenantId);
        providerEntity.setName(sanitize(name));
        providerEntity.setProvider(provider);
        providerEntity.setDescription(sanitize(textOrEmpty(body, "description")));
        providerEntity.setParameters(paramsOf(body));
        providerEntity.setDefault(boolOrFalse(body, "isDefault"));
        try {
            service.create(providerEntity);
        } catch (RuntimeException e) {
            log.warn("Failed to create web search provider: {}", e.getMessage());
            throw BizException.internal(e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WebSearchProviderResponse.from(providerEntity, canViewIntegrationSecrets()));
    }

    @GetMapping
    public ResponseEntity<?> listProviders() {
        long tenantId = requireTenant();
        List<WebSearchProvider> providers = service.list(tenantId);
        return ResponseEntity.ok(
                WebSearchProviderResponse.listOf(providers, canViewIntegrationSecrets()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProvider(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        WebSearchProvider provider = owned(tenantId, id);
        return ResponseEntity.ok(WebSearchProviderResponse.from(provider, canViewIntegrationSecrets()));
    }

    /** 对照 UpdateProviderRequest：无 required 字段；merge 规则见下 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateProviderRequest(
            String name,
            String description,
            WebSearchProviderParams parameters,
            Boolean isDefault) {}

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        // Go：ownership 检查先于 ShouldBindJSON（未知 id + 坏 body 都是 404）
        WebSearchProvider existing = owned(tenantId, id);
        UpdateProviderRequest req = bind(rawBody, UpdateProviderRequest.class, "UpdateProviderRequest");

        // api_key 绝不从本端点流动：强制保留存量（deprecated 告警仅日志）
        WebSearchProviderParams merged = req.parameters() == null ? new WebSearchProviderParams() : req.parameters();
        WebSearchProviderParams existingParams =
                existing.getParameters() == null ? new WebSearchProviderParams() : existing.getParameters();
        merged.setApiKey(existingParams.getApiKey());
        // extra_config 为 nil（请求缺省）时保留存量
        if (merged.getExtraConfig() == null) {
            merged.setExtraConfig(existingParams.getExtraConfig());
        }
        // 顶层 name/description：请求缺省（空串）时保留存量
        String mergedName = req.name() == null || req.name().isEmpty() ? existing.getName() : req.name();
        String mergedDescription = req.description() == null || req.description().isEmpty()
                ? existing.getDescription() : req.description();

        WebSearchProvider updated = new WebSearchProvider();
        updated.setId(id);
        updated.setTenantId(tenantId);
        updated.setName(sanitize(mergedName));
        updated.setProvider(existing.getProvider()); // provider 类型创建后不可变
        updated.setDescription(sanitize(mergedDescription));
        updated.setParameters(merged);
        updated.setDefault(req.isDefault() != null && req.isDefault());
        try {
            service.update(updated);
        } catch (RuntimeException e) {
            log.warn("Failed to update web search provider {}: {}", id, e.getMessage());
            throw BizException.internal(e.getMessage());
        }
        // Re-fetch to get the full stored state
        WebSearchProvider refreshed = service.getByID(tenantId, id);
        if (refreshed != null) {
            return ResponseEntity.ok(WebSearchProviderResponse.from(refreshed, canViewIntegrationSecrets()));
        }
        return ResponseEntity.ok(connectedBody());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProvider(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        owned(tenantId, id);
        try {
            service.delete(tenantId, id);
        } catch (RuntimeException e) {
            log.warn("Failed to delete web search provider {}: {}", id, e.getMessage());
            throw BizException.internal(e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // ── POST /{id}/test（Admin+）：已存 provider 连通性 ─────────────────

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testProviderByID(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        WebSearchProvider provider = owned(tenantId, id);
        doTestSearch(provider.getProvider(), provider.getParameters());
        return ResponseEntity.ok(connectedBody());
    }

    // ── 内部辅助 ───────────────────────────────────────────────────────

    /**
     * 对照 doTestSearch（handler/web_search_provider.go L412-431 全量，2026-09-23
     * 占位扫清）：CreateProvider → {@code search("test", 1, false)} → 空结果 →
     * EmptyTestResultsError 文案；三支失败均以 200 纯字符串输出（{@link TestFailure}）。
     * 真实出站受本部署 SSRF 白名单约束（与 Go 的 guard 同款——白名单外目标在触网前
     * 即拒，错误原文经 TestFailure 200 输出）。
     */
    private void doTestSearch(String providerType, WebSearchProviderParams params) {
        log.info("[WebSearch][Test] testing provider type={}", providerType);
        com.ragagent.websearch.provider.WebSearchProvider provider;
        try {
            provider = registry.createProvider(providerType, params);
        } catch (RuntimeException e) {
            log.warn("[WebSearch][Test] failed to create provider: {}", e.getMessage());
            throw new TestFailure("failed to create provider: " + messageOf(e));
        }
        List<WebSearchResult> results;
        try {
            results = provider.search("test", 1, false);
        } catch (RuntimeException e) {
            log.warn("[WebSearch][Test] search failed: {}", e.getMessage());
            throw new TestFailure(messageOf(e));
        }
        if (results == null || results.isEmpty()) {
            String message = EmptyTestResults.emptyTestResultsError(providerType, provider).getMessage();
            log.warn("[WebSearch][Test] {}", message);
            throw new TestFailure(message);
        }
        log.info("[WebSearch][Test] succeeded: type={}, results={}", providerType, results.size());
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** test 端点的失败形态：200 纯字符串（不走全局异常的信封） */
    public static class TestFailure extends RuntimeException {
        public TestFailure(String message) {
            super(message);
        }
    }

    private WebSearchProvider owned(long tenantId, String id) {
        WebSearchProvider provider;
        try {
            provider = service.getByID(tenantId, id);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to query provider");
        }
        if (provider == null) {
            throw notFoundPure("web search provider not found");
        }
        return provider;
    }

    /** getOwned 的 404 是纯字符串形态（c.JSON 直写，非 AppError 信封） */
    public static class PureNotFound extends RuntimeException {
        public PureNotFound(String message) {
            super(message);
        }
    }

    private static PureNotFound notFoundPure(String message) {
        return new PureNotFound(message);
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new TenantMissing();
        }
        return tenantId;
    }

    /** 租户缺失：401 纯字符串（gin.H） */
    public static class TenantMissing extends RuntimeException {
    }

    /** 对照 dto.CanViewIntegrationSecrets：Admin+ 或（全量/管理租户设置能力的 API key） */
    static boolean canViewIntegrationSecrets() {
        if (TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        if (!APIKeyScopeContext.present()) {
            return false;
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return false;
        }
        return scope.fullAccess() || scope.hasCapability("manage_tenant_settings");
    }

    static WebSearchProviderParams paramsOf(JsonNode body) {
        JsonNode node = body.get("parameters");
        if (node == null || node.isNull()) {
            return new WebSearchProviderParams();
        }
        try {
            return MAPPER.treeToValue(node, WebSearchProviderParams.class);
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(null, e.getMessage()));
        }
    }

    static String textOrNull(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    static String textOrEmpty(JsonNode body, String field) {
        String v = textOrNull(body, field);
        return v == null ? "" : v;
    }

    static boolean boolOrFalse(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.asBoolean(false);
    }

    /** 对照 secutils.SanitizeForLog（换行/制表符→空格、去控制字符）——它同时是**存储值** */
    static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : "";
        }
        String s = input.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 32) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    static JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest("EOF");
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /** 对照 go-playground/validator：多失败字段按 struct 序用 \n 连接（message 内） */
    static BizException validatorError(String structName, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("Key: '").append(structName).append('.').append(field)
                    .append("' Error:Field validation for '").append(field)
                    .append("' failed on the 'required' tag");
        }
        return BizException.badRequest(sb.toString());
    }

    /** 绑定 JSON：EOF / 解析器原文 → 400 code 1000 */
    static <T> T bind(String rawBody, Class<T> type, String structName) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest("EOF");
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /** gin.H：{"data":..., "success":true}（字母序 data < success） */
    static Map<String, Object> envelopeData(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }


    /** 连通性测试成功体：{connected:true}（与失败体 {connected:false,error} 同形对称）。 */
    private static Map<String, Object> connectedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", true);
        return body;
    }

    /** 纯字符串错误体：{"error": msg}（不进全局信封，对照 Go handler 的 c.JSON 直写） */
    public static Map<String, Object> errorEnvelope(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    // ── 本控制器私有的错误形态（对照 Go handler 的 c.JSON 直写，不进全局信封） ──

    @ExceptionHandler(PureNotFound.class)
    public ResponseEntity<?> handlePureNotFound(PureNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorEnvelope(e.getMessage()));
    }

    @ExceptionHandler(TenantMissing.class)
    public ResponseEntity<?> handleTenantMissing() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorEnvelope("unauthorized: workspace context missing"));
    }

    @ExceptionHandler(TestFailure.class)
    public ResponseEntity<?> handleTestFailure(TestFailure e) {
        return ResponseEntity.ok(errorEnvelope(e.getMessage()));
    }
}
