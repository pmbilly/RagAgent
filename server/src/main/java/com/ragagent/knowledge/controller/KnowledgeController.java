package com.ragagent.knowledge.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.web.ContentTypeByFilename;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.LocalStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对照 Go internal/handler/knowledge.go（阶段 3 子集 + 波 2 文档操作面 15 条路由）。
 * 知识实体直接序列化（struct 声明序，@JsonPropertyOrder 已锁定）；
 * 重复文档 409 为特殊信封（code/data/message/success），不走 error_handler。
 *
 * <p><b>波 2 路由的守卫链在控制器内复刻</b>（Go 挂在路由上，语义见
 * {@link KnowledgeAccessGuard}）：读 = knowledge 缺失(404 大写 K) → KB 访问
 * (404 小写 k / 403 信封) → handler；带 OwnedKnowledgeKBOrAdmin 的写路由在其前再插
 * ownership（缺失放行 / 非本人 403 纯字符串）。批处理路由（body 携带 kb_id）的
 * requireKnowledgeWriteAccess = KB 访问 → ownership（信封形态）。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;
    private final KnowledgeAccessGuard guard;
    private final SsrfGuard ssrfGuard;
    private final com.ragagent.knowledge.service.SharedAgentAccessResolver sharedAgentAccess;
    private final com.ragagent.knowledge.service.KnowledgeBaseService kbService;
    private final com.ragagent.org.service.KbShareService kbShareService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               KnowledgeAccessGuard guard,
                               SsrfGuard ssrfGuard,
                               com.ragagent.knowledge.service.SharedAgentAccessResolver sharedAgentAccess,
                               com.ragagent.knowledge.service.KnowledgeBaseService kbService,
                               com.ragagent.org.service.KbShareService kbShareService) {
        this.knowledgeService = knowledgeService;
        this.guard = guard;
        this.ssrfGuard = ssrfGuard;
        this.sharedAgentAccess = sharedAgentAccess;
        this.kbService = kbService;
        this.kbShareService = kbShareService;
    }

    /** 对照 CreateKnowledgeFromFile — multipart（字段名严格对照 Go：file/fileName/metadata/tag_ids/channel/process_config） */
    @PostMapping("/knowledge-bases/{id}/knowledge/file")
    public ResponseEntity<?> createFromFile(@PathVariable("id") String kbId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "fileName", required = false) String fileName,
                                            @RequestParam(value = "metadata", required = false) String metadataJson,
                                            @RequestParam(value = "tag_ids", required = false) String tagIds,
                                            @RequestParam(value = "channel", required = false) String channel)
            throws IOException {
        log.info("Start creating knowledge from file, KB: {}", kbId);
        JsonNode customMetadata = parseJsonParam(metadataJson, "metadata");
        byte[] content = LocalStorageService.readAll(file.getInputStream());
        try {
            Knowledge k = knowledgeService.createFromFile(
                    kbId, content,
                    fileName != null && !fileName.isEmpty() ? fileName : file.getOriginalFilename(),
                    fileName, customMetadata, channel);
            return ResponseEntity.ok(envelope(k));
        } catch (KnowledgeService.DuplicateKnowledgeException e) {
            return duplicateResponse(e);
        }
    }

    /** 对照 CreateKnowledgeFromURL — 201；SSRF 校验（对照 handler L88 语义） */
    @PostMapping("/knowledge-bases/{id}/knowledge/url")
    public ResponseEntity<?> createFromUrl(@PathVariable("id") String kbId,
                                           @RequestBody(required = false) String rawBody) {
        log.info("Start creating knowledge from URL, KB: {}", kbId);
        JsonNode body = parseBody(rawBody, true);
        String url = body.path("url").asText("");
        if (url.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'CreateKnowledgeFromURLRequest.Url' Error:Field validation for 'Url' failed on the 'required' tag"));
        }
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (SsrfGuard.SsrfException e) {
            throw new BizException(AppError.badRequest(ssrfGuard.formatSSRFError("URL", url, e)));
        }
        try {
            Knowledge k = knowledgeService.createFromUrl(kbId, url,
                    textOrNull(body, "file_name"), textOrNull(body, "file_type"),
                    textOrNull(body, "title"), textOrNull(body, "channel"));
            return ResponseEntity.status(HttpStatus.CREATED).body(envelope(k));
        } catch (KnowledgeService.DuplicateKnowledgeException e) {
            return duplicateResponse(e);
        }
    }

    /** 对照 CreateManualKnowledge */
    @PostMapping("/knowledge-bases/{id}/knowledge/manual")
    public ResponseEntity<?> createManual(@PathVariable("id") String kbId,
                                          @RequestBody(required = false) String rawBody) {
        log.info("Start creating manual knowledge, KB: {}", kbId);
        JsonNode body = parseBody(rawBody, false);
        String title = body.path("title").asText("");
        if (title.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'ManualKnowledgePayload.Title' Error:Field validation for 'Title' failed on the 'required' tag"));
        }
        Knowledge k = knowledgeService.createManual(kbId, title,
                body.path("content").asText(""),
                body.path("status").asText(""),
                textOrNull(body, "channel"));
        return ResponseEntity.ok(envelope(k));
    }

    /** 对照 ListKnowledge — 真分页，顶层 data/page/page_size/total/success（字母序） */
    @GetMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<?> listKnowledge(@PathVariable("id") String kbId,
                                           @RequestParam(value = "page", defaultValue = "1") long page,
                                           @RequestParam(value = "page_size", defaultValue = "20") long pageSize,
                                           @RequestParam(value = "keyword", required = false) String keyword,
                                           @RequestParam(value = "parse_status", required = false) String parseStatus,
                                           @RequestParam(value = "file_type", required = false) String fileType,
                                           @RequestParam(value = "folder_path", required = false) String folderPath) {
        log.info("Start listing knowledge, KB: {}", kbId);
        if (page < 1) {
            throw new BizException(AppError.badRequest("page must be at least 1"));
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new BizException(AppError.badRequest("page_size must be between 1 and 1000"));
        }
        boolean folderPresent = folderPath != null;
        Page<Knowledge> result = knowledgeService.listKnowledge(
                kbId, page, pageSize, keyword, parseStatus, fileType, folderPath, folderPresent);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.getRecords());
        body.put("page", page);
        body.put("page_size", pageSize);
        body.put("success", true);
        body.put("total", result.getTotal());
        return ResponseEntity.ok(body);
    }

    /** 对照 ListKnowledgeFolders */
    @GetMapping("/knowledge-bases/{id}/knowledge/folders")
    public ResponseEntity<?> listFolders(@PathVariable("id") String kbId) {
        return ResponseEntity.ok(envelope(knowledgeService.folderTree(kbId)));
    }

    /** 对照 GetKnowledge：路由带 KBAccessReadFromKnowledgeIDParam（守卫链同款分层） */
    @GetMapping("/knowledge/{id}")
    public ResponseEntity<?> getKnowledge(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge, ID: {}", id);
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, false);
        return ResponseEntity.ok(envelope(knowledgeService.getKnowledge(safeId)));
    }

    /**
     * 对照 GetKnowledgeBatch（GET /knowledge/batch）。query 绑定：ids required
     * （"ids=" → [""] 通过 binding，服务层查不到行 → data:[]）；agent_id 分支
     * （W5α 收口）：共享 agent 解析 → scope 空短路 → 有效租户取数 → scope 过滤。
     */
    @GetMapping("/knowledge/batch")
    public ResponseEntity<?> getKnowledgeBatch(
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "kb_id", required = false) String kbId,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "agent_source_tenant_id", required = false) String agentSourceTenantId) {
        long callerTenant = tenantId();
        if (callerTenant == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        // gin form 绑定顺序：uint64 字段的 strconv 映射错误先于 validator 的 required
        //（映射失败 → "Invalid request parameters" + ParseUint 原文 details，golden 钉住）
        String trimmedSource = agentSourceTenantId == null ? "" : agentSourceTenantId.trim();
        if (!trimmedSource.isEmpty()) {
            if (!trimmedSource.matches("\\d+")) {
                throw new BizException(AppError.badRequest("Invalid request parameters")
                        .withDetails("strconv.ParseUint: parsing \"" + trimmedSource + "\": invalid syntax"));
            }
            try {
                Long.parseLong(trimmedSource);
            } catch (NumberFormatException e) {
                throw new BizException(AppError.badRequest("Invalid request parameters")
                        .withDetails("strconv.ParseUint: parsing \"" + trimmedSource + "\": value out of range"));
            }
        }
        if (ids == null) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails("Key: 'GetKnowledgeBatchRequest.IDs' Error:Field validation for "
                            + "'IDs' failed on the 'required' tag"));
        }
        String safeAgent = LogSanitizer.sanitize(agentId == null ? "" : agentId);
        // agent 共享分支（W5α 收口，Go L1604-1624）：解析 → scope 空短路 → 有效租户切换
        com.ragagent.org.service.SharedAgentKBScope agentScope = null;
        long effectiveTenant = callerTenant;
        if (!safeAgent.isEmpty()) {
            com.ragagent.org.domain.AgentRow agent =
                    sharedAgentAccess.resolveForRequest(safeAgent, agentSourceTenantId);
            agentScope = com.ragagent.org.service.SharedAgentKBScope.from(agent);
            effectiveTenant = agent.getTenantId();
            if (agentScope.isEmpty()) {
                return ResponseEntity.ok(envelope(new ArrayList<>()));
            }
        }
        List<Knowledge> knowledges;
        if (LogSanitizer.sanitize(kbId == null ? "" : kbId).isEmpty()) {
            if (agentScope != null) {
                // 对照 agentScope 无 kb_id 分支：保持已授权 agent 的读范围与原始调用方
                knowledges = knowledgeService.getKnowledgeBatch(effectiveTenant, ids);
            } else {
                knowledges = knowledgeService.getKnowledgeBatchWithSharedAccess(callerTenant, ids);
            }
        } else {
            String safeKbId = LogSanitizer.sanitize(kbId);
            long effID;
            if (agentScope == null) {
                guard.requireKbAccess(safeKbId);
                effID = callerTenant;
            } else {
                effID = resolveKbAccessForAgentScope(safeKbId, callerTenant, agentScope);
            }
            knowledges = knowledgeService.getKnowledgeBatch(effID, ids);
            // scopeKBID 过滤（对照 allowedKBSet= {kb_id}）
            knowledges = knowledges.stream()
                    .filter(k -> safeKbId.equals(k.getKnowledgeBaseId())).toList();
        }
        if (agentScope != null) {
            // 对照 filterKnowledgeByAgentScope（agent 分支恒过滤，与 kb_id 无关）
            final com.ragagent.org.service.SharedAgentKBScope scope = agentScope;
            knowledges = com.ragagent.knowledge.service.SharedAgentAccessResolver
                    .filterKnowledgeByAgentScope(knowledges, scope,
                            Knowledge::getKnowledgeBaseId, Knowledge::getTenantId);
        }
        return ResponseEntity.ok(envelope(knowledges));
    }

    /**
     * 对照 validateKnowledgeBaseAccessWithKBID × access.ResolveKB（agent 流程，
     * required=Viewer，W5α）：授予顺序 own → org-share → agent-scope；无授予 →
     * 403 "Permission denied to access this knowledge base"；授予后 scope 校验失败 →
     * 403 "Knowledge base not accessible through this agent"。返回 effID（恒 = KB 的
     * owner 租户，对照 grant.EffectiveTenantID）。
     */
    private long resolveKbAccessForAgentScope(String safeKbId, long callerTenant,
                                              com.ragagent.org.service.SharedAgentKBScope agentScope) {
        // 对照 requireTenantAPIKeyKnowledgeBase：先于 KB 加载
        var keyScope = com.ragagent.apikey.domain.APIKeyScopeContext.current();
        if (keyScope != null && keyScope.isKnowledgeBaseRestricted()
                && !keyScope.allowsKnowledgeBase(safeKbId)) {
            throw new BizException(AppError.forbidden(
                    "API key scope does not allow one or more knowledge bases"));
        }
        KnowledgeBase kb = kbShareService.kbById(safeKbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        long kbTenant = kb.getTenantId() == null ? 0 : kb.getTenantId();
        boolean granted = kbTenant == callerTenant;
        if (!granted) {
            granted = kbShareService.checkTenantKBPermission(safeKbId, callerTenant,
                    com.ragagent.org.service.OrganizationService.callerTenantRole())
                    .permits("viewer");
        }
        if (!granted) {
            granted = agentScope.allows(safeKbId, kbTenant);
        }
        if (!granted) {
            throw new BizException(AppError.forbidden(
                    "Permission denied to access this knowledge base"));
        }
        if (!agentScope.allows(safeKbId, kbTenant)) {
            throw new BizException(AppError.forbidden(
                    "Knowledge base not accessible through this agent"));
        }
        return kbTenant;
    }

    /** 对照 GetKnowledgeSpans（/stages 与 /spans 两个路径同 handler；gin.H 键字母序） */
    @GetMapping({"/knowledge/{id}/stages", "/knowledge/{id}/spans"})
    public ResponseEntity<?> getKnowledgeSpans(@PathVariable("id") String id,
                                               @RequestParam(value = "attempt", required = false) String attempt) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, false);
        Knowledge knowledge = knowledgeService.getKnowledge(safeId);
        int requestedAttempt = 0;
        if (attempt != null && !attempt.trim().isEmpty()) {
            try {
                int n = Integer.parseInt(attempt.trim());
                if (n > 0) {
                    requestedAttempt = n;
                }
            } catch (NumberFormatException ignored) {
                // 对照 strconv.Atoi 失败 → requestedAttempt 保持 0
            }
        }
        JsonNode data = knowledgeService.knowledgeSpans(knowledge, requestedAttempt);
        return ResponseEntity.ok(envelope(data));
    }

    /** 对照 RegenerateKnowledgeSummary：ownership + KBAccessWrite + service 确定性分支 */
    @PostMapping("/knowledge/{id}/regenerate-summary")
    public ResponseEntity<?> regenerateKnowledgeSummary(@PathVariable("id") String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        Knowledge knowledge = resolveKnowledgeByGuard(safeId, true);
        if (knowledge.getSummaryStatus() == null || knowledge.getSummaryStatus().isEmpty()
                || "none".equals(knowledge.getSummaryStatus())) {
            knowledgeService.regenerateKnowledgeSummary(safeId);
        } else {
            knowledgeService.requestKnowledgeSummaryRefresh(safeId);
        }
        // service 对无 summary model 的部署恒抛 400（golden 分支）；LLM 生成随阶段 7，
        // 走到此处即部署差异形态（与 chunk 模块 Regenerate 的降级同款文案）
        throw new BizException(AppError.internal("summary model is not available in this deployment"));
    }

    /** 对照 UpdateManualKnowledge：ownership + KBAccessWrite + 手工内容校验 */
    @PutMapping("/knowledge/manual/{id}")
    public ResponseEntity<?> updateManualKnowledge(@PathVariable("id") String id,
                                                   @RequestBody(required = false) String rawBody) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        JsonNode body = bindRequiredBody(rawBody);
        Knowledge k = knowledgeService.updateManualKnowledge(safeId,
                textOrNull(body, "title"), textOrNull(body, "content"),
                textOrNull(body, "status"), textOrNull(body, "channel"));
        return ResponseEntity.ok(envelope(k));
    }

    /** 对照 ReparseKnowledge：ownership + KBAccessWrite；空 body 保留上传时配置 */
    @PostMapping("/knowledge/{id}/reparse")
    public ResponseEntity<?> reparseKnowledge(@PathVariable("id") String id,
                                              @RequestBody(required = false) String rawBody) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                MAPPER.readTree(rawBody);
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("Invalid reparse request body")
                        .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
            }
        }
        Knowledge k = knowledgeService.reparseKnowledge(safeId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", k);
        resp.put("message", "Knowledge reparse task submitted");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 CancelKnowledgeParse：ownership + KBAccessWrite + 状态机（幂等/拒绝） */
    @PostMapping("/knowledge/{id}/cancel-parse")
    public ResponseEntity<?> cancelKnowledgeParse(@PathVariable("id") String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        Knowledge k = knowledgeService.cancelKnowledgeParse(safeId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", k);
        resp.put("message", "Knowledge parse cancelled");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 DownloadKnowledgeFile：Contributor 路由门 + KBAccessWrite + handler 内 Editor 检查 */
    @GetMapping("/knowledge/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeFile(@PathVariable("id") String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, false, true);
        KnowledgeService.KnowledgeFile file = knowledgeService.getKnowledgeFile(safeId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Description", "File Transfer");
        headers.put("Content-Transfer-Encoding", "binary");
        headers.put("Expires", "0");
        return serveFile(file, "application/octet-stream", false, !file.manual(), headers);
    }

    /** 对照 PreviewKnowledgeFile：Viewer + KBAccessRead，Content-Type 按扩展名 */
    @GetMapping("/knowledge/{id}/preview")
    public ResponseEntity<byte[]> previewKnowledgeFile(@PathVariable("id") String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, false);
        KnowledgeService.KnowledgeFile file = knowledgeService.getKnowledgeFile(safeId);
        ContentTypeByFilename.Record safe = ContentTypeByFilename.safe(file.filename());
        return serveFile(file, safe.contentType(), safe.inline(), !file.manual(), new LinkedHashMap<>());
    }

    /** 对照 UpdateImageInfo：ownership + KBAccessWrite + image_info 解析/归属/向量分支 */
    @PutMapping("/knowledge/image/{id}/{chunkId}")
    public ResponseEntity<?> updateImageInfo(@PathVariable("id") String id,
                                             @PathVariable("chunkId") String chunkId,
                                             @RequestBody(required = false) String rawBody) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        String safeChunkId = LogSanitizer.sanitize(chunkId);
        if (safeChunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        JsonNode body = bindRequiredBody(rawBody);
        String imageInfo = body.hasNonNull("image_info") ? body.get("image_info").asText() : "";
        knowledgeService.updateImageInfo(safeId, safeChunkId, imageInfo);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", "Knowledge chunk image updated successfully");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 UpdateKnowledgeTagBatch：body 携带可选 kb_id；无 kb_id 时从首条 knowledge 推导 */
    @PutMapping("/knowledge/tags")
    public ResponseEntity<?> updateKnowledgeTagBatch(@RequestBody(required = false) String rawBody) {
        if (tenantId() == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        JsonNode body = bindTagBody(rawBody);
        JsonNode updates = body.get("updates");
        String kbId = LogSanitizer.sanitize(textOrEmpty(body, "kb_id"));
        String authorizedKbId;
        if (!kbId.isEmpty()) {
            guard.requireKbAccess(kbId);
            authorizedKbId = kbId;
        } else {
            // updates 非空（min=1 已保证）：取首条 knowledge 推导授权 KB
            //（Go 是 map 迭代取"第一个"，单键场景下确定性一致）。
            // 这条路径没有路由级 KBAccess 中间件 → 跨租户落在 handler 层，
            // 文案是 "Permission denied to access this knowledge"（不带 base，golden 钉住）
            String firstKnowledgeId = updates.fieldNames().next();
            Knowledge k = resolveKnowledgeHandlerLevel(LogSanitizer.sanitize(firstKnowledgeId), true);
            authorizedKbId = k.getKnowledgeBaseId();
        }
        // 对照 requireKBOwnershipOrAdmin(authorizedKBID)：KB 缺失 → 404 信封；非本人 → 403 信封
        guard.requireKbOwnershipOrAdminEnvelope(knowledgeService.requireKb(authorizedKbId));
        Map<String, List<String>> updateMap = new LinkedHashMap<>();
        updates.fields().forEachRemaining(e -> {
            List<String> tagIds = new ArrayList<>();
            e.getValue().forEach(n -> tagIds.add(n.asText()));
            updateMap.put(e.getKey(), tagIds);
        });
        knowledgeService.updateKnowledgeTagBatch(authorizedKbId, updateMap);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 BatchDeleteKnowledge：dedupe/maxBatch → KB 访问+ownership → 行校验（含
     *  RejectMoving）→ task_id。Go 异步清理，Java 同步软删（契约一致）。 */
    @PostMapping("/knowledge/batch-delete")
    public ResponseEntity<?> batchDeleteKnowledge(@RequestBody(required = false) String rawBody) {
        JsonNode body = bindBatchBody(rawBody);
        requireField(body, "kb_id", "BatchDeleteKnowledgeRequest", "KBID");
        requireField(body, "ids", "BatchDeleteKnowledgeRequest", "IDs");
        List<String> ids = requireBatchIds(body.get("ids"), "ids");
        batchAccessChecks(LogSanitizer.sanitize(textOrEmpty(body, "kb_id")));
        String kbId = LogSanitizer.sanitize(textOrEmpty(body, "kb_id"));
        // 对照 handler 的单批校验：count 不符 → "One or more..."；逐行 RejectMoving → 跨 KB
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("One or more knowledge entries not found"));
        }
        for (Knowledge k : rows) {
            rejectMoving(k);
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
        String taskId = knowledgeService.batchDeleteKnowledge(kbId, ids);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted_count", ids.size());
        data.put("task_id", taskId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("message", "Batch delete task submitted");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 BatchReparseKnowledge：bind 失败固定文案；行校验的 count 文案与 batch-delete 不同。 */
    @PostMapping("/knowledge/batch-reparse")
    public ResponseEntity<?> batchReparseKnowledge(@RequestBody(required = false) String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("invalid batch reparse knowledge request parameters"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("invalid batch reparse knowledge request parameters"));
        }
        if (body == null || !body.isObject() || !body.hasNonNull("kb_id")
                || textOrEmpty(body, "kb_id").isEmpty() || !body.has("ids") || !body.get("ids").isArray()) {
            throw new BizException(AppError.badRequest("invalid batch reparse knowledge request parameters"));
        }
        List<String> ids = dedupeIds(body.get("ids"));
        if (ids.isEmpty()) {
            throw new BizException(AppError.badRequest("no knowledge IDs provided for batch reparse"));
        }
        if (ids.size() > 200) {
            throw new BizException(AppError.badRequest("too many ids (max 200 per batch)"));
        }
        String kbId = LogSanitizer.sanitize(textOrEmpty(body, "kb_id"));
        batchAccessChecks(kbId);
        // 对照 handler：count 不符 → "some knowledge entries were not found"；逐行 RejectMoving → 跨 KB
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("some knowledge entries were not found"));
        }
        for (Knowledge k : rows) {
            rejectMoving(k);
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
        String taskId = knowledgeService.batchReparseKnowledge(kbId, ids);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reparse_count", ids.size());
        data.put("task_id", taskId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("message", "Batch reparse task submitted");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 MoveKnowledgeToFolder：body 携带 kb_id/folder_path（目的地不存在即创建） */
    @PostMapping("/knowledge/folder")
    public ResponseEntity<?> moveKnowledgeToFolder(@RequestBody(required = false) String rawBody) {
        JsonNode body = bindBatchBody(rawBody);
        requireField(body, "kb_id", "MoveKnowledgeToFolderRequest", "KBID");
        requireField(body, "knowledge_ids", "MoveKnowledgeToFolderRequest", "IDs");
        List<String> ids = requireBatchIds(body.get("knowledge_ids"), "knowledge_ids");
        String folderPath = body.hasNonNull("folder_path") ? body.get("folder_path").asText() : "";
        String kbId = batchAccessChecks(LogSanitizer.sanitize(textOrEmpty(body, "kb_id")));
        requireKnowledgeInKb(kbId, ids);
        long affected = knowledgeService.moveKnowledgeToFolder(kbId, ids, folderPath);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("folder_path", KnowledgeService.normalizeKnowledgeFolderPath(folderPath));
        data.put("moved_count", affected);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 RenameKnowledgeFolder：路由 ownership（纯字符串）→ 绑定 → handler 链 → service */
    @PutMapping("/knowledge-bases/{id}/knowledge/folders")
    public ResponseEntity<?> renameKnowledgeFolder(@PathVariable("id") String id,
                                                   @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        // 对照路由守卫 OwnedKBOrAdmin（先于 KB 访问；KB 缺失 → 放行交给后续守卫）
        KnowledgeBase routeKb = knowledgeService.findKb(kbId);
        if (routeKb != null) {
            guard.requireOwnedKb(routeKb);
        }
        JsonNode body = bindRequiredBody(rawBody);
        String from = body.hasNonNull("from") ? body.get("from").asText() : null;
        String to = body.hasNonNull("to") ? body.get("to").asText() : null;
        if (from == null || from.isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: "
                    + "Key: 'RenameKnowledgeFolderRequest.From' Error:Field validation for 'From' failed on the 'required' tag"));
        }
        if (to == null || to.isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: "
                    + "Key: 'RenameKnowledgeFolderRequest.To' Error:Field validation for 'To' failed on the 'required' tag"));
        }
        // 对照 validateKnowledgeBaseWriteAccessWithKBID + permission + requireKBOwnershipOrAdmin
        KnowledgeBase kb = guard.requireKbAccess(kbId);
        guard.requireKbOwnershipOrAdminEnvelope(kb);
        long affected = knowledgeService.renameKnowledgeFolder(kbId, from, to);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("folder_path", KnowledgeService.normalizeKnowledgeFolderPath(to));
        data.put("moved_count", affected);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 ClearKnowledgeBaseContents：Admin 路由门 + 仅 owner 租户可清 */
    @DeleteMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<?> clearKnowledgeBaseContents(@PathVariable("id") String id) {
        log.info("Start clearing knowledge base contents");
        String kbId = LogSanitizer.sanitize(id);
        KnowledgeBase kb = guard.requireKbAccess(kbId);
        Long callerTenant = com.ragagent.common.context.TenantContext.currentTenantId();
        String role = com.ragagent.common.context.TenantContext.currentRole();
        boolean admin = com.ragagent.auth.domain.TenantRole.fromString(role)
                .hasPermission(com.ragagent.auth.domain.TenantRole.ADMIN);
        if (kb.getTenantId() == null || !kb.getTenantId().equals(callerTenant) || !admin) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can clear contents"));
        }
        int count = knowledgeService.clearKnowledgeBaseContents(kbId);
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted_count", count);
        resp.put("data", data);
        resp.put("message", count == 0 ? "Knowledge base is already empty"
                : "Knowledge base contents clear task submitted");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    // ── 波 2 第三批：search / move / move progress ────────────────────────

    /**
     * 对照 SearchKnowledge（GET /knowledge/search，Viewer、无 KB 守卫——跨库搜索，
     * 范围由调用方决定）。绑定顺序照 Go：keyword/?query 双参 → recent 判空 →
     * parseOffsetPagination（1010 文案）→ file_types → agent_id → API-Key 白名单 →
     * own+shared 缺省路径。空 keyword 只在显式 recent=true 时合法。
     *
     * <p>agent_id 分支（W5α 收口，Go L2185-2250）：共享 agent 解析 → scope 空短路 →
     * 显式选择直取 / "all" 列源空间 KB 经能力过滤且仅 document 型 → API-Key 过滤 →
     * SearchKnowledgeForScopes。</p>
     */
    @GetMapping("/knowledge/search")
    public ResponseEntity<?> searchKnowledge(
            @RequestParam(value = "keyword", required = false) String keywordParam,
            @RequestParam(value = "query", required = false) String queryParam,
            @RequestParam(value = "recent", required = false) String recentParam,
            @RequestParam(value = "offset", required = false) String offsetParam,
            @RequestParam(value = "limit", required = false) String limitParam,
            @RequestParam(value = "file_types", required = false) String fileTypesParam,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "agent_source_tenant_id", required = false) String agentSourceTenantId) {
        // Go: recent, _ := strconv.ParseBool(...) —— 非法值静默为 false
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
        // 对照 parseOffsetPagination：offset 非负整数；limit 1..100；错误是 1010 校验信封
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
        String safeAgent = LogSanitizer.sanitize(agentId == null ? "" : agentId);
        if (!safeAgent.isEmpty()) {
            // 对照 SearchKnowledge 的 agent_id 分支（W5α 收口，Go L2185-2250）
            com.ragagent.org.domain.AgentRow agent =
                    sharedAgentAccess.resolveForRequest(safeAgent, agentSourceTenantId);
            long sourceTenant = agent.getTenantId();
            com.ragagent.org.service.SharedAgentKBScope agentScope =
                    com.ragagent.org.service.SharedAgentKBScope.from(agent);
            if (agentScope.isEmpty()) {
                return ResponseEntity.ok(searchAgentEmptyBody());
            }
            List<KnowledgeService.KnowledgeSearchScope> scopes = new ArrayList<>();
            if (!agentScope.isAll()) {
                for (String id : agentScope.ids()) {
                    if (!id.isEmpty()) {
                        scopes.add(new KnowledgeService.KnowledgeSearchScope(sourceTenant, id));
                    }
                }
            } else {
                List<KnowledgeBase> kbs = kbService.listKnowledgeBasesByTenantId(sourceTenant);
                for (KnowledgeBase kb : com.ragagent.knowledge.service.SharedAgentAccessResolver
                        .filterKnowledgeBasesForSharedAgent(kbs, agent)) {
                    if ("document".equals(kb.getType())) {
                        scopes.add(new KnowledgeService.KnowledgeSearchScope(kb.getTenantId(),
                                kb.getId()));
                    }
                }
            }
            // 对照 filterKnowledgeSearchScopesForAPIKey：受限 Key 只保留白名单 KB
            var keyScope = com.ragagent.apikey.domain.APIKeyScopeContext.current();
            if (keyScope != null && keyScope.isKnowledgeBaseRestricted()) {
                scopes = scopes.stream()
                        .filter(s -> keyScope.allowsKnowledgeBase(s.kbId())).toList();
            }
            if (scopes.isEmpty()) {
                return ResponseEntity.ok(searchAgentEmptyBody());
            }
            KnowledgeService.SearchOutcome agentOutcome = knowledgeService.searchKnowledgeInScopes(
                    scopes, keyword, offset, limit, fileTypes);
            Map<String, Object> agentBody = new LinkedHashMap<>();
            agentBody.put("data", agentOutcome.knowledges());
            agentBody.put("has_more", agentOutcome.hasMore());
            agentBody.put("success", true);
            agentBody.put("total", agentOutcome.total());
            return ResponseEntity.ok(agentBody);
        }
        KnowledgeService.SearchOutcome outcome;
        var scope = com.ragagent.apikey.domain.APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            // 对照 tenantAPIKeySearchScopes：受限 Key 的搜索范围 = 白名单 KB（本租户）
            List<KnowledgeService.KnowledgeSearchScope> scopes = new ArrayList<>();
            long tid = tenantId();
            for (String kbId : scope.knowledgeBaseIds()) {
                scopes.add(new KnowledgeService.KnowledgeSearchScope(tid, kbId));
            }
            outcome = knowledgeService.searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
        } else {
            outcome = knowledgeService.searchKnowledge(keyword, offset, limit, fileTypes);
        }
        Map<String, Object> body = new LinkedHashMap<>();
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

    /** 对照 agent 分支空响应：gin.H{"success","data":[],"has_more":false,"total":0}（键字母序）。 */
    private static Map<String, Object> searchAgentEmptyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", new ArrayList<>());
        body.put("has_more", false);
        body.put("success", true);
        body.put("total", 0);
        return body;
    }

    /**
     * 对照 MoveKnowledge（POST /knowledge/move，Contributor；body 携带 source/target，
     * handler 内逐层校验后入队）。Go 的 binding 校验把**所有**失败字段按 struct 序用
     * \n 连接放进同一条 message（golden ks-move-empty-body 钉住）。
     */
    @PostMapping("/knowledge/move")
    public ResponseEntity<?> moveKnowledge(@RequestBody(required = false) String rawBody) {
        JsonNode body = bindMoveBody(rawBody);
        List<String> knowledgeIds = new ArrayList<>();
        if (body.get("knowledge_ids") != null && body.get("knowledge_ids").isArray()) {
            body.get("knowledge_ids").forEach(n -> knowledgeIds.add(n.asText()));
        }
        String sourceKbId = textOrEmpty(body, "source_kb_id");
        String targetKbId = textOrEmpty(body, "target_kb_id");
        String mode = textOrEmpty(body, "mode");
        if (sourceKbId.equals(targetKbId)) {
            throw new BizException(AppError.badRequest("Source and target knowledge base cannot be the same"));
        }
        long callerTenant = tenantId();
        if (callerTenant == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        // 对照 requireTenantAPIKeyKnowledgeBases(source, target)
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(
                List.of(sourceKbId, targetKbId));
        // 源库：存在性（404 大写 S）→ 租户（403）→ ownership（信封）
        KnowledgeBase sourceKb = knowledgeService.findKb(sourceKbId);
        if (sourceKb == null) {
            throw new BizException(AppError.notFound("Source knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            throw new BizException(AppError.forbidden("No permission to access source knowledge base"));
        }
        guard.requireKbOwnershipOrAdminEnvelope(sourceKb);
        // 目标库：同链
        KnowledgeBase targetKb = knowledgeService.findKb(targetKbId);
        if (targetKb == null) {
            throw new BizException(AppError.notFound("Target knowledge base not found"));
        }
        if (targetKb.getTenantId() == null || targetKb.getTenantId() != callerTenant) {
            throw new BizException(AppError.forbidden("No permission to access target knowledge base"));
        }
        guard.requireKbOwnershipOrAdminEnvelope(targetKb);
        // 对照 resolveHandlerKBAccessFor(source/target, Editor)：org-share 未翻译，
        // 同租户 Editor 授予恒可过；跨租户已在上面拦掉
        // 对照 WithKBTransfer → ValidateKBTransferCompatibility（消息逐字照 Go）
        try {
            KnowledgeService.validateKBTransferCompatibility(sourceKb, targetKb, mode);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // dedupe + 空白拒绝 + 归属/状态校验（顺序照 Go）
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String id : knowledgeIds) {
            if (id.trim().isEmpty()) {
                throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
            }
            seen.add(id);
        }
        List<String> uniqueIds = new ArrayList<>(seen);
        for (String kId : uniqueIds) {
            // Go 用 service 的 GetKnowledgeByID（ctx 租户过滤）：租户内查不到 → "not found"；
            // 查到但 KB 不对 → "does not belong"。两句话前后依赖，不能合并。
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
        String taskId = KnowledgeService.generateTaskId("kg_move", callerTenant, sourceKbId);
        knowledgeService.startKnowledgeMove(callerTenant, taskId, uniqueIds, sourceKbId, targetKbId, mode);
        var resp = new com.ragagent.knowledge.dto.KnowledgeTaskDtos.MoveKnowledgeResponse(
                taskId, sourceKbId, targetKbId, uniqueIds.size(), "Knowledge move task started");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", resp);
        out.put("success", true);
        return ResponseEntity.ok(out);
    }

    /**
     * 对照 MoveKnowledgeRequest 绑定：KnowledgeIDs(required,min=1) / SourceKBID(required) /
     * TargetKBID(required) / Mode(required,oneof=reuse_vectors reparse)。validator 把全部
     * 失败字段按 struct 序 join("\n")；Go 的 message 形态是 "Invalid request parameters: " + 原文。
     */
    private JsonNode bindMoveBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: EOF"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    "Invalid request parameters: " + GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (body == null || !body.isObject()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: EOF"));
        }
        List<String> failures = new ArrayList<>();
        JsonNode ids = body.get("knowledge_ids");
        if (ids == null || ids.isNull()) {
            failures.add("Key: 'MoveKnowledgeRequest.KnowledgeIDs' Error:Field validation for "
                    + "'KnowledgeIDs' failed on the 'required' tag");
        } else if (!ids.isArray() || ids.isEmpty()) {
            failures.add("Key: 'MoveKnowledgeRequest.KnowledgeIDs' Error:Field validation for "
                    + "'KnowledgeIDs' failed on the 'min' tag");
        }
        if (isMissingString(body, "source_kb_id")) {
            failures.add("Key: 'MoveKnowledgeRequest.SourceKBID' Error:Field validation for "
                    + "'SourceKBID' failed on the 'required' tag");
        }
        if (isMissingString(body, "target_kb_id")) {
            failures.add("Key: 'MoveKnowledgeRequest.TargetKBID' Error:Field validation for "
                    + "'TargetKBID' failed on the 'required' tag");
        }
        String mode = textOrEmpty(body, "mode");
        if (body.get("mode") == null || body.get("mode").isNull() || mode.isEmpty()) {
            failures.add("Key: 'MoveKnowledgeRequest.Mode' Error:Field validation for "
                    + "'Mode' failed on the 'required' tag");
        } else if (!"reuse_vectors".equals(mode) && !"reparse".equals(mode)) {
            failures.add("Key: 'MoveKnowledgeRequest.Mode' Error:Field validation for "
                    + "'Mode' failed on the 'oneof' tag");
        }
        if (!failures.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Invalid request parameters: " + String.join("\n", failures)));
        }
        return body;
    }

    private static boolean isMissingString(JsonNode body, String field) {
        JsonNode v = body.get(field);
        return v == null || v.isNull() || v.asText("").isEmpty();
    }

    /** 对照 GetKnowledgeMoveProgress：租户隔离（ParseTaskID）→ 进度（404 文案专属）。 */
    @GetMapping("/knowledge/move/progress/{taskId}")
    public ResponseEntity<?> getKnowledgeMoveProgress(@PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Task ID cannot be empty"));
        }
        requireTaskProgressTenant(taskId);
        var progress = knowledgeService.getKnowledgeMoveProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("Knowledge move task not found"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", progress);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 requireTaskProgressTenant：坏 id → 400；跨租户 → 404 "task not found"。 */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenant = KnowledgeService.taskTenantId(taskId);
        if (taskTenant == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        long caller = tenantId();
        if (caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (taskTenant != caller) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    // ── 波 2 守卫/绑定工具 ────────────────────────────────────────────────

    /**
     * 对照路由中间件链（resolveKnowledgeAndValidateKBAccess）：knowledge 全局缺失 →
     * 404 "Knowledge not found"（大写 K）→ [ownership 放行或 403 纯字符串，仅带
     * OwnedKnowledgeKBOrAdmin 的路由] → KB 访问（404 小写 k / 403 信封）→ 返回调用者
     * 空间内的 knowledge 行。download/preview 没有 ownership 中间件（download 只有
     * Contributor 角色门 + KBAccessWrite），单独区分。
     */
    private Knowledge resolveKnowledgeByGuard(String knowledgeId, boolean ownership, boolean write) {
        Knowledge global = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
        if (global == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        if (ownership) {
            guard.requireOwnedKnowledgeKb(knowledgeId);
        }
        guard.requireKbAccess(global.getKnowledgeBaseId());
        return knowledgeService.getKnowledge(knowledgeId);
    }

    private Knowledge resolveKnowledgeByGuard(String knowledgeId, boolean write) {
        return resolveKnowledgeByGuard(knowledgeId, write, write);
    }

    /**
     * 对照 resolveKnowledgeAndValidateKBAccess 的 handler 层形态（用于没有任何路由级
     * KBAccess 中间件的 body 路由，如 /knowledge/tags 无 kb_id 的推导路径）：
     * knowledge 缺失 → 404 "Knowledge not found"；跨租户 → 403
     * "Permission denied to access this <b>knowledge</b>"（注意与路由层的
     * "...knowledge base" 一字之差，golden 钉住）。
     */
    private Knowledge resolveKnowledgeHandlerLevel(String knowledgeId, boolean write) {
        Knowledge global = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
        if (global == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(
                java.util.List.of(global.getKnowledgeBaseId()));
        Long caller = com.ragagent.common.context.TenantContext.currentTenantId();
        if (global.getTenantId() == null || !global.getTenantId().equals(caller)) {
            throw new BizException(AppError.forbidden("Permission denied to access this knowledge"));
        }
        return knowledgeService.getKnowledge(knowledgeId);
    }

    /** 批处理路由共用的 KB 访问 + ownership（信封）链。 */
    private String batchAccessChecks(String kbId) {
        KnowledgeBase kb = guard.requireKbAccess(kbId);
        guard.requireKbOwnershipOrAdminEnvelope(kb);
        return kbId;
    }

    /** 对照 access.RejectMovingKnowledge（controller 面：move 未完成 → 409）。 */
    private void rejectMoving(Knowledge k) {
        JsonNode metadata = k.getMetadata();
        if (metadata != null && metadata.has("_knowledge_transfer")) {
            JsonNode state = metadata.get("_knowledge_transfer");
            if ("move".equals(state.path("operation").asText(""))
                    && "moving".equals(state.path("phase").asText(""))) {
                throw new BizException(AppError.conflict(
                        "knowledge has an unfinished move; retry the move first"));
            }
        }
    }

    /** 对照 requireKnowledgeInKB：计数不符 → 400 "One or more..."；跨 KB → 400 "Knowledge %s does not belong..." */
    private void requireKnowledgeInKb(String kbId, List<String> ids) {
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("One or more knowledge entries not found"));
        }
        for (Knowledge k : rows) {
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
    }

    /** 对照 dedupeKnowledgeIDs + 空校验 + max 200 */
    private static List<String> requireBatchIds(JsonNode rawIds, String field) {
        List<String> ids = dedupeIds(rawIds);
        if (ids.isEmpty()) {
            throw new BizException(AppError.badRequest(field + " cannot be empty"));
        }
        if (ids.size() > 200) {
            throw new BizException(AppError.badRequest("too many ids (max 200 per batch)"));
        }
        return ids;
    }

    private static List<String> dedupeIds(JsonNode rawIds) {
        List<String> ids = new ArrayList<>();
        if (rawIds == null || !rawIds.isArray()) {
            return ids;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        rawIds.forEach(n -> {
            String id = n.asText();
            if (id != null && !id.trim().isEmpty() && seen.add(id)) {
                ids.add(id);
            }
        });
        return ids;
    }

    /**
     * 对照 ShouldBindJSON 的 400 前缀形态（"Invalid request parameters: " + 原文）：
     * 空 body → EOF；JSON 语法错 → Go 解析器原文。required 字段校验由
     * {@link #requireField} 在解析后按 struct 字段序补齐。
     */
    private JsonNode bindBatchBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: EOF"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    "Invalid request parameters: " + GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (body == null || !body.isObject()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: EOF"));
        }
        return body;
    }

    /** 对照 validator 的 required：字段缺失或显式 null 触发（数组 [] 非 nil，可过 binding）。 */
    private void requireField(JsonNode body, String jsonName, String structName, String fieldName) {
        JsonNode v = body.get(jsonName);
        if (v == null || v.isNull()) {
            throw new BizException(AppError.badRequest("Invalid request parameters: Key: '"
                    + structName + "." + fieldName + "' Error:Field validation for '"
                    + fieldName + "' failed on the 'required' tag"));
        }
    }

    /** 对照 ShouldBindJSON（无前缀形态）：空 body → EOF；语法错 → Go 解析器原文；
     *  null 字面量 → 空对象（Go 零值绑定不报错）。 */
    private JsonNode bindRequiredBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (body == null || !body.isObject()) {
            return MAPPER.createObjectNode();
        }
        return body;
    }

    /** 对照 knowledgeTagBatchRequest 绑定（updates required,min=1 → 固定文案 + details 原文）。 */
    private JsonNode bindTagBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("EOF"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("请求参数不合法")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (body == null || !body.isObject()) {
            throw new BizException(AppError.badRequest("请求参数不合法")
                    .withDetails("json: cannot unmarshal into Go value of type handler.knowledgeTagBatchRequest"));
        }
        JsonNode updates = body.get("updates");
        if (updates == null || updates.isNull()) {
            throw new BizException(AppError.badRequest("请求参数不合法")
                    .withDetails("Key: 'knowledgeTagBatchRequest.Updates' Error:Field validation for "
                            + "'Updates' failed on the 'required' tag"));
        }
        if (!updates.isObject() || updates.isEmpty()) {
            throw new BizException(AppError.badRequest("请求参数不合法")
                    .withDetails("Key: 'knowledgeTagBatchRequest.Updates' Error:Field validation for "
                            + "'Updates' failed on the 'min' tag"));
        }
        return body;
    }

    /** Go mime.FormatMediaType 的对位：token 安全 → filename=...；否则 RFC2231
     *  filename*=utf-8''%XX（大写十六进制，非 token 字符全编码，与 gin 实测一致）。 */
    private static String contentDisposition(String disposition, String filename) {
        boolean token = !filename.isEmpty() && filename.chars().allMatch(KnowledgeController::isTokenChar);
        if (token) {
            return disposition + "; filename=" + filename;
        }
        StringBuilder sb = new StringBuilder(disposition).append("; filename*=utf-8''");
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (isTokenChar(c)) {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b));
            }
        }
        return sb.toString();
    }

    /** 对照 Go isTokenChar（mime/mediatype.go：非空白非 CTL 且非 tspecials）。 */
    private static boolean isTokenChar(int c) {
        if (c <= 0x20 || c >= 0x7f) {
            return false;
        }
        return "()<>@,;:\\\"/[]?=".indexOf(c) < 0;
    }

    /**
     * 对照 filetransport.Serve 的响应头骨架（下载/预览共用；Range 语义未复刻——
     * MockMvc 契约只锁头部集合与字节）。
     *
     * @param seeker Go 的 io.ReadSeeker 判定：真实文件 → Accept-Ranges: bytes；
     *               manual（内存 reader）→ Accept-Ranges: none + 显式 Content-Length
     */
    private ResponseEntity<byte[]> serveFile(KnowledgeService.KnowledgeFile file, String contentType,
                                             boolean inline, boolean seeker, Map<String, String> preHeaders) {
        String filename = file.filename() == null ? "" : file.filename();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", contentDisposition(inline ? "inline" : "attachment", filename))
                .header("Cache-Control", "private, no-store")
                .header("Content-Length", String.valueOf(file.content().length));
        if (!seeker) {
            builder.header("Accept-Ranges", "none");
        } else {
            builder.header("Accept-Ranges", "bytes");
        }
        preHeaders.forEach(builder::header);
        return builder.body(file.content());
    }

    private static String textOrEmpty(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    private static long tenantId() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    /** 对照 UpdateKnowledge — 守卫链 + 部分更新，响应 success/message/data（字母序） */
    @PutMapping("/knowledge/{id}")
    public ResponseEntity<?> updateKnowledge(@PathVariable("id") String id,
                                             @RequestBody(required = false) String rawBody) {
        log.info("Start updating knowledge, ID: {}", id);
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        JsonNode body = rawBody == null || rawBody.isBlank() ? null : parseBody(rawBody, false);
        Knowledge k = knowledgeService.updateKnowledge(safeId, body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", k);
        resp.put("message", "Knowledge updated successfully");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 DeleteKnowledge：守卫链 + RejectMoving + 异步语义（返回 task_id） */
    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<?> deleteKnowledge(@PathVariable("id") String id) {
        log.info("Start deleting knowledge, ID: {}", id);
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        resolveKnowledgeByGuard(safeId, true);
        String taskId = knowledgeService.deleteKnowledge(safeId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("message", "Delete task submitted");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    /** 对照 409 重复信封：{"code","data"(已存在文档),"message","success":false}（字母序） */
    private static ResponseEntity<Map<String, Object>> duplicateResponse(
            KnowledgeService.DuplicateKnowledgeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("data", e.existing());
        body.put("message", e.getMessage());
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static JsonNode parseBody(String rawBody, boolean allowNull) {
        if (rawBody == null || rawBody.isBlank()) {
            if (allowNull) {
                throw new BizException(AppError.badRequest("EOF"));
            }
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception ex) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(ex.getMessage()));
        }
    }

    private static JsonNode parseJsonParam(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new BizException(AppError.badRequest(label + " must be a valid JSON"));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }
}
