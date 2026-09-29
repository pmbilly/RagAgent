package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.MessageResponse;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.CopyKbRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.HybridSearchRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.RebuildIndexResponse;
import com.ragagent.knowledge.dto.KnowledgeBaseDtos.UpdateKbRequest;
import com.ragagent.knowledge.dto.KnowledgeBaseResponseBuilder;
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

/**
 * 知识库 CRUD 与检索入口：列表/详情/更新/删除、置顶、移动目标、混合检索
 * （POST+GET 双路由）、复制/副本/重建索引与复制进度。
 *
 * <p><b>守卫顺序</b>（golden 依赖，不能重排）：hybrid-search/duplicate 的路由带
 * KBAccessRead（{@code guard.requireKbAccess}）；copy 的源/目标在 body，handler 内
 * {@link #resolveHandlerKbAccess}——跨租户 403 文案与 move 的 handler 检查刻意不同。</p>
 *
 * <p>create 的请求体直接绑定 {@link KnowledgeBase} 实体（含 legacy cos_config 兼容）；
 * 其余写端点走 DTO。响应 data 经 {@link KnowledgeBaseResponseBuilder} 输出（键字母序）。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseService kbService;
    private final com.ragagent.knowledge.service.KnowledgeService knowledgeService;
    private final com.ragagent.knowledge.service.KnowledgeAccessGuard guard;
    private final HybridSearchService hybridSearchService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   com.ragagent.knowledge.service.KnowledgeService knowledgeService,
                                   com.ragagent.knowledge.service.KnowledgeAccessGuard guard,
                                   HybridSearchService hybridSearchService) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.guard = guard;
        this.hybridSearchService = hybridSearchService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> createKnowledgeBase(
            @RequestBody(required = false) KnowledgeBase rawBody) {
        log.info("Start creating knowledge base");
        KnowledgeBase kb = bindKnowledgeBase(rawBody);
        kb = kbService.createKnowledgeBase(kb);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 空体 = 全零值创建；legacy cos_config → storage_config；配置空值按 omitempty 语义归一。 */
    private KnowledgeBase bindKnowledgeBase(KnowledgeBase body) {
        if (body == null) {
            return new KnowledgeBase();
        }
        return normalizeConfigOmitEmpty(body);
    }

    /**
     * 配置空值归一（对齐 Go 的 omitempty 语义）：这些配置字段是 JsonNode 透传，
     * 不归一则前端编辑器发来的空串/空数组会原样存库并回显。keep 集合 = 无
     * omitempty 的标签（恒保留）；faq_config 两字段都无 omitempty → 不归一。
     */
    private static KnowledgeBase normalizeConfigOmitEmpty(KnowledgeBase kb) {
        kb.setExtractConfig(dropEmpty(kb.getExtractConfig(), Set.of("enabled")));
        kb.setWikiConfig(dropEmpty(kb.getWikiConfig(), Set.of("synthesis_model_id", "max_pages_per_ingest")));
        kb.setAutoTagConfig(dropEmpty(kb.getAutoTagConfig(), Set.of("enabled")));
        kb.setQuestionGenerationConfig(dropEmpty(kb.getQuestionGenerationConfig(),
                Set.of("enabled", "question_count")));
        return kb;
    }

    /** keep 之外的字段，值为 null/空串/0/空数组/空对象时剔除（bool 不剔——仅 *bool 的 false 保留）。 */
    private static JsonNode dropEmpty(JsonNode node, Set<String> keep) {
        if (node == null || !node.isObject()) {
            return node;
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;
        List<String> drop = new ArrayList<>();
        obj.fields().forEachRemaining(e -> {
            if (keep.contains(e.getKey())) {
                return;
            }
            JsonNode v = e.getValue();
            boolean empty = v == null || v.isNull()
                    || (v.isTextual() && v.asText().isEmpty())
                    || (v.isNumber() && v.numberValue().doubleValue() == 0d)
                    || (v.isContainerNode() && v.isEmpty());
            if (empty) {
                drop.add(e.getKey());
            }
        });
        drop.forEach(obj::remove);
        return obj;
    }

    /** 列表；KB 受限的 API Key 只看得到白名单内的库（数据面收口）。 */
    @GetMapping
    public ResponseEntity<ApiResponse<Object>> listKnowledgeBases(
            @RequestParam(value = "creator", required = false) String creator) {
        log.info("Start listing knowledge bases");
        List<KnowledgeBase> kbs = kbService.listKnowledgeBases(creator);
        var scope = com.ragagent.apikey.domain.APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            kbs = kbs.stream().filter(kb -> scope.allowsKnowledgeBase(kb.getId())).toList();
        }
        List<Object> data = new ArrayList<>(kbs.size());
        for (KnowledgeBase kb : kbs) {
            data.add(KnowledgeBaseResponseBuilder.buildListItem(kb, kbService.retrieveDriver()));
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> getKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.getKnowledgeBase(id);
        return ResponseEntity.ok(ApiResponse.ok(
                KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> updateKnowledgeBase(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateKbRequest req) {
        log.info("Start updating knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (req.name() != null && req.name().isEmpty()) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("name: 不能为空"));
        }
        existing = kbService.updateKnowledgeBase(existing, req.name(), req.description(), req.config());
        return ResponseEntity.ok(ApiResponse.ok(
                KnowledgeBaseResponseBuilder.build(existing, kbService.retrieveDriver())));
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

    @DeleteMapping("/{id}")
    public ResponseEntity<MessageResponse> deleteKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start deleting knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can delete"));
        }
        kbService.deleteKnowledgeBase(id);
        return ResponseEntity.ok(new MessageResponse("Knowledge base deleted successfully", true));
    }

    @PutMapping("/{id}/pin")
    public ResponseEntity<ApiResponse<Object>> togglePin(@PathVariable("id") String id) {
        log.info("Start toggling pin for knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.togglePin(id);
        return ResponseEntity.ok(ApiResponse.ok(
                KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    @GetMapping("/{id}/move-targets")
    public ResponseEntity<ApiResponse<Object>> listMoveTargets(@PathVariable("id") String id) {
        log.info("Start listing move targets, ID: {}", id);
        List<KnowledgeBase> targets = kbService.listMoveTargets(id);
        List<Object> data = new ArrayList<>(targets.size());
        for (KnowledgeBase kb : targets) {
            data.add(KnowledgeBaseResponseBuilder.buildRaw(kb));
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
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
            com.ragagent.storageurl.Mode.resolve(resourceUrls);
        } catch (com.ragagent.storageurl.PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.forbidden(e.getMessage()));
        } catch (com.ragagent.storageurl.ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // 多库 scope：knowledge_base_ids 优先，缺省单库；API-Key 白名单 + 租户归属校验
        List<String> searchKbIds = req.knowledgeBaseIds() != null && !req.knowledgeBaseIds().isEmpty()
                ? new ArrayList<>(req.knowledgeBaseIds())
                : List.of(kb.getId());
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(searchKbIds);
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
        com.ragagent.chatpipeline.SearchParams params = new com.ragagent.chatpipeline.SearchParams();
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
        List<com.ragagent.retrieval.domain.SearchResult> results =
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
            taskId = com.ragagent.knowledge.service.KnowledgeTaskIds.generateTaskId("kb_clone", caller, sourceId);
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
                com.ragagent.knowledge.service.KnowledgeService.validateCloneCompatibility(sourceKb, existing);
            } catch (IllegalArgumentException e) {
                throw new BizException(AppError.badRequest(e.getMessage()));
            }
            targetKb = existing;
        }
        String reservedTargetId = targetKb.getId();
        knowledgeService.startKBClone(caller, taskId, sourceId, reservedTargetId, create, creatorId);
        var resp = new com.ragagent.knowledge.dto.KnowledgeTaskDtos.CopyKnowledgeBaseResponse(
                taskId, sourceId, reservedTargetId, "Knowledge base copy task started");
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    /** handler 内 KB 访问（Viewer 面）：API-Key 白名单 → 查行 → 租户归属；缺失 404、跨租户 403。 */
    private KnowledgeBase resolveHandlerKbAccess(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));
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
        Long taskTenant = com.ragagent.knowledge.service.KnowledgeTaskIds.taskTenantId(taskId);
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
        var resp = new com.ragagent.knowledge.dto.KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse(
                sourceId, targetKb.getId(), "Knowledge base duplicate created",
                // env 默认 store → buildKBResponse 不写 vector_store_engine_type 键（golden 钉住）
                KnowledgeBaseResponseBuilder.build(targetKb, kbService.retrieveDriver(), false));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp));
    }
}
