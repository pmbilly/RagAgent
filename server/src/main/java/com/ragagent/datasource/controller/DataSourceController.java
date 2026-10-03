package com.ragagent.datasource.controller;

import com.ragagent.common.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.context.TenantContext;
import com.ragagent.datasource.ConnectorCatalog;
import com.ragagent.datasource.ConnectorMetadata;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.dto.DataSourceResponse;
import com.ragagent.datasource.service.DataSourceService;
import com.ragagent.knowledge.domain.KnowledgeBase;
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
 * 数据源管理端点。
 *
 * <h2>错误形态：纯字符串 {@code {"error":"..."}}，不是 AppError 信封</h2>
 * <p>本类的 handler <b>全部</b>直写 {@code {"error": msg}} 单键体，
 * 所以线上永远是 {@code {"error":"..."}} 单键。
 * 凭据子资源控制器（{@code DataSourceCredentialsController}）走的是 AppError 信封，
 * <b>两者形态不同，别统一</b>。</p>
 *
 * <h2>鉴权分三层，缺一不可</h2>
 * <ol>
 *   <li><b>角色</b>：读端 Viewer+、其余 Admin+（见 WebConfig）；</li>
 *   <li><b>租户归属</b>：{@link #ownDataSource} —— 数据源持有外部服务的凭据，
 *       只有属主租户能碰；</li>
 *   <li><b>API-Key 的 KB 白名单</b>：{@code AuthorizeTenantAPIKeyKnowledgeBases}，
 *       写在 {@link KnowledgeBaseOwnerGuard} 里（所有端点都经过它，一处覆盖全部）。</li>
 * </ol>
 *
 * <h2>为什么每个端点都自己取一次租户</h2>
 * <p>每个端点开头都取当前租户并判 0 → 401，不抽成切面：
 * 401 的<b>文案在两组端点里不同</b>
 * （create/list 是 {@code "unauthorized: workspace context missing"}，其余是
 * {@code "unauthorized"}），抽公共方法反而要额外传参。</p>
 */
@RestController
public class DataSourceController {

    /**
     * 请求体解析器：{@code FAIL_ON_UNKNOWN_PROPERTIES=false}
     * （默认忽略未知字段）。Jackson 的裸配置默认会<b>失败</b>
     * ——前端多带一个字段就整条 400 是这里最不该发生的事。</p>
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 列表分页的最大页大小。 */
    static final int MAX_LIST_PAGE_SIZE = 100;

    private final DataSourceService service;
    private final KnowledgeBaseOwnerGuard kbGuard;

    public DataSourceController(DataSourceService service, KnowledgeBaseOwnerGuard kbGuard) {
        this.service = service;
        this.kbGuard = kbGuard;
    }

    // ══════════════════════════ 连接器目录 ══════════════════════════

    /**
     * 列出可用连接器。
     *
     * <p>返回<b>裸数组</b>（没有 data/success 信封），元素是 {@link ConnectorMetadata}，
     * 按优先级做<b>稳定</b>排序（同优先级保持声明序）。
     * 比对时按 type 建索引，别按下标比（见 {@link ConnectorCatalog}）。</p>
     */
    @GetMapping("/api/v1/datasource/types")
    public List<ConnectorMetadata> getAvailableConnectors() {
        return ConnectorCatalog.listAvailableConnectors();
    }

    // ══════════════════════════ CRUD ══════════════════════════

    /**
     * 新建数据源：成功是 <b>201</b> Created。
     *
     * <p>顺序：租户 → 解析请求体（400 {@code invalid request}）→ 知识库归属
     * （400/403/404）→ 强制 {@code req.TenantID = tenantID} → service。
     * 客户端就算在 body 里塞了别的租户 id 也会被覆盖掉。</p>
     */
    @PostMapping("/api/v1/datasource")
    public ResponseEntity<?> createDataSource(@RequestBody(required = false) String rawBody) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized: workspace context missing");
        }
        DataSource req = parseDataSource(rawBody);
        if (req == null) {
            return error(400, "invalid request");
        }
        ResponseEntity<?> kbError = kbGuard.check(tenantId, req.getKnowledgeBaseId());
        if (kbError != null) {
            return kbError;
        }
        req.setTenantId(tenantId);
        DataSource ds;
        try {
            ds = service.createDataSource(req);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.status(201).body(DataSourceResponse.from(ds));
    }

    /** 查询单个数据源。 */
    @GetMapping("/api/v1/datasource/{id}")
    public ResponseEntity<?> getDataSource(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        return ResponseEntity.ok(DataSourceResponse.from(owned.ds()));
    }

    /**
     * 列出数据源。
     *
     * <p>⚠️ 空列表输出 {@code []} 而不是 {@code null}——仓储本来就回
     * 空列表，这里再兜一次是为了不依赖下游的实现细节。</p>
     */
    @GetMapping("/api/v1/datasource")
    public ResponseEntity<?> listDataSources(
            @RequestParam(value = "kbId", required = false) String kbId) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized: workspace context missing");
        }
        String kb = kbId == null ? "" : kbId;
        ResponseEntity<?> kbError = kbGuard.check(tenantId, kb);
        if (kbError != null) {
            return kbError;
        }
        List<DataSource> dataSources;
        try {
            dataSources = service.listDataSources(kb);
        } catch (RuntimeException e) {
            return error(500, "failed to list data sources");
        }
        List<DataSourceResponse> out = new ArrayList<>();
        for (DataSource ds : dataSources) {
            out.add(DataSourceResponse.from(ds));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 更新数据源。
     *
     * <p>顺序有一个<b>容易写错的细节</b>：先解析请求体、<b>再</b>查归属。
     * 所以"非法 JSON + 不存在的数据源"返回的是 400 {@code invalid request}，不是 404。</p>
     *
     * <p>{@code req.ID / TenantID / KnowledgeBaseID} 一律由<b>库里的行</b>覆盖——
     * 客户端改不了租户，也改不了归属知识库（service 里还有第二道"不允许换库"）。</p>
     */
    @PutMapping("/api/v1/datasource/{id}")
    public ResponseEntity<?> updateDataSource(@PathVariable("id") String id,
                                              @RequestBody(required = false) String rawBody) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        DataSource req = parseDataSource(rawBody);
        if (req == null) {
            return error(400, "invalid request");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        req.setId(id);
        req.setTenantId(owned.ds().getTenantId());
        req.setKnowledgeBaseId(owned.ds().getKnowledgeBaseId());
        DataSource ds;
        try {
            ds = service.updateDataSource(req);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(DataSourceResponse.from(ds));
    }

    /** 删除数据源：成功是 <b>204</b>，无响应体。 */
    @DeleteMapping("/api/v1/datasource/{id}")
    public ResponseEntity<?> deleteDataSource(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        try {
            service.deleteDataSource(id);
        } catch (RuntimeException e) {
            return error(500, "failed to delete data source");
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 连接与资源 ══════════════════════════

    /** 校验已存连接：成功回 {@code {"status":"connected"}}。 */
    @PostMapping("/api/v1/datasource/{id}/validate")
    public ResponseEntity<?> validateConnection(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        try {
            service.validateConnection(id);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(statusBody("connected"));
    }

    /**
     * 用裸凭据试连，<b>不落库</b>。
     *
     * <p>请求体缺失/解析失败/字段缺一，都回<b>同一句</b>
     * {@code invalid request: type and credentials are required}。</p>
     */
    @PostMapping("/api/v1/datasource/validate-credentials")
    public ResponseEntity<?> validateCredentials(@RequestBody(required = false) String rawBody) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        ValidateCredentialsRequest req = null;
        try {
            if (rawBody != null && !rawBody.isBlank()) {
                req = MAPPER.readValue(rawBody, ValidateCredentialsRequest.class);
            }
        } catch (Exception ignored) {
            req = null;
        }
        // 字段校验：字符串判空、map 判 null（空 map 是合法的）
        if (req == null || req.type() == null || req.type().isEmpty() || req.credentials() == null) {
            return error(400, "invalid request: type and credentials are required");
        }
        try {
            service.validateCredentials(req.type(), req.credentials());
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(statusBody("connected"));
    }

    /** 试连请求体（{@code type} + {@code credentials}）。 */
    record ValidateCredentialsRequest(String type, Map<String, Object> credentials) {
    }

    /**
     * 列出可同步资源。
     *
     * <p>{@code parent_id} 为空串即列顶层——这是"惰性加载层级资源"（飞书 wiki 这种
     * 大源）的入口。空列表同样归一成 {@code []}。</p>
     */
    @GetMapping("/api/v1/datasource/{id}/resources")
    public ResponseEntity<?> listAvailableResources(
            @PathVariable("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        List<Resource> resources;
        try {
            resources = service.listAvailableResources(id, parentId == null ? "" : parentId);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(resources == null ? new ArrayList<Resource>() : resources);
    }

    /**
     * 解析资源祖先。
     *
     * <p>⚠️ 请求体解析失败时直接回解析器的原文
     * （例如 {@code invalid character 'o' in literal null}），不同解析器措辞不同，
     * 契约测试掩码该字段。</p>
     */
    @PostMapping("/api/v1/datasource/{id}/resource-ancestors")
    public ResponseEntity<?> resolveResourceAncestors(@PathVariable("id") String id,
                                                      @RequestBody(required = false) String rawBody) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        ResolveAncestorsRequest req;
        try {
            req = rawBody == null || rawBody.isBlank()
                    ? new ResolveAncestorsRequest(null)
                    : MAPPER.readValue(rawBody, ResolveAncestorsRequest.class);
        } catch (Exception e) {
            return error(400, e.getMessage());
        }
        List<String> ancestors;
        try {
            ancestors = service.resolveResourceAncestors(id, req.resourceIds());
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ancestors", ancestors == null ? new ArrayList<String>() : ancestors);
        return ResponseEntity.ok(body);
    }

    /** 资源祖先请求体（{@code resourceIds} 字段可缺）。 */
    record ResolveAncestorsRequest(List<String> resourceIds) {
    }

    // ══════════════════════════ 同步控制 ══════════════════════════

    /**
     * 手动触发同步：返回新建的 sync_log 裸实体。
     *
     * <p>注意两条 400 的文案不同：数据源不存在（404 {@code data source not found}）
     * 与状态不允许（400 {@code data source is not active}）——别合并。</p>
     */
    @PostMapping("/api/v1/datasource/{id}/sync")
    public ResponseEntity<?> manualSync(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        SyncLog syncLog;
        try {
            syncLog = service.manualSync(id);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(syncLog);
    }

    /** 暂停数据源。 */
    @PostMapping("/api/v1/datasource/{id}/pause")
    public ResponseEntity<?> pauseDataSource(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        try {
            service.pauseDataSource(id);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(statusBody("paused"));
    }

    /** 恢复数据源。 */
    @PostMapping("/api/v1/datasource/{id}/resume")
    public ResponseEntity<?> resumeDataSource(@PathVariable("id") String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }
        try {
            service.resumeDataSource(id);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(statusBody("active"));
    }

    // ══════════════════════════ 同步日志 ══════════════════════════

    /**
     * 查询同步日志列表。
     *
     * <p>分页参数<b>不</b>容错：{@code limit} 只要给了就必须落在 1..100，否则 400
     * （注意这与 memory 模块那个"非法 limit 一律归 50"是<b>相反</b>的处置）。
     * {@code offset} 反而容错：解析失败或为负都归 0。</p>
     */
    @GetMapping("/api/v1/datasource/{id}/logs")
    public ResponseEntity<?> getSyncLogs(@PathVariable("id") String id,
                                         @RequestParam(value = "limit", required = false) String limit,
                                         @RequestParam(value = "offset", required = false) String offset) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        Owned owned = ownDataSource(tenantId, id);
        if (owned.error() != null) {
            return owned.error();
        }

        int limitValue = 10;
        if (limit != null && !limit.isEmpty()) {
            Integer v = parseInt(limit);
            if (v == null || v <= 0 || v > MAX_LIST_PAGE_SIZE) {
                return error(400, "limit must be between 1 and " + MAX_LIST_PAGE_SIZE);
            }
            limitValue = v;
        }
        int offsetValue = 0;
        if (offset != null && !offset.isEmpty()) {
            Integer v = parseInt(offset);
            if (v != null && v >= 0) {
                offsetValue = v;
            }
        }

        List<SyncLog> logs;
        try {
            logs = service.getSyncLogs(id, limitValue, offsetValue);
        } catch (RuntimeException e) {
            return error(400, e.getMessage());
        }
        return ResponseEntity.ok(logs == null ? new ArrayList<SyncLog>() : logs);
    }

    /**
     * 查询单条同步日志。
     *
     * <p>⚠️ 判定顺序有语义：<b>先</b>按 log_id 查（查不到 → 404
     * {@code sync log not found}），<b>再</b>校验它所属数据源的租户归属。所以
     * "日志不存在"与"日志属于别人"的文案不同——后者走 {@code getOwnedDataSource}，
     * 它回的是 {@code data source not found}。</p>
     */
    @GetMapping("/api/v1/datasource/logs/{log_id}")
    public ResponseEntity<?> getSyncLog(@PathVariable("log_id") String logId) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return error(401, "unauthorized");
        }
        SyncLog syncLog;
        try {
            syncLog = service.getSyncLog(logId);
        } catch (RuntimeException e) {
            return error(404, "sync log not found");
        }
        Owned owned = ownDataSource(tenantId, syncLog.getDataSourceId());
        if (owned.error() != null) {
            return owned.error();
        }
        return ResponseEntity.ok(syncLog);
    }

    // ══════════════════════════ 归属守卫 ══════════════════════════

    private record Owned(DataSource ds, ResponseEntity<?> error) {
    }

    /**
     * 归属判定：
     * 先按 id 取数据源（取不到 → 404 {@code data source not found}），
     * 再校验它所属的知识库归属。
     *
     * <p>⚠️ 两步的异常边界<b>必须分开</b>：知识库那一层会抛
     * {@code GuardForbiddenException}（API-Key 白名单拒绝），把它一起 catch 成 404
     * 会把 403 变成 404——这是"一个 id 能不能用来探测存在性"的实质区别。</p>
     */
    private Owned ownDataSource(long tenantId, String id) {
        DataSource ds;
        try {
            ds = service.getDataSource(id);
        } catch (RuntimeException e) {
            return new Owned(null, error(404, "data source not found"));
        }
        ResponseEntity<?> kbError = kbGuard.check(tenantId, ds.getKnowledgeBaseId());
        if (kbError != null) {
            return new Owned(null, kbError);
        }
        return new Owned(ds, null);
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /**
     * 解析请求体：空 body 与非法 JSON 都回
     * {@code invalid request}（本类的 handler 只用这一句，不像凭据那个控制器会带上
     * 解析器原文）。
     */
    private static DataSource parseDataSource(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(rawBody, DataSource.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单键 {@code {"status": "..."}}。 */
    static Map<String, Object> statusBody(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        return body;
    }

    /** 单键 {@code {"error": msg}}。 */
    static ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 知识库归属 + API-Key KB 白名单的联合守卫。
     *
     * <p>抽成独立 bean 而不是 controller 的私有方法：凭据子资源那个控制器也要用
     * 同一套知识库判定（两个控制器的<b>错误形态不同</b>——本类回纯字符串，
     * 那个回 AppError 信封，所以只共享这一层）。</p>
     */
    @org.springframework.stereotype.Component
    public static class KnowledgeBaseOwnerGuard {

        private final com.ragagent.datasource.service.KnowledgeBridge knowledge;

        public KnowledgeBaseOwnerGuard(com.ragagent.datasource.service.KnowledgeBridge knowledge) {
            this.knowledge = knowledge;
        }

        /**
         * @return {@code null} = 通过；否则是原样返回的错误响应
         *         （400 {@code kb_id is required} / 404 {@code knowledge base not found} /
         *         403 {@code access denied} / 403 白名单拒绝）
         */
        ResponseEntity<?> check(long tenantId, String kbId) {
            if (kbId == null || kbId.isEmpty()) {
                return error(400, "kb_id is required");
            }
            // ⚠️ 这里**不能**用 kbService.getKnowledgeBase(id)：那个方法带租户过滤，
            // 会把"库不存在"与"库属于别人"压成同一个 404；本守卫要保持
            // 404（不存在）与 403（存在但属别人）两种应答——跨租户探测的形态不同。
            // 故走不带租户条件的 KnowledgeBridge.findKnowledgeBase。
            KnowledgeBase kb = knowledge.findKnowledgeBase(kbId);
            if (kb == null) {
                return error(404, "knowledge base not found");
            }
            if (kb.getTenantId() == null || kb.getTenantId() != tenantId) {
                return error(403, "access denied");
            }
            try {
                TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));
            } catch (RuntimeException e) {
                return error(403, e.getMessage());
            }
            return null;
        }
    }
}
