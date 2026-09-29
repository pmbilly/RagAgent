package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.DataMessageResponse;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeDtos;
import com.ragagent.knowledge.dto.KnowledgeDtos.BatchDeleteRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.KnowledgeTagBatchRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.MoveKnowledgeRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.MoveToFolderRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.RenameFolderRequest;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.KnowledgeSearchService;
import com.ragagent.knowledge.service.KnowledgeTaskIds;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.MoveKnowledgeResponse;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKeyScope;

/**
 * 知识文档运营操作面：跨库搜索、批量删除/重析、批量标签、跨 KB 搬移与进度、
 * 文件夹移动/重命名。批处理路由的 KB 访问 + ownership（信封形态）经
 * {@link KnowledgeRouteGuards#batchAccessChecks}；搬移的行级校验（归属/状态/搬移中）
 * 语义照既有契约，错误文案与顺序不能调整。
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeOpsController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeOpsController.class);

    private final KnowledgeService knowledgeService;
    private final KnowledgeRouteGuards guards;

    public KnowledgeOpsController(KnowledgeService knowledgeService,
                                  KnowledgeRouteGuards guards) {
        this.knowledgeService = knowledgeService;
        this.guards = guards;
    }

    // ── 跨库搜索 ─────────────────────────────────────────────────────────

    /**
     * 跨库搜索（Viewer，无 KB 守卫——范围由调用方决定）：keyword/?query 双参 →
     * recent 判空 → offset/limit 校验 → file_types → API-Key 白名单。空 keyword
     * 只在显式 recent=true 时合法。
     */
    @GetMapping("/knowledge/search")
    public ResponseEntity<Object> searchKnowledge(
            @RequestParam(value = "keyword", required = false) String keywordParam,
            @RequestParam(value = "query", required = false) String queryParam,
            @RequestParam(value = "recent", required = false) String recentParam,
            @RequestParam(value = "offset", required = false) String offsetParam,
            @RequestParam(value = "limit", required = false) String limitParam,
            @RequestParam(value = "file_types", required = false) String fileTypesParam) {
        // 非法 recent 值静默为 false
        boolean recent = Boolean.parseBoolean(recentParam == null ? "false" : recentParam.trim());
        String keyword = keywordParam == null ? "" : keywordParam;
        if (keyword.isEmpty()) {
            keyword = queryParam == null ? "" : queryParam;
        }
        if (keyword.trim().isEmpty() && !recent) {
            throw new BizException(AppError.badRequest(
                    "missing search keyword: pass ?keyword=... or ?query=..."));
        }
        keyword = keyword.trim();
        int offset = 0;
        int limit = 20;
        if (offsetParam != null && !offsetParam.trim().isEmpty()) {
            Integer v = parseIntStrict(offsetParam.trim());
            if (v == null || v < 0) {
                throw new BizException(AppError.validation("offset must be a non-negative integer"));
            }
            offset = v;
        }
        if (limitParam != null && !limitParam.trim().isEmpty()) {
            Integer v = parseIntStrict(limitParam.trim());
            if (v == null || v < 1 || v > 100) {
                throw new BizException(AppError.validation("limit must be between 1 and 100"));
            }
            limit = v;
        }
        List<String> fileTypes = new ArrayList<>();
        if (fileTypesParam != null && !fileTypesParam.isEmpty()) {
            for (String ft : fileTypesParam.split(",")) {
                String t = ft.trim();
                if (!t.isEmpty()) {
                    fileTypes.add(t);
                }
            }
        }
        KnowledgeSearchService.SearchOutcome outcome;
        var scope = APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            // 受限 API Key 的搜索范围 = 白名单 KB（本租户）
            List<KnowledgeSearchService.KnowledgeSearchScope> scopes = new ArrayList<>();
            long tid = KnowledgeRouteGuards.tenantId();
            for (String kbId : scope.knowledgeBaseIds()) {
                scopes.add(new KnowledgeSearchService.KnowledgeSearchScope(tid, kbId));
            }
            outcome = knowledgeService.searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
        } else {
            outcome = knowledgeService.searchKnowledge(keyword, offset, limit, fileTypes);
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("data", outcome.knowledges());
        body.put("has_more", outcome.hasMore());
        body.put("success", true);
        body.put("total", outcome.total());
        return ResponseEntity.ok(body);
    }

    private static Integer parseIntStrict(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 批量标签 ─────────────────────────────────────────────────────────

    /** body 携带可选 kb_id；缺省时从首条 knowledge 推导授权 KB（handler 层守卫）。 */
    @PutMapping("/knowledge/tags")
    public ResponseEntity<ApiResponse<Void>> updateKnowledgeTagBatch(
            @Valid @NonNullBody @RequestBody KnowledgeTagBatchRequest req) {
        if (KnowledgeRouteGuards.tenantId() == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        String kbId = LogSanitizer.sanitize(req.kbId() == null ? "" : req.kbId());
        String authorizedKbId;
        if (!kbId.isEmpty()) {
            authorizedKbId = kbId;
        } else {
            // updates 非空（@NotEmpty 已保证）：取首条 knowledge 推导授权 KB（单键确定性一致）
            String firstKnowledgeId = req.updates().keySet().iterator().next();
            Knowledge k = guards.resolveKnowledgeHandlerLevel(LogSanitizer.sanitize(firstKnowledgeId), true);
            authorizedKbId = k.getKnowledgeBaseId();
        }
        guards.batchAccessChecks(authorizedKbId);
        knowledgeService.updateKnowledgeTagBatch(authorizedKbId, req.updates());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // ── 批量删除 / 重析 ──────────────────────────────────────────────────

    /** dedupe/maxBatch → KB 访问+ownership → 行校验（含搬移中拒绝）→ task_id。 */
    @PostMapping("/knowledge/batch-delete")
    public ResponseEntity<DataMessageResponse<KnowledgeDtos.BatchTaskData>> batchDeleteKnowledge(
            @Valid @NonNullBody @RequestBody BatchDeleteRequest req) {
        String kbId = LogSanitizer.sanitize(req.kbId());
        if (req.ids() == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("ids: 不能为空"));
        }
        List<String> ids = guards.requireBatchIds(req.ids(), "ids");
        guards.batchAccessChecks(kbId);
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(KnowledgeRouteGuards.tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("One or more knowledge entries not found"));
        }
        for (Knowledge k : rows) {
            guards.rejectMoving(k);
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
        String taskId = knowledgeService.batchDeleteKnowledge(kbId, ids);
        return ResponseEntity.ok(DataMessageResponse.of(
                new KnowledgeDtos.BatchTaskData(ids.size(), taskId), "Batch delete task submitted"));
    }

    /**
     * 一切绑定失败（空体/缺字段/畸形 JSON/空数组）都落固定文案——兜底文案优先于
     * 标准绑定细节，故此处保留手动绑定。
     */
    @PostMapping("/knowledge/batch-reparse")
    public ResponseEntity<DataMessageResponse<KnowledgeDtos.ReparseTaskData>> batchReparseKnowledge(
            @RequestBody(required = false) KnowledgeDtos.BatchReparseRequest request) {
        KnowledgeDtos.BatchReparseRequest parsed = requireBatchReparse(request);
        String kbId = LogSanitizer.sanitize(parsed.kbId());
        List<String> ids = parsed.ids();
        guards.batchAccessChecks(kbId);
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(KnowledgeRouteGuards.tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("some knowledge entries were not found"));
        }
        for (Knowledge k : rows) {
            guards.rejectMoving(k);
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
        String taskId = knowledgeService.batchReparseKnowledge(kbId, ids);
        return ResponseEntity.ok(DataMessageResponse.of(
                new KnowledgeDtos.ReparseTaskData(ids.size(), taskId), "Batch reparse task submitted"));
    }

    /**
     * 校验批量重解析请求：空体 / 缺 kbId / 缺 ids → 固定文案；ids 去重后为空 → 专属文案；
     * 单批上限 200。
     */
    private static KnowledgeDtos.BatchReparseRequest requireBatchReparse(
            KnowledgeDtos.BatchReparseRequest request) {
        if (request == null || request.kbId() == null || request.kbId().isBlank()
                || request.ids() == null) {
            throw new BizException(AppError.badRequest("invalid batch reparse knowledge request parameters"));
        }
        List<String> ids = KnowledgeRouteGuards.dedupeIds(request.ids());
        if (ids.isEmpty()) {
            throw new BizException(AppError.badRequest("no knowledge IDs provided for batch reparse"));
        }
        if (ids.size() > 200) {
            throw new BizException(AppError.badRequest("too many ids (max 200 per batch)"));
        }
        return new KnowledgeDtos.BatchReparseRequest(request.kbId(), ids);
    }

    // ── 文件夹 ───────────────────────────────────────────────────────────

    /** 跨库移动到文件夹（目的地不存在即创建）。 */
    @PostMapping("/knowledge/folder")
    public ResponseEntity<ApiResponse<Object>> moveKnowledgeToFolder(
            @Valid @NonNullBody @RequestBody MoveToFolderRequest req) {
        List<String> ids = guards.requireBatchIds(req.knowledgeIds(), "knowledge_ids");
        String folderPath = req.folderPath() == null ? "" : req.folderPath();
        String kbId = guards.batchAccessChecks(LogSanitizer.sanitize(req.kbId()));
        guards.requireKnowledgeInKb(kbId, ids);
        long affected = knowledgeService.moveKnowledgeToFolder(kbId, ids, folderPath);
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("folder_path", KnowledgeService.normalizeKnowledgeFolderPath(folderPath));
        data.put("moved_count", affected);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /** 路由 ownership（纯字符串，KB 缺失放行）→ KB 访问 + ownership（信封）→ service。 */
    @PutMapping("/knowledge-bases/{id}/knowledge/folders")
    public ResponseEntity<ApiResponse<Object>> renameKnowledgeFolder(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody RenameFolderRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        KnowledgeBase routeKb = knowledgeService.findKb(kbId);
        if (routeKb != null) {
            guards.requireOwnedKb(routeKb);
        }
        guards.batchAccessChecks(kbId);
        long affected = knowledgeService.renameKnowledgeFolder(kbId, req.from(), req.to());
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("folder_path", KnowledgeService.normalizeKnowledgeFolderPath(req.to()));
        data.put("moved_count", affected);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    // ── 跨 KB 搬移 ───────────────────────────────────────────────────────

    /** 源/目标双库守卫链 + 兼容性校验 + 逐行归属/状态校验后入队。 */
    @PostMapping("/knowledge/move")
    public ResponseEntity<ApiResponse<Object>> moveKnowledge(
            @Valid @NonNullBody @RequestBody MoveKnowledgeRequest req) {
        String sourceKbId = LogSanitizer.sanitize(req.sourceKbId());
        String targetKbId = LogSanitizer.sanitize(req.targetKbId());
        String mode = req.mode();
        if (sourceKbId.equals(targetKbId)) {
            throw new BizException(AppError.badRequest("Source and target knowledge base cannot be the same"));
        }
        long callerTenant = KnowledgeRouteGuards.tenantId();
        if (callerTenant == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        TenantAPIKeyScope.authorizeKnowledgeBases(
                List.of(sourceKbId, targetKbId));
        // 源库：存在性（404 大写 S）→ 租户（403）→ ownership（信封）；目标库同链
        KnowledgeBase sourceKb = knowledgeService.findKb(sourceKbId);
        if (sourceKb == null) {
            throw new BizException(AppError.notFound("Source knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            throw new BizException(AppError.forbidden("No permission to access source knowledge base"));
        }
        guards.batchAccessChecks(sourceKb.getId());
        KnowledgeBase targetKb = knowledgeService.findKb(targetKbId);
        if (targetKb == null) {
            throw new BizException(AppError.notFound("Target knowledge base not found"));
        }
        if (targetKb.getTenantId() == null || targetKb.getTenantId() != callerTenant) {
            throw new BizException(AppError.forbidden("No permission to access target knowledge base"));
        }
        guards.batchAccessChecks(targetKb.getId());
        try {
            KnowledgeService.validateKBTransferCompatibility(sourceKb, targetKb, mode);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // dedupe + 空白拒绝 + 归属/状态校验（两句话前后依赖，不能合并）
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String id : req.knowledgeIds()) {
            if (id.trim().isEmpty()) {
                throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
            }
            seen.add(id);
        }
        List<String> uniqueIds = new ArrayList<>(seen);
        for (String kId : uniqueIds) {
            Knowledge k = knowledgeService.getKnowledgeInTenant(callerTenant, kId);
            if (k == null) {
                throw new BizException(AppError.badRequest("Knowledge item " + kId + " not found"));
            }
            if (!sourceKbId.equals(k.getKnowledgeBaseId())) {
                throw new BizException(AppError.badRequest(
                        "Knowledge item " + kId + " does not belong to the source knowledge base"));
            }
            if (!Knowledge.PARSE_COMPLETED.equals(k.getParseStatus())) {
                throw new BizException(AppError.badRequest("Knowledge item " + kId
                        + " is not in completed status (current: " + k.getParseStatus() + ")"));
            }
        }
        String taskId = KnowledgeTaskIds.generateTaskId("kg_move", callerTenant, sourceKbId);
        knowledgeService.startKnowledgeMove(callerTenant, taskId, uniqueIds, sourceKbId, targetKbId, mode);
        var resp = new MoveKnowledgeResponse(
                taskId, sourceKbId, targetKbId, uniqueIds.size(), "Knowledge move task started");
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    @GetMapping("/knowledge/move/progress/{taskId}")
    public ResponseEntity<ApiResponse<Object>> getKnowledgeMoveProgress(
            @PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Task ID cannot be empty"));
        }
        guards.requireTaskProgressTenant(taskId);
        var progress = knowledgeService.getKnowledgeMoveProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("Knowledge move task not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(progress));
    }
}
