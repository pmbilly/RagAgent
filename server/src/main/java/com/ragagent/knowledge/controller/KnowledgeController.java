package com.ragagent.knowledge.controller;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.ContentTypeByFilename;
import com.ragagent.common.web.DataMessageResponse;
import com.ragagent.common.web.MessageResponse;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.dto.KnowledgeDtos.ClearContentsResponse;
import com.ragagent.knowledge.dto.KnowledgeDtos.CreateFromUrlRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.CreateManualRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.KnowledgeListResponse;
import com.ragagent.knowledge.dto.KnowledgeDtos.UpdateImageInfoRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.UpdateKnowledgeRequest;
import com.ragagent.knowledge.dto.KnowledgeDtos.TaskIdResponse;
import com.ragagent.knowledge.dto.KnowledgeDtos.UpdateManualRequest;
import com.ragagent.knowledge.service.KnowledgeFileService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.LocalStorageService;
import com.ragagent.storage.fileserve.FileTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;

/**
 * 知识文档主面：创建（文件/URL/手工）、列表/详情/批量取、解析生命周期
 * （重析/取消/进度 span）、下载/预览、图片信息、摘要与单文档更新/删除。
 * 运营操作面（搜索/批量/搬移/标签/文件夹）在 {@link KnowledgeOpsController}。
 *
 * <p>守卫链经 {@link KnowledgeRouteGuards}（全局缺失 404 大写 K → [ownership 403
 * 纯字符串] → KB 访问 404/403）；重复文档 409 为特殊信封
 * （code/data/message/success），不走全局错误处理器。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;
    private final KnowledgeRouteGuards guards;
    private final SsrfGuard ssrfGuard;

    public KnowledgeController(KnowledgeService knowledgeService,
                               KnowledgeRouteGuards guards,
                               SsrfGuard ssrfGuard) {
        this.knowledgeService = knowledgeService;
        this.guards = guards;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * multipart 上传。tag_ids / process_config 参数被接收但静默丢弃（管线只读 KB 级
     * 配置；完整落地需穿越处理分块与写链，待专项收口）。
     */
    @PostMapping("/knowledge-bases/{id}/knowledge/file")
    public ResponseEntity<ApiResponse<Knowledge>> createFromFile(
            @PathVariable("id") String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "metadata", required = false) String metadataJson,
            @RequestParam(value = "tag_ids", required = false) String tagIds,
            @RequestParam(value = "channel", required = false) String channel)
            throws IOException {
        log.info("Start creating knowledge from file, KB: {}", kbId);
        JsonNode customMetadata = KnowledgeRouteGuards.parseJsonParam(MAPPER, metadataJson, "metadata");
        byte[] content = LocalStorageService.readAll(file.getInputStream());
        try {
            Knowledge k = knowledgeService.createFromFile(
                    kbId, content,
                    fileName != null && !fileName.isEmpty() ? fileName : file.getOriginalFilename(),
                    fileName, customMetadata, channel);
            return ResponseEntity.ok(ApiResponse.ok(k));
        } catch (KnowledgeService.DuplicateKnowledgeException e) {
            return KnowledgeRouteGuards.duplicateResponse(e);
        }
    }

    /** 201；SSRF 校验先行。 */
    @PostMapping("/knowledge-bases/{id}/knowledge/url")
    public ResponseEntity<ApiResponse<Knowledge>> createFromUrl(
            @PathVariable("id") String kbId,
            @Valid @NonNullBody @RequestBody CreateFromUrlRequest req) {
        log.info("Start creating knowledge from URL, KB: {}", kbId);
        try {
            ssrfGuard.validateURLForSSRF(req.url());
        } catch (SsrfGuard.SsrfException e) {
            throw new BizException(AppError.badRequest(ssrfGuard.formatSSRFError("URL", req.url(), e)));
        }
        try {
            Knowledge k = knowledgeService.createFromUrl(kbId, req.url(),
                    req.fileName(), req.fileType(), req.title(), req.channel());
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(k));
        } catch (KnowledgeService.DuplicateKnowledgeException e) {
            return KnowledgeRouteGuards.duplicateResponse(e);
        }
    }

    @PostMapping("/knowledge-bases/{id}/knowledge/manual")
    public ResponseEntity<ApiResponse<Knowledge>> createManual(
            @PathVariable("id") String kbId,
            @Valid @NonNullBody @RequestBody CreateManualRequest req) {
        log.info("Start creating manual knowledge, KB: {}", kbId);
        Knowledge k = knowledgeService.createManual(kbId, req.title(),
                req.content() == null ? "" : req.content(),
                req.status() == null ? "" : req.status(),
                req.channel());
        return ResponseEntity.ok(ApiResponse.ok(k));
    }

    /** 真分页，顶层 data/page/page_size/total/success（既有五键契约）。 */
    @GetMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<KnowledgeListResponse> listKnowledge(
            @PathVariable("id") String kbId,
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
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Knowledge> result =
                knowledgeService.listKnowledge(
                        kbId, page, pageSize, keyword, parseStatus, fileType, folderPath, folderPresent);
        return ResponseEntity.ok(new KnowledgeListResponse(
                result.getRecords(), page, pageSize, true, result.getTotal()));
    }

    @GetMapping("/knowledge-bases/{id}/knowledge/folders")
    public ResponseEntity<ApiResponse<Object>> listFolders(@PathVariable("id") String kbId) {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.folderTree(kbId)));
    }

    @GetMapping("/knowledge/{id}")
    public ResponseEntity<ApiResponse<Knowledge>> getKnowledge(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.getKnowledge(safeId)));
    }

    /**
     * query 绑定：ids required（"ids=" → [""] 通过绑定，服务层查不到行 → data:[]）。
     */
    @GetMapping("/knowledge/batch")
    public ResponseEntity<ApiResponse<List<Knowledge>>> getKnowledgeBatch(
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "kb_id", required = false) String kbId) {
        long callerTenant = KnowledgeRouteGuards.tenantId();
        if (callerTenant == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (ids == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("ids: 不能为空"));
        }
        List<Knowledge> knowledges;
        if (LogSanitizer.sanitize(kbId == null ? "" : kbId).isEmpty()) {
            knowledges = knowledgeService.getKnowledgeBatchWithSharedAccess(callerTenant, ids);
        } else {
            String safeKbId = LogSanitizer.sanitize(kbId);
            guards.batchAccessChecks(safeKbId);
            knowledges = knowledgeService.getKnowledgeBatch(callerTenant, ids);
            knowledges = knowledges.stream()
                    .filter(k -> safeKbId.equals(k.getKnowledgeBaseId())).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(knowledges));
    }

    /** /stages 与 /spans 两个路径同 handler。 */
    @GetMapping({"/knowledge/{id}/stages", "/knowledge/{id}/spans"})
    public ResponseEntity<ApiResponse<Object>> getKnowledgeSpans(
            @PathVariable("id") String id,
            @RequestParam(value = "attempt", required = false) String attempt) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        Knowledge knowledge = knowledgeService.getKnowledge(safeId);
        int requestedAttempt = 0;
        if (attempt != null && !attempt.trim().isEmpty()) {
            try {
                int n = Integer.parseInt(attempt.trim());
                if (n > 0) {
                    requestedAttempt = n;
                }
            } catch (NumberFormatException ignored) {
                // 非法 attempt 保持 0（取最新）
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(
                knowledgeService.knowledgeSpans(knowledge, requestedAttempt)));
    }

    /** 无既有摘要 → 同步重生；有 → 入队刷新并回读 pending 态。 */
    @PostMapping("/knowledge/{id}/regenerate-summary")
    public ResponseEntity<ApiResponse<Knowledge>> regenerateKnowledgeSummary(
            @PathVariable("id") String id) {
        String safeId = requireKnowledgeId(id);
        Knowledge knowledge = guards.resolveKnowledgeByGuard(safeId, true);
        Knowledge result;
        if (knowledge.getSummaryStatus() == null || knowledge.getSummaryStatus().isEmpty()
                || "none".equals(knowledge.getSummaryStatus())) {
            result = knowledgeService.regenerateKnowledgeSummary(safeId);
        } else {
            knowledgeService.requestKnowledgeSummaryRefresh(safeId);
            result = knowledgeService.getKnowledge(safeId);
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/knowledge/manual/{id}")
    public ResponseEntity<ApiResponse<Knowledge>> updateManualKnowledge(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateManualRequest req) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        Knowledge k = knowledgeService.updateManualKnowledge(safeId,
                req.title(), req.content(), req.status(), req.channel());
        return ResponseEntity.ok(ApiResponse.ok(k));
    }

    /** 空 body 允许（保留上传时配置）；非空时仅做语法校验。 */
    @PostMapping("/knowledge/{id}/reparse")
    public ResponseEntity<DataMessageResponse<Knowledge>> reparseKnowledge(
            @PathVariable("id") String id,
            @RequestBody(required = false) JsonNode body) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        Knowledge k = knowledgeService.reparseKnowledge(safeId);
        return ResponseEntity.ok(DataMessageResponse.of(k, "Knowledge reparse task submitted"));
    }

    @PostMapping("/knowledge/{id}/cancel-parse")
    public ResponseEntity<DataMessageResponse<Knowledge>> cancelKnowledgeParse(
            @PathVariable("id") String id) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        Knowledge k = knowledgeService.cancelKnowledgeParse(safeId);
        return ResponseEntity.ok(DataMessageResponse.of(k, "Knowledge parse cancelled"));
    }

    /** Contributor 路由门 + KBAccessWrite + handler 内 Editor 检查（守卫链 write 分支）。 */
    @GetMapping("/knowledge/{id}/download")
    public void downloadKnowledgeFile(@PathVariable("id") String id,
                                      HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false, true);
        KnowledgeFileService.KnowledgeFileStream file = knowledgeService.openKnowledgeFile(safeId);
        response.setHeader("Content-Description", "File Transfer");
        response.setHeader("Content-Transfer-Encoding", "binary");
        response.setHeader("Expires", "0");
        FileTransport.serve(response, request, file.opened(), new FileTransport.Options(
                file.filename(), true, "application/octet-stream",
                KnowledgeRouteGuards.contentDisposition("attachment",
                        KnowledgeRouteGuards.safeFilename(file.filename())),
                "private, no-store", file.opened().size()));
    }

    /** Viewer + KBAccessRead，Content-Type 按扩展名。 */
    @GetMapping("/knowledge/{id}/preview")
    public void previewKnowledgeFile(@PathVariable("id") String id,
                                     HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        KnowledgeFileService.KnowledgeFileStream file = knowledgeService.openKnowledgeFile(safeId);
        ContentTypeByFilename.Record safe = ContentTypeByFilename.safe(file.filename());
        FileTransport.serve(response, request, file.opened(), new FileTransport.Options(
                file.filename(), !safe.inline(), safe.contentType(),
                KnowledgeRouteGuards.contentDisposition(safe.inline() ? "inline" : "attachment",
                        KnowledgeRouteGuards.safeFilename(file.filename())),
                "private, no-store", file.opened().size()));
    }

    @PutMapping("/knowledge/image/{id}/{chunkId}")
    public ResponseEntity<MessageResponse> updateImageInfo(
            @PathVariable("id") String id,
            @PathVariable("chunkId") String chunkId,
            @Valid @NonNullBody @RequestBody UpdateImageInfoRequest req) {
        String safeId = requireKnowledgeId(id);
        String safeChunkId = LogSanitizer.sanitize(chunkId);
        if (safeChunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        guards.resolveKnowledgeByGuard(safeId, true);
        String imageInfo = req.imageInfo() == null ? "" : req.imageInfo();
        knowledgeService.updateImageInfo(safeId, safeChunkId, imageInfo);
        return ResponseEntity.ok(new MessageResponse(
                "Knowledge chunk image updated successfully", true));
    }

    /** 守卫链 + 部分更新（更新字段集合见 {@link UpdateKnowledgeRequest}）。 */
    @PutMapping("/knowledge/{id}")
    public ResponseEntity<DataMessageResponse<Knowledge>> updateKnowledge(
            @PathVariable("id") String id,
            @RequestBody(required = false) UpdateKnowledgeRequest request) {
        log.info("Start updating knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        Knowledge k = knowledgeService.updateKnowledge(safeId, request);
        return ResponseEntity.ok(DataMessageResponse.of(k, "Knowledge updated successfully"));
    }

    /** 守卫链 + 搬移中拒绝（service 内）+ 异步删除语义。 */
    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<DataMessageResponse<TaskIdResponse>> deleteKnowledge(
            @PathVariable("id") String id) {
        log.info("Start deleting knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        String taskId = knowledgeService.deleteKnowledge(safeId);
        DataMessageResponse<TaskIdResponse> resp =
                DataMessageResponse.of(new TaskIdResponse(taskId), "Delete task submitted");
        return ResponseEntity.ok(resp);
    }

    /** Admin 路由门 + 仅 owner 租户可清。 */
    @DeleteMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<DataMessageResponse<Object>> clearKnowledgeBaseContents(
            @PathVariable("id") String id) {
        log.info("Start clearing knowledge base contents");
        String kbId = LogSanitizer.sanitize(id);
        var kb = guards.requireKbAccess(kbId);
        Long callerTenant = TenantContext.currentTenantId();
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role)
                .hasPermission(TenantRole.ADMIN);
        if (kb.getTenantId() == null || !kb.getTenantId().equals(callerTenant) || !admin) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can clear contents"));
        }
        int count = knowledgeService.clearKnowledgeBaseContents(kbId);
        return ResponseEntity.ok(new DataMessageResponse<>(
                new ClearContentsResponse(count),
                count == 0 ? "Knowledge base is already empty"
                        : "Knowledge base contents clear task submitted", true));
    }

    private String requireKnowledgeId(String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        return safeId;
    }

}
