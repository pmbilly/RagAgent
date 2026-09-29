package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.CopyKbRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.HybridSearchRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.RebuildIndexResponse;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.UpdateKbRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.CreateKbRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseResponse;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.retrieval.HybridSearchService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.CopyKnowledgeBaseResponse;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse;
import com.ragagent.knowledge.service.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.storageurl.PublicModeForbiddenException;
import com.ragagent.storageurl.ResourceModeException;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.knowledge.service.KnowledgeTaskIds;
import com.ragagent.storageurl.Mode;

/**
 * 知识库 CRUD 与检索入口：列表/详情/更新/删除、置顶、移动目标、混合检索
 * （POST+GET 双路由）、复制/副本/重建索引与复制进度。
 *
 * <p><b>守卫顺序</b>（契约样例依赖，不能重排）：hybrid-search/duplicate 的路由带
 * KBAccessRead（{@code guard.requireKbAccess}）；copy 的源/目标在 body，handler 内
 * {@link #resolveHandlerKbAccess}——跨租户 403 文案与 move 的 handler 检查刻意不同。</p>
 *
 * <p><b>响应契约</b>（见 {@code docs/knowledge-api-contract-v1.md}）：知识库对象统一由
 * {@link KnowledgeBaseResponse} 输出（camelCase、可空字段显式 null、内部字段不下发）；
 * 成功响应不再包 {@code {data, success}} 信封——单资源直接返回对象、列表直接返回数组，
 * 删除返回 204。</p>
 *
 * <p><b>待办</b>：create 仍把请求体直接绑定 {@link KnowledgeBase} 实体（含 legacy cos_config
 * 兼容），待"请求侧 DTO 化"批次改为独立请求 DTO。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final KnowledgeAccessGuard guard;
    private final HybridSearchService hybridSearchService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   KnowledgeService knowledgeService,
                                   KnowledgeAccessGuard guard,
                                   HybridSearchService hybridSearchService) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.guard = guard;
        this.hybridSearchService = hybridSearchService;
    }

    /** 创建知识库：请求体字段全部可选（缺省由服务层默认值链补齐）。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> createKnowledgeBase(
            @RequestBody(required = false) CreateKbRequest request) {
        log.info("Start creating knowledge base");
        KnowledgeBase kb = kbService.createKnowledgeBase(
                request == null ? CreateKbRequest.empty().toEntity() : request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeBaseResponse.from(kb, kbService.retrieveDriver()));
    }

    /** 列表；KB 受限的 API Key 只看得到白名单内的库（数据面收口）。 */
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> listKnowledgeBases(
            @RequestParam(value = "creator", required = false) String creator) {
        log.info("Start listing knowledge bases");
        List<KnowledgeBase> kbs = kbService.listKnowledgeBases(creator);
        var scope = APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            kbs = kbs.stream().filter(kb -> scope.allowsKnowledgeBase(kb.getId())).toList();
        }
        String driver = kbService.retrieveDriver();
        return ResponseEntity.ok(kbs.stream().map(kb -> KnowledgeBaseResponse.from(kb, driver)).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.getKnowledgeBase(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(kb, kbService.retrieveDriver()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> updateKnowledgeBase(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateKbRequest req) {
        log.info("Start updating knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (req.name() != null && req.name().isEmpty()) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("name: 不能为空"));
        }
        existing = kbService.updateKnowledgeBase(existing, req.name(), req.description(), req.config());
        return ResponseEntity.ok(KnowledgeBaseResponse.from(existing, kbService.retrieveDriver()));
    }

    /** 创建者本人或 Admin+，否则 403（不泄漏存在性）。 */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    /** 删除知识库；成功返回 204（无响应体，见契约文档 §1.13）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start deleting knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can delete"));
        }
        kbService.deleteKnowledgeBase(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/pin")
    public ResponseEntity<KnowledgeBaseResponse> togglePin(@PathVariable("id") String id) {
        log.info("Start toggling pin for knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.togglePin(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(kb, kbService.retrieveDriver()));
    }

    @GetMapping("/{id}/move-targets")
    public ResponseEntity<List<KnowledgeBaseResponse>> listMoveTargets(@PathVariable("id") String id) {
        log.info("Start listing move targets, ID: {}", id);
        List<KnowledgeBase> targets = kbService.listMoveTargets(id);
        String driver = kbService.retrieveDriver();
        return ResponseEntity.ok(targets.stream().map(kb -> KnowledgeBaseResponse.from(kb, driver)).toList());
    }

    // ── hybrid-search / copy / duplicate / rebuild-index / copy progress ──

    /** POST 与 GET 双路由同一 handler（GET 带 JSON body 兼容 #1727）。 */
    @PostMapping("/{id}/hybrid-search")
    public ResponseEntity<KnowledgeBaseDtos.HybridSearchResponse> hybridSearchPost(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody HybridSearchRequest req,
            @RequestParam(value = "resource_urls", required = false) String resourceUrls) {
        return hybridSearch(id, req, resourceUrls);
    }

    @GetMapping("/{id}/hybrid-search")
    public ResponseEntity<KnowledgeBaseDtos.HybridSearchResponse> hybridSearchGet(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody HybridSearchRequest req,
            @RequestParam(value = "resource_urls", required = false) String resourceUrls) {
        return hybridSearch(id, req, resourceUrls);
    }

    private ResponseEntity<KnowledgeBaseDtos.HybridSearchResponse> hybridSearch(String id, HybridSearchRequest req,
            String resourceUrls) {
        log.info("Start hybrid search");
        KnowledgeBase kb = guard.requireKbAccess(id);
        boolean precomputedVectorOnly = req.queryEmbedding() != null
                && req.queryEmbedding().length > 0
                && Boolean.TRUE.equals(req.disableKeywordsMatch())
                && !Boolean.TRUE.equals(req.disableVectorMatch());
        if ((req.queryText() == null || req.queryText().trim().isEmpty()) && !precomputedVectorOnly) {
            throw new BizException(AppError.badRequest("query_text is required"));
        }
        // resource_urls：public 拒绝 → 403；其他坏值 → 400
        try {
            Mode.resolve(resourceUrls);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.forbidden(e.getMessage()));
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // 多库 scope：knowledge_base_ids 优先，缺省单库；API-Key 白名单 + 租户归属校验
        List<String> searchKbIds = req.knowledgeBaseIds() != null && !req.knowledgeBaseIds().isEmpty()
                ? new ArrayList<>(req.knowledgeBaseIds())
                : List.of(kb.getId());
        TenantAPIKeyScope.authorizeKnowledgeBases(searchKbIds);
        List<KnowledgeBase> kbs = new ArrayList<>();
        for (String kbId : searchKbIds) {
            KnowledgeBase row = kbService.getAllTenantById(kbId);
            if (row != null) {
                kbs.add(row);
            }
        }
        if (kbs.isEmpty()) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        // 检索面只认本租户 KB（跨租户 org-share 授权链已退役）
        Long caller = TenantContext.currentTenantId();
        for (KnowledgeBase row : kbs) {
            if (row.getTenantId() == null || !row.getTenantId().equals(caller)) {
                throw new BizException(AppError.notFound("knowledge base not found"));
            }
        }
        boolean primaryFound = kbs.stream().anyMatch(row -> row.getId().equals(kb.getId()));
        if (!primaryFound) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        // 检索执行：pgvector + ParadeDB BM25 + RRF + FAQ 后处理 + 富化装配
        SearchParams params = new SearchParams();
        params.setQueryText(req.queryText() == null ? "" : req.queryText());
        if (req.queryEmbedding() != null && req.queryEmbedding().length > 0) {
            params.setQueryEmbedding(req.queryEmbedding());
        }
        params.setVectorThreshold(req.vectorThreshold() == null ? 0.0 : req.vectorThreshold());
        params.setKeywordThreshold(req.keywordThreshold() == null ? 0.0 : req.keywordThreshold());
        params.setMatchCount(req.matchCount() == null ? 0 : req.matchCount());
        params.setDisableKeywordsMatch(Boolean.TRUE.equals(req.disableKeywordsMatch()));
        params.setDisableVectorMatch(Boolean.TRUE.equals(req.disableVectorMatch()));
        params.setSkipContextEnrichment(Boolean.TRUE.equals(req.skipContextEnrichment()));
        params.setKnowledgeIds(req.knowledgeIds() == null ? List.of() : req.knowledgeIds());
        params.setTagIds(req.tagIds() == null ? List.of() : req.tagIds());
        params.setKnowledgeBaseIds(searchKbIds);
        List<SearchResult> results =
                hybridSearchService.hybridSearch(kb.getId(), params);
        return ResponseEntity.ok(new KnowledgeBaseDtos.HybridSearchResponse(results, true));
    }

    /** 复制知识库（源在 body）；target_id 缺省 = 创建新库。 */
    @PostMapping("/copy")
    public ResponseEntity<ApiResponse<Object>> copyKnowledgeBase(
            @Valid @NonNullBody @RequestBody CopyKbRequest req) {
        log.info("Start copying knowledge base");
        String sourceId = req.sourceId() == null ? "" : req.sourceId();
        String targetId = req.targetId() == null ? "" : req.targetId();
        String explicitTaskId = req.taskId() == null ? "" : req.taskId();
        long caller = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        if (caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        KnowledgeBase sourceKb = resolveHandlerKbAccess(sourceId);
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != caller) {
            throw new BizException(AppError.forbidden("No permission to copy this knowledge base"));
        }
        String taskId = explicitTaskId;
        if (taskId.isEmpty()) {
            taskId = KnowledgeTaskIds.generateTaskId("kb_clone", caller, sourceId);
        } else {
            requireTaskProgressTenant(taskId);
        }
        boolean create = targetId.isEmpty();
        KnowledgeBase targetKb = new KnowledgeBase();
        targetKb.setId(java.util.UUID.randomUUID().toString());
        targetKb.setTenantId(caller);
        String creatorId = "";
        if (create) {
            String uid = TenantContext.currentUserId();
            if (uid != null && !uid.startsWith("system-")) {
                targetKb.setCreatorId(uid);
                creatorId = uid;
            }
        } else {
            KnowledgeBase existing = resolveHandlerKbAccess(targetId);
            if (existing.getTenantId() == null || existing.getTenantId() != caller) {
                throw new BizException(AppError.forbidden("No permission to copy to this knowledge base"));
            }
            // 非创建者且非 Admin+ 拒绝替换内容
            String role = TenantContext.currentRole();
            boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
            if (!admin && (existing.getCreatorId() == null || existing.getCreatorId().isEmpty()
                    || !existing.getCreatorId().equals(TenantContext.currentUserId()))) {
                throw new BizException(AppError.forbidden("No permission to replace this knowledge base's contents"));
            }
            try {
                KnowledgeService.validateCloneCompatibility(sourceKb, existing);
            } catch (IllegalArgumentException e) {
                throw new BizException(AppError.badRequest(e.getMessage()));
            }
            targetKb = existing;
        }
        String reservedTargetId = targetKb.getId();
        knowledgeService.startKBClone(caller, taskId, sourceId, reservedTargetId, create, creatorId);
        var resp = new CopyKnowledgeBaseResponse(
                taskId, sourceId, reservedTargetId, "Knowledge base copy task started");
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    /** handler 内 KB 访问（Viewer 面）：API-Key 白名单 → 查行 → 租户归属；缺失 404、跨租户 403。 */
    private KnowledgeBase resolveHandlerKbAccess(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        Long caller = TenantContext.currentTenantId();
        if (kb.getTenantId() == null || caller == null || !kb.getTenantId().equals(caller)) {
            throw new BizException(AppError.forbidden("Permission denied to access this knowledge base"));
        }
        return kb;
    }

    @GetMapping("/copy/progress/{taskId}")
    public ResponseEntity<ApiResponse<Object>> getKBCloneProgress(@PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Task ID cannot be empty"));
        }
        requireTaskProgressTenant(taskId);
        var progress = knowledgeService.getKBCloneProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("KB clone task not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(progress));
    }

    /** 任务租户必须与调用方一致，否则 404（不泄露他租户任务存在性）。 */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenant = KnowledgeTaskIds.taskTenantId(taskId);
        if (taskTenant == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        Long caller = TenantContext.currentTenantId();
        if (caller == null || caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (!taskTenant.equals(caller)) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    /** 索引策略变更后对 KB 内全部知识重跑处理管线；前端读 {@code data.document_count}。 */
    @PostMapping("/{id}/rebuild-index")
    public ResponseEntity<ApiResponse<RebuildIndexResponse>> rebuildIndex(@PathVariable("id") String id) {
        log.info("Start rebuilding knowledge base index, ID: {}", id);
        String kbId = id == null ? "" : id;
        if (kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        guard.requireKbAccess(kbId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        KnowledgeBase sourceKb = kbService.getAllTenantById(kbId);
        if (sourceKb == null) {
            throw new BizException(AppError.notFound("Knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base rebuild rejected: belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to rebuild this knowledge base"));
        }
        int count = knowledgeService.rebuildKnowledgeBaseIndex(kbId);
        return ResponseEntity.ok(ApiResponse.ok(new RebuildIndexResponse(count)));
    }

    /** 同步克隆设置（名字带 " 副本"，重名去重）。 */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<ApiResponse<Object>> duplicateKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start duplicating knowledge base, ID: {}", id);
        String sourceId = id == null ? "" : id;
        if (sourceId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        guard.requireKbAccess(sourceId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        KnowledgeBase sourceKb = kbService.getAllTenantById(sourceId);
        if (sourceKb == null) {
            // 路由守卫已兜住缺失；此分支保留 handler 的 NotFound 文案
            throw new BizException(AppError.notFound("Source knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base duplicate rejected: source belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to duplicate this knowledge base"));
        }
        KnowledgeBase targetKb = knowledgeService.duplicateKnowledgeBase(sourceId);
        var resp = new DuplicateKnowledgeBaseResponse(
                sourceId, targetKb.getId(), "Knowledge base duplicate created",
                KnowledgeBaseResponse.from(targetKb, kbService.retrieveDriver()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp));
    }
}
