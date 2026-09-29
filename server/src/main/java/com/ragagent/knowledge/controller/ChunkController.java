package com.ragagent.knowledge.controller;

import java.util.List;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.dto.ChunkPageResponse;
import com.ragagent.knowledge.dto.ChunkRevisionResponse;
import com.ragagent.knowledge.dto.ChunkUpdateResponse;
import com.ragagent.knowledge.dto.DeleteGeneratedQuestionRequest;
import com.ragagent.knowledge.dto.RevertChunkRequest;
import com.ragagent.knowledge.dto.GeneratedQuestionResponse;
import com.ragagent.knowledge.dto.UpdateChunkRequest;
import com.ragagent.knowledge.dto.ChunkResponse;
import com.ragagent.knowledge.dto.UpsertGeneratedQuestionRequest;
import com.ragagent.knowledge.domain.ChunkNotFoundException;
import com.ragagent.knowledge.domain.ChunkRevisionConflictException;
import com.ragagent.knowledge.security.ChunkAccessGuard;
import com.ragagent.knowledge.service.ChunkEditService;
import com.ragagent.knowledge.service.ChunkReadService;
import com.ragagent.knowledge.service.ChunkQuestionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * chunk 路由：编辑/生成问题两服务与仓储读面的 HTTP 绑定层。
 *
 * <p><b>守卫链</b>：写路径 = ownership → KB 访问 → handler；读路径 = KB 访问 → handler
 * （判定顺序为既有契约，不能重排，详见 {@link ChunkAccessGuard}）。</p>
 *
 * <p><b>错误形态分层</b>（契约样例锁定）：update/delete 的业务失败 → 500 且
 * message=原文；revert 的同类错误 → 400——同一 service 异常在两个端点的 HTTP
 * 形态刻意不同，异常映射按端点分开写。</p>
 */
@RestController
public class ChunkController {

    private static final Logger log = LoggerFactory.getLogger(ChunkController.class);

    private final ChunkEditService chunkEdit;
    private final ChunkQuestionService chunkQuestion;
    private final ChunkReadService chunkRead;
    private final ChunkAccessGuard guard;

    public ChunkController(ChunkEditService chunkEdit,
                           ChunkQuestionService chunkQuestion,
                           ChunkReadService chunkRead,
                           ChunkAccessGuard guard) {
        this.chunkEdit = chunkEdit;
        this.chunkQuestion = chunkQuestion;
        this.chunkRead = chunkRead;
        this.guard = guard;
    }

    // ══════════════════════════ 读 ══════════════════════════

    /** 分页钳位：page&lt;1→1、size&lt;1→10、size&gt;100→100（size 的小值是合法值，非 clamp）。 */
    @GetMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<ChunkPageResponse> listKnowledgeChunks(
            @PathVariable("knowledgeId") String knowledgeId,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "pageSize", required = false) String pageSize,
            @RequestParam(value = "chunkType", required = false) List<String> chunkType) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        int pageValue = bindPagination(page, "page", false);
        if (pageValue < 1) {
            pageValue = 1;
        }
        int sizeValue = bindPagination(pageSize, "pageSize", true);
        if (sizeValue < 1) {
            sizeValue = 10;
        }
        if (sizeValue > 100) {
            sizeValue = 100;
        }

        // 默认只取 text 分块；调用方可用 ?chunkType=image_caption 等覆盖
        List<String> types = (chunkType == null || chunkType.isEmpty())
                ? List.of("text") : chunkType;

        guard.requireKbAccess(guard.kbIdFromKnowledgeParam(kgId));

        ChunkReadService.ChunkPageView result = chunkRead.listPagedChunks(
                tenantId(), kgId, (pageValue - 1) * sizeValue, sizeValue, types);

        List<ChunkResponse> items = result.items().stream().map(ChunkResponse::from).toList();
        return ResponseEntity.ok(new ChunkPageResponse(items, pageValue, sizeValue, result.total()));
    }

    @GetMapping("/api/v1/chunks/by-id/{id}")
    public ResponseEntity<ChunkResponse> getChunkByIdOnly(@PathVariable("id") String id) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        final Chunk chunk;
        try {
            chunk = chunkRead.getChunkByIdOnly(chunkId);
        } catch (ChunkNotFoundException e) {
            throw new BizException(AppError.notFound("Chunk not found"));
        }
        return ResponseEntity.ok(ChunkResponse.from(chunk));
    }

    /** 分块修订历史（直接返回数组）。 */
    @GetMapping("/api/v1/chunks/{knowledgeId}/{id}/revisions")
    public ResponseEntity<List<ChunkRevisionResponse>> listChunkRevisions(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        List<ChunkRevisionResponse> items = chunkEdit.listChunkRevisions(chunk.getId()).stream()
                .map(ChunkRevisionResponse::from)
                .toList();
        return ResponseEntity.ok(items);
    }

    // ══════════════════════════ 更新 / 回滚 ══════════════════════════

    /** 业务失败（fmt.Errorf 族）→ 500 信封 message=原文。 */
    @PutMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<ChunkUpdateResponse> updateChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id,
            @Valid @RejectEmptyBody @RequestBody(required = false) UpdateChunkRequest req) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        UpdateChunkRequest body = req == null ? new UpdateChunkRequest(null, null, null) : req;
        Chunk updated;
        try {
            updated = chunkEdit.updateDocumentChunk(
                    chunk.getId(), body.content(), body.enabled(), body.expectedRevision());
        } catch (ChunkRevisionConflictException e) {
            throw new BizException(AppError.conflict(
                    "Chunk was modified by another user; refresh and retry"));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(errText(e)));
        }
        return updatedResponse(updated, knowledgeId);
    }

    /** 非 AppError 在这里是 <b>400</b> 不是 500（revert 端点特有）。 */
    @PostMapping("/api/v1/chunks/{knowledgeId}/{id}/revert")
    public ResponseEntity<ChunkUpdateResponse> revertChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id,
            @Valid @RejectEmptyBody @RequestBody(required = false) RevertChunkRequest req) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        RevertChunkRequest body = req == null ? new RevertChunkRequest(null, null) : req;
        if (body.revision() != null && body.revision() < 0) {
            throw new BizException(AppError.badRequest("revision must be a non-negative integer"));
        }
        Chunk updated;
        try {
            updated = chunkEdit.revertDocumentChunk(chunk.getId(), body.revision(),
                    body.expectedRevision());
        } catch (ChunkRevisionConflictException e) {
            throw new BizException(AppError.conflict(
                    "Chunk was modified by another user; refresh and retry"));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(errText(e)));
        }
        return updatedResponse(updated, knowledgeId);
    }

    // ══════════════════════════ 生成问题 ══════════════════════════

    @PutMapping("/api/v1/chunks/by-id/{id}/questions")
    public ResponseEntity<GeneratedQuestionResponse> upsertGeneratedQuestion(
            @PathVariable("id") String id,
            @Valid @RequestBody UpsertGeneratedQuestionRequest req) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID is required"));
        }
        if (req.question() == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("question: 不能为空"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        String questionId = req.questionId() == null ? "" : req.questionId();
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        GeneratedQuestion item = chunkQuestion.upsertGeneratedQuestion(
                chunkId, questionId, req.question());
        return ResponseEntity.ok(GeneratedQuestionResponse.from(item));
    }

    /** 重新生成问题（直接返回数组）。 */
    @PostMapping("/api/v1/chunks/by-id/{id}/questions/regenerate")
    public ResponseEntity<List<GeneratedQuestionResponse>> regenerateGeneratedQuestions(
            @PathVariable("id") String id) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID is required"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        List<GeneratedQuestionResponse> items = chunkQuestion.regenerateChunkQuestions(chunkId)
                .stream()
                .map(GeneratedQuestionResponse::from)
                .toList();
        return ResponseEntity.ok(items);
    }

    /**
     * 一切 bind 失败（EOF/缺字段/畸形 JSON/null 字面量）都落固定文案
     * 「Question ID is required」——body 可省，缺字段/空体统一走该固定文案。
     */
    @DeleteMapping("/api/v1/chunks/by-id/{id}/questions")
    public ResponseEntity<Void> deleteGeneratedQuestion(
            @PathVariable("id") String id,
            @RequestBody(required = false) DeleteGeneratedQuestionRequest req) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        String questionId = req == null || req.questionId() == null ? "" : req.questionId();
        if (questionId.isEmpty()) {
            throw new BizException(AppError.badRequest("Question ID is required"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        chunkQuestion.deleteGeneratedQuestion(chunkId, questionId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 删除 ══════════════════════════

    @DeleteMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<Void> deleteChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        try {
            chunkEdit.deleteChunk(chunk.getId());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(errText(e)));
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<Void> deleteChunksByKnowledgeId(
            @PathVariable("knowledgeId") String knowledgeId) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        guard.requireOwnedChunkKbByKnowledge(kgId);
        guard.requireKbAccess(guard.kbIdFromKnowledgeParam(kgId));
        try {
            chunkEdit.deleteChunksByKnowledgeId(kgId);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(errText(e)));
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 共用 ══════════════════════════

    /** 取 chunk 并校验属于 URL 里的 :knowledge_id（同租户横向越权防线）；守卫链先行。 */
    private Chunk fetchChunkAndVerifyOwnership(String knowledgeId, String id) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        guard.requireOwnedChunkKbByKnowledge(kgId);
        guard.requireKbAccess(guard.kbIdFromKnowledgeParam(kgId));
        final Chunk chunk;
        try {
            chunk = chunkRead.getChunkById(tenantId(), chunkId);
        } catch (ChunkNotFoundException e) {
            throw new BizException(AppError.notFound("Chunk not found"));
        }
        if (!chunk.getKnowledgeId().equals(kgId)) {
            throw new BizException(AppError.forbidden("No permission to access this chunk"));
        }
        return chunk;
    }

    /** 更新/回滚的成功响应；knowledge 摘要重载失败仅 WARN（两键缺席）。 */
    private ResponseEntity<ChunkUpdateResponse> updatedResponse(Chunk chunk, String knowledgeId) {
        Knowledge knowledge = null;
        try {
            knowledge = chunkRead.findForSummaryReload(knowledgeId, tenantId());
        } catch (RuntimeException e) {
            log.warn("Chunk updated but failed to reload summary status for {}: {}",
                    LogSanitizer.sanitize(knowledgeId), errText(e));
        }
        if (knowledge == null) {
            return ResponseEntity.ok(new ChunkUpdateResponse(ChunkResponse.from(chunk), null, null));
        }
        return ResponseEntity.ok(new ChunkUpdateResponse(
                ChunkResponse.from(chunk),
                emptyToNull(knowledge.getDescription()),
                emptyToNull(knowledge.getSummaryStatus())));
    }

    /**
     * 分页 query 绑定：解析失败 → 400「page: 类型不正确」；&lt;1 →「必须为正整数」；
     * page=0 与缺省同义。message 统一「分页参数不合法」。
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw paginationError(field + ": 类型不正确");
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw paginationError(field + ": 必须为正整数");
        }
        if (withMax && value > 1000) {
            throw paginationError(field + ": 必须不大于 1000");
        }
        return (int) value;
    }

    private static BizException paginationError(String detail) {
        return new BizException(AppError.badRequest("分页参数不合法").withDetails(detail));
    }

    /** BizException 的 message 已是双前缀形态，直接用（500/400 面的 message=原文）。 */
    private static String errText(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 空串按"未设置"处理（契约：不用空串代替 null）。 */
    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }
}
