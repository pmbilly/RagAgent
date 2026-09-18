package com.ragagent.knowledge.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.ChunkNotFoundException;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.service.ChunkAccessGuard;
import com.ragagent.knowledge.service.ChunkService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * chunk 路由（对照 Go internal/handler/chunk.go L40-472 + routes_knowledge.go L27-53）。
 *
 * <p><b>守卫链在控制器内复刻</b>（Go 挂在路由上的两段中间件，Java 的等价分层见
 * {@link ChunkAccessGuard} 的类注释）：写路径 = ownership → KB 访问 → handler；
 * 读路径 = KB 访问 → handler。判定顺序与 Go 的中间件链逐层一致，golden 依赖顺序。</p>
 *
 * <p><b>错误形态对照</b>（golden 锁定）：</p>
 * <ul>
 *   <li>handler 内 AppError → 404/403/409/400 信封（BizException 直通）；</li>
 *   <li>{@code fetchChunkAndVerifyOwnership} 的 chunk 与 knowledge_id 不符 → 403 信封
 *       "No permission to access this chunk"；</li>
 *   <li>{@code UpdateDocumentChunk} 的业务失败（空内容/加图/非 text/超长）在 Go 是
 *       {@code fmt.Errorf} → 500 信封 code=1007 且 message=原文；而 revert/questions 的
 *       同类错误被各自的 handler 包成 <b>400</b>——同一个 service 异常在两个端点的
 *       HTTP 形态刻意不同，异常映射按端点分开写；</li>
 *   <li>ownership 守卫拒绝 → 403 纯字符串（{@code GuardForbiddenException}）。</li>
 * </ul>
 *
 * <p><b>与 Go 的分层差异</b>：list/by-id 两条纯读端点直接用
 * {@link ChunkRepository}（Go 的 service.ListPagedChunksByKnowledgeID /
 * GetChunkByIDOnly 是仓储透传，Java 免去一层转发），其余走 {@link ChunkService}。</p>
 */
@RestController
public class ChunkController {

    private static final Logger log = LoggerFactory.getLogger(ChunkController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChunkService chunkService;
    private final ChunkRepository chunkRepository;
    private final ChunkAccessGuard guard;
    private final KnowledgeMapper knowledgeMapper;

    public ChunkController(ChunkService chunkService,
                           ChunkRepository chunkRepository,
                           ChunkAccessGuard guard,
                           KnowledgeMapper knowledgeMapper) {
        this.chunkService = chunkService;
        this.chunkRepository = chunkRepository;
        this.guard = guard;
        this.knowledgeMapper = knowledgeMapper;
    }

    // ══════════════════════════ 读 ══════════════════════════

    /** 对照 ListKnowledgeChunks（L99-152）。分页钳位：page&lt;1→1、size&lt;1→10、size&gt;100→100。 */
    @GetMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<Map<String, Object>> listKnowledgeChunks(
            @PathVariable("knowledgeId") String knowledgeId,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String pageSize,
            @RequestParam(value = "chunk_type", required = false) List<String> chunkType) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        // 对照 Go L117-125 的三段 if（不是 clamp：page 无上限、size 的小值是合法值）
        int pageValue = bindPagination(page, "Page", false);
        if (pageValue < 1) {
            pageValue = 1;
        }
        int sizeValue = bindPagination(pageSize, "PageSize", true);
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

        long tenantId = tenantId();
        ChunkRepository.ChunkPage result = chunkRepository.listPagedChunksByKnowledgeId(
                tenantId, kgId, (pageValue - 1) * sizeValue, sizeValue,
                types, null, "", "", "", "", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.items());
        body.put("page", pageValue);
        body.put("page_size", sizeValue);
        body.put("success", true);
        body.put("total", result.total());
        return ResponseEntity.ok(body);
    }

    /** 对照 GetChunkByIDOnly（L53-83）：不需要 knowledge_id。 */
    @GetMapping("/api/v1/chunks/by-id/{id}")
    public ResponseEntity<Map<String, Object>> getChunkByIdOnly(@PathVariable("id") String id) {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", chunk);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 ListChunkRevisions（L257-269）。 */
    @GetMapping("/api/v1/chunks/{knowledgeId}/{id}/revisions")
    public ResponseEntity<Map<String, Object>> listChunkRevisions(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        List<ChunkRevision> items = chunkService.listChunkRevisions(chunk.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════════════════ 更新 / 回滚 ══════════════════════════

    /** 对照 UpdateChunk（L211-255）。业务失败（fmt.Errorf 族）→ 500 信封 message=原文。 */
    @PutMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<Map<String, Object>> updateChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        UpdateChunkRequest req = bindBody(rawBody, UpdateChunkRequest.class);
        if (req == null) {
            // body 为 null 字面量：Go 零值绑定不报错（全指针字段 = 全 nil = 无变更）
            req = new UpdateChunkRequest(null, null, null);
        }
        Chunk updated;
        try {
            updated = chunkService.updateDocumentChunk(
                    chunk.getId(), req.content, req.isEnabled, req.expectedRevision);
        } catch (ChunkRevisionConflictException e) {
            throw new BizException(AppError.conflict(
                    "Chunk was modified by another user; refresh and retry"));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // Go：非 AppError → NewInternalServerError(err.Error())（500 且 message=原文）
            throw new BizException(AppError.internal(errText(e)));
        }
        return updatedResponse(updated, knowledgeId);
    }

    /** 对照 RevertChunk（L276-315）。注意：非 AppError 在这里是 **400** 不是 500。 */
    @PostMapping("/api/v1/chunks/{knowledgeId}/{id}/revert")
    public ResponseEntity<Map<String, Object>> revertChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        RevertChunkRequest req = bindBody(rawBody, RevertChunkRequest.class);
        if (req == null) {
            req = new RevertChunkRequest(null, null);
        }
        if (req.revision == null) {
            throw new BizException(AppError.badRequest(
                    "Key: 'RevertChunkRequest.Revision' Error:Field validation for "
                            + "'Revision' failed on the 'required' tag"));
        }
        if (req.revision < 0) {
            throw new BizException(AppError.badRequest(
                    "revision must be a non-negative integer"));
        }
        Chunk updated;
        try {
            updated = chunkService.revertDocumentChunk(chunk.getId(), req.revision,
                    req.expectedRevision);
        } catch (ChunkRevisionConflictException e) {
            throw new BizException(AppError.conflict(
                    "Chunk was modified by another user; refresh and retry"));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // Go：非 AppError → NewBadRequestError(err.Error())——revert 特有
            throw new BizException(AppError.badRequest(errText(e)));
        }
        return updatedResponse(updated, knowledgeId);
    }

    // ══════════════════════════ 生成问题 ══════════════════════════

    /** 对照 UpsertGeneratedQuestion（L322-339）。 */
    @PutMapping("/api/v1/chunks/by-id/{id}/questions")
    public ResponseEntity<Map<String, Object>> upsertGeneratedQuestion(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID is required"));
        }
        UpsertGeneratedQuestionRequest req = bindBody(rawBody,
                UpsertGeneratedQuestionRequest.class);
        if (req == null) {
            req = new UpsertGeneratedQuestionRequest(null, null);
        }
        if (req.question == null || req.question.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'UpsertGeneratedQuestionRequest.Question' Error:Field validation "
                            + "for 'Question' failed on the 'required' tag"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        GeneratedQuestion item = chunkService.upsertGeneratedQuestion(
                chunkId, req.questionId == null ? "" : req.questionId, req.question);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", item);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 RegenerateGeneratedQuestions（L341-353）。 */
    @PostMapping("/api/v1/chunks/by-id/{id}/questions/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateGeneratedQuestions(
            @PathVariable("id") String id) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID is required"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        List<GeneratedQuestion> items = chunkService.regenerateChunkQuestions(chunkId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 DeleteGeneratedQuestion（L440-472）：任何 bind 失败都落固定文案。 */
    @DeleteMapping("/api/v1/chunks/by-id/{id}/questions")
    public ResponseEntity<Map<String, Object>> deleteGeneratedQuestion(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String chunkId = LogSanitizer.sanitize(id);
        if (chunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        String questionId;
        try {
            // Go：bind 失败（EOF/缺字段/畸形 JSON/null 字面量）一律 "Question ID is required"
            DeleteGeneratedQuestionRequest req =
                    bindBody(rawBody, DeleteGeneratedQuestionRequest.class);
            questionId = req == null ? null : req.questionId;
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("Question ID is required"));
        }
        if (questionId == null || questionId.isEmpty()) {
            throw new BizException(AppError.badRequest("Question ID is required"));
        }
        guard.requireOwnedChunkKbByChunk(chunkId);
        guard.requireKbAccess(guard.kbIdFromChunkParam(chunkId));
        chunkService.deleteGeneratedQuestion(chunkId, questionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Generated question deleted");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════════════════ 删除 ══════════════════════════

    /** 对照 DeleteChunk（L369-389）。 */
    @DeleteMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ResponseEntity<Map<String, Object>> deleteChunk(
            @PathVariable("knowledgeId") String knowledgeId,
            @PathVariable("id") String id) {
        Chunk chunk = fetchChunkAndVerifyOwnership(knowledgeId, id);
        try {
            chunkService.deleteChunk(chunk.getId());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(errText(e)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Chunk deleted");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 DeleteChunksByKnowledgeID（L403-424）。 */
    @DeleteMapping("/api/v1/chunks/{knowledgeId}")
    public ResponseEntity<Map<String, Object>> deleteChunksByKnowledgeId(
            @PathVariable("knowledgeId") String knowledgeId) {
        String kgId = LogSanitizer.sanitize(knowledgeId);
        if (kgId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        guard.requireOwnedChunkKbByKnowledge(kgId);
        guard.requireKbAccess(guard.kbIdFromKnowledgeParam(kgId));
        try {
            chunkService.deleteChunksByKnowledgeId(kgId);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(errText(e)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "All chunks under knowledge deleted");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════════════════ 共用 ══════════════════════════

    /**
     * 对照 {@code fetchChunkAndVerifyOwnership}（L167-194）：取 chunk 并校验属于 URL 里的
     * {@code :knowledge_id}（同租户横向越权防线）。调用方先跑守卫链（ownership → KB 访问）。
     */
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

    /**
     * update/revert 的成功响应：gin.H（map → 字母序 data &lt; description &lt; success &lt;
     * summary_status）；knowledge 重载失败只 WARN，两个键整体缺席（对照 Go L245-254）。
     */
    private ResponseEntity<Map<String, Object>> updatedResponse(Chunk chunk, String knowledgeId) {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", chunk);
        if (knowledge != null) {
            body.put("description", knowledge.getDescription() == null
                    ? "" : knowledge.getDescription());
            body.put("success", true);
            body.put("summary_status", knowledge.getSummaryStatus() == null
                    ? "" : knowledge.getSummaryStatus());
        } else {
            body.put("success", true);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * questions 三端点的错误说明：Go handler 把**一切**service 错误包成
     * {@code NewBadRequestError(err.Error())}（400 信封）。Java 侧 service 的对应失败
     * 已是 BizException.badRequest(原文)，直通即可，无需再映射。
     */

    /** 对照 types.Pagination 的 gin form 绑定（omitempty,min=1[,max=1000]）。 */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest(
                    "strconv.ParseInt: parsing \"" + raw + "\": invalid syntax"));
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw new BizException(AppError.badRequest(
                    "Key: 'Pagination." + field + "' Error:Field validation for '"
                            + field + "' failed on the 'min' tag"));
        }
        if (withMax && value > 1000) {
            throw new BizException(AppError.badRequest(
                    "Key: 'Pagination." + field + "' Error:Field validation for '"
                            + field + "' failed on the 'max' tag"));
        }
        return (int) value;
    }

    /** handler 的三段钳位已内联（对照 L117-125）。 */

    private <T> T bindBody(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        try {
            T value = MAPPER.readValue(rawBody, type);
            return value;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    /** Go err.Error() 的 Java 对位：BizException 的 message 已是双前缀形态，直接用。 */
    private static String errText(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ══════════════════════════ 请求体 ══════════════════════════

    /** 对照 UpdateChunkRequest（L155-159）：全指针字段，三态。 */
    public record UpdateChunkRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
            @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_revision")
            Integer expectedRevision) {
    }

    /** 对照 RevertChunkRequest（L271-274）：revision 带 binding:required（controller 判 null）。 */
    public record RevertChunkRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("revision") Integer revision,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_revision")
            Integer expectedRevision) {
    }

    /** 对照 UpsertGeneratedQuestionRequest（L317-320）。 */
    public record UpsertGeneratedQuestionRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("question_id") String questionId,
            @com.fasterxml.jackson.annotation.JsonProperty("question") String question) {
    }

    /** 对照 DeleteGeneratedQuestion 的匿名结构体（L451-453）。 */
    public record DeleteGeneratedQuestionRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("question_id") String questionId) {
    }
}
