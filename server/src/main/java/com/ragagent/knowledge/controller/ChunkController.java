package com.ragagent.knowledge.controller;

import java.util.List;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.dto.ChunkDtos.ChunkMessageResponse;
import com.ragagent.knowledge.dto.ChunkDtos.ChunkPageResponse;
import com.ragagent.knowledge.dto.ChunkDtos.ChunkUpdateResponse;
import com.ragagent.knowledge.dto.ChunkDtos.DeleteGeneratedQuestionRequest;
import com.ragagent.knowledge.dto.ChunkDtos.RevertChunkRequest;
import com.ragagent.knowledge.dto.ChunkDtos.UpdateChunkRequest;
import com.ragagent.knowledge.dto.ChunkDtos.UpsertGeneratedQuestionRequest;
import com.ragagent.knowledge.mapper.ChunkNotFoundException;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.service.ChunkAccessGuard;
import com.ragagent.knowledge.service.ChunkEditService;
import com.ragagent.knowledge.service.ChunkQuestionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final ChunkRepository chunkRepository;
    private final ChunkAccessGuard guard;
    private final KnowledgeMapper knowledgeMapper;

    public ChunkController(ChunkEditService chunkEdit,
                           ChunkQuestionService chunkQuestion,
                           ChunkRepository chunkRepository,
                           ChunkAccessGuard guard,
                           KnowledgeMapper knowledgeMapper) {
        this.chunkEdit = chunkEdit;
        this.chunkQuestion = chunkQuestion;
        this.chunkRepository = chunkRepository;
        this.guard = guard;
        this.knowledgeMapper = knowledgeMapper;
    }

    // ══════════════════════════ 读 ══════════════════════════

    /** 分页钳位：page&lt;1→1、size&lt;1→10、size&gt;100→100（size 的小值是合法值，非 clamp）。 */
    @GetMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<ChunkPageResponse<List<Chunk>>> listKnowledgeChunks(
            @PathVariable("knowledgeId") String knowledgeId,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String pageSize,
            @RequestParam(value = "chunk_type", required = false) List<String> chunkType) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        int pageValue = bindPagination(page, "page", false);
        if (pageValue < 1) {
            pageValue = 1;
        }
        int sizeValue = bindPagination(pageSize, "page_size", true);
        if (sizeValue < 1) {
            sizeValue = 10;
        }
        if (sizeValue > 100) {
            sizeValue = 100;
        }

        // Default to text chunks; callers may override via ?chunk_type=image_caption etc.
        List<String> types = (chunkType == null || chunkType.isEmpty())
                ? List.of("text") : chunkType;

        guard.requireKbAccess(guard.kbIdFromKnowledgeParam(kgId));

        ChunkRepository.ChunkPage result = chunkRepository.listPagedChunksByKnowledgeId(
                tenantId(), kgId, (pageValue - 1) * sizeValue, sizeValue,
                types, null, "", "", "", "", null);

        return ResponseEntity.ok(new ChunkPageResponse<>(
                result.items(), pageValue, sizeValue, true, result.total()));
    }

    @GetMapping("/api/v1/chunks/by-id/{id}")
    public ResponseEntity<ApiResponse<Chunk>> getChunkByIdOnly(@PathVariable("id") String id) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        final Chunk chunk;
        try {
            chunk = chunkRepository.getChunkByIdOnly(chunkId);
        } catch (ChunkNotFoundException e) {
            throw new BizException(AppError.notFound("Chunk not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(chunk));
    }

    @GetMapping("/api/v1/chunks/{knowledgeId}/{id}/revisions")
    public ResponseEntity<ApiResponse<List<ChunkRevision>>> listChunkRevisions(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        List<ChunkRevision> items = chunkEdit.listChunkRevisions(chunk.getId());
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    // ══════════════════════════ 更新 / 回滚 ══════════════════════════

    /** 业务失败（fmt.Errorf 族）→ 500 信封 message=原文。 */
    @PutMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<ChunkUpdateResponse<Chunk>> updateChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id,
            @Valid @RejectEmptyBody @RequestBody(required = false) UpdateChunkRequest req) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        UpdateChunkRequest body = req == null ? new UpdateChunkRequest(null, null, null) : req;
        Chunk updated;
        try {
            updated = chunkEdit.updateDocumentChunk(
                    chunk.getId(), body.content(), body.isEnabled(), body.expectedRevision());
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
    public ResponseEntity<ChunkUpdateResponse<Chunk>> revertChunk(
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
    public ResponseEntity<ApiResponse<GeneratedQuestion>> upsertGeneratedQuestion(
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
        return ResponseEntity.ok(ApiResponse.ok(item));
    }

    @PostMapping("/api/v1/chunks/by-id/{id}/questions/regenerate")
    public ResponseEntity<ApiResponse<List<GeneratedQuestion>>> regenerateGeneratedQuestions(
            @PathVariable("id") String id) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID is required"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        List<GeneratedQuestion> items = chunkQuestion.regenerateChunkQuestions(chunkId);
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    /**
     * 一切 bind 失败（EOF/缺字段/畸形 JSON/null 字面量）都落固定文案
     * 「Question ID is required」——body 可省，缺字段/空体统一走该固定文案。
     */
    @DeleteMapping("/api/v1/chunks/by-id/{id}/questions")
    public ResponseEntity<ChunkMessageResponse> deleteGeneratedQuestion(
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
        return ResponseEntity.ok(new ChunkMessageResponse("Generated question deleted", true));
    }

    // ══════════════════════════ 删除 ══════════════════════════

    @DeleteMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<ChunkMessageResponse> deleteChunk(
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
        return ResponseEntity.ok(new ChunkMessageResponse("Chunk deleted", true));
    }

    @DeleteMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<ChunkMessageResponse> deleteChunksByKnowledgeId(
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
        return ResponseEntity.ok(new ChunkMessageResponse("All chunks under knowledge deleted", true));
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
            chunk = chunkRepository.getChunkById(tenantId(), chunkId);
        } catch (ChunkNotFoundException e) {
            throw new BizException(AppError.notFound("Chunk not found"));
        }
        if (!chunk.getKnowledgeId().equals(kgId)) {
            throw new BizException(AppError.forbidden("No permission to access this chunk"));
        }
        return chunk;
    }

    /** 更新/回滚的成功响应；knowledge 摘要重载失败仅 WARN（两键缺席）。 */
    private ResponseEntity<ChunkUpdateResponse<Chunk>> updatedResponse(Chunk chunk, String knowledgeId) {
        Knowledge knowledge = null;
        try {
            knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, LogSanitizer.sanitize(knowledgeId))
                    .eq(Knowledge::getTenantId, tenantId())
                    .isNull(Knowledge::getDeletedAt)
                    .last("LIMIT 1"));
        } catch (RuntimeException e) {
            log.warn("Chunk updated but failed to reload summary status for {}: {}",
                    LogSanitizer.sanitize(knowledgeId), errText(e));
        }
        if (knowledge == null) {
            return ResponseEntity.ok(new ChunkUpdateResponse<>(chunk, null, true, null));
        }
        return ResponseEntity.ok(new ChunkUpdateResponse<>(chunk,
                knowledge.getDescription() == null ? "" : knowledge.getDescription(),
                true,
                knowledge.getSummaryStatus() == null ? "" : knowledge.getSummaryStatus()));
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

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }
}
