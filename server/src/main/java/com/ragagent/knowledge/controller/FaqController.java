package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.AddSimilarQuestionsRequest;
import com.ragagent.knowledge.dto.FaqEntry;
import com.ragagent.knowledge.dto.FaqEntryPage;
import com.ragagent.knowledge.dto.FaqDeleteRequest;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import com.ragagent.knowledge.dto.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.FaqEntryTagBatchRequest;
import com.ragagent.knowledge.dto.UpdateLastImportDisplayStatusRequest;
import com.ragagent.knowledge.dto.FaqBatchUpsertPayload;
import com.ragagent.knowledge.dto.FaqImportProgress;
import com.ragagent.knowledge.dto.FaqTaskStartResponse;
import com.ragagent.knowledge.dto.FaqSearchRequest;
import com.ragagent.knowledge.service.FaqEntryCommandService;
import com.ragagent.knowledge.service.FaqEntryQueryService;
import com.ragagent.knowledge.service.FaqImportService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ragagent.knowledge.security.ChunkAccessGuard;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.knowledge.task.KnowledgeTaskIds;

/**
 * FAQ 模块 HTTP 面：条目查询/命令/导入三服务的薄绑定层。读路由经
 * {@link #requireKbRead}、写路由经 {@link #requireKbWrite} 做访问控制；
 * 参数校验由 {@code @Valid} DTO 约束声明，异常统一走全局 400 处理器。
 */
@RestController
public class FaqController {

    private static final Logger log = LoggerFactory.getLogger(FaqController.class);

    private final FaqEntryQueryService faqEntryQuery;
    private final FaqEntryCommandService faqEntryCommand;
    private final FaqImportService faqImport;
    private final ChunkAccessGuard guard;

    public FaqController(FaqEntryQueryService faqEntryQuery,
                         FaqEntryCommandService faqEntryCommand,
                         FaqImportService faqImport,
                         ChunkAccessGuard guard) {
        this.faqEntryQuery = faqEntryQuery;
        this.faqEntryCommand = faqEntryCommand;
        this.faqImport = faqImport;
        this.guard = guard;
    }

    // ══════════════════════════ 读 ══════════════════════════

    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<FaqEntryPage> listEntries(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "pageSize", required = false) String pageSize,
            @RequestParam(value = "tagIds", required = false) String tagIds,
            @RequestParam(value = "tagId", required = false) String tagId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "searchField", required = false) String searchField,
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            @RequestParam(value = "isEnabled", required = false) String isEnabled) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);

        int pageValue = bindPagination(page, "page", false);
        int sizeValue = bindPagination(pageSize, "pageSize", true);
        if (pageValue < 1) {
            pageValue = 1;
        }
        if (sizeValue < 1) {
            sizeValue = 20;
        }
        if (sizeValue > 1000) {
            sizeValue = 1000;
        }

        List<String> tagUuids = parseCommaSeparatedTagIDs(tagIds);
        long legacyTagSeqId = 0;
        if (tagId != null && !tagId.isEmpty()) {
            try {
                legacyTagSeqId = Long.parseLong(tagId);
            } catch (NumberFormatException e) {
                throw new BizException(AppError.badRequest("tagId 必须是整数"));
            }
        }
        Boolean isEnabledFilter = parseOptionalFAQEnabled(isEnabled);

        return ResponseEntity.ok(faqEntryQuery.listEntries(kbId, pageValue, sizeValue, tagUuids,
                legacyTagSeqId, LogSanitizer.sanitize(keyword), LogSanitizer.sanitize(searchField),
                LogSanitizer.sanitize(sortOrder), isEnabledFilter));
    }

    /** 导出：CSV（默认，含 BOM）或 JSON（?format=json）。 */
    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries/export")
    public ResponseEntity<byte[]> exportEntries(
            @PathVariable("id") String id,
            @RequestParam(value = "format", required = false) String format) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);
        String fmt = format == null ? "" : format.toLowerCase().trim();
        if ("json".equals(fmt)) {
            byte[] jsonData = faqEntryQuery.exportJson(kbId);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Content-Disposition", "attachment; filename=faq_export.json")
                    .body(jsonData);
        }
        byte[] csvData = faqEntryQuery.exportCsv(kbId);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = new byte[bom.length + csvData.length];
        System.arraycopy(bom, 0, body, 0, bom.length);
        System.arraycopy(csvData, 0, body, bom.length, csvData.length);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=faq_export.csv")
                .body(body);
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public ResponseEntity<FaqEntry> getEntry(
            @PathVariable("id") String id, @PathVariable("entryId") String entryId) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);
        long entrySeqId = parseEntryId(entryId);
        return ResponseEntity.ok(faqEntryQuery.getEntry(kbId, entrySeqId));
    }

    /** 导入进度轮询面（前端 3 秒轮询依赖 404 语义与 data 内 snake_case 字段形状）。 */
    @GetMapping("/api/v1/faq/import/progress/{taskId}")
    public ResponseEntity<FaqImportProgress> getImportProgress(
            @PathVariable("taskId") String taskId) {
        String task = LogSanitizer.sanitize(taskId);
        requireTaskProgressTenant(task);
        return ResponseEntity.ok(faqImport.getImportProgress(task));
    }

    // ══════════════════════════ 写 ══════════════════════════

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<FaqTaskStartResponse> upsertEntries(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqBatchUpsertPayload req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        String taskId = faqImport.upsertEntries(kbId, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new FaqTaskStartResponse(taskId));
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entry")
    public ResponseEntity<FaqEntry> createEntry(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqEntryPayload req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        return ResponseEntity.status(HttpStatus.CREATED).body(faqEntryCommand.createEntry(kbId, req));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public ResponseEntity<FaqEntry> updateEntry(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            @Valid @NonNullBody @RequestBody FaqEntryPayload req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        long entrySeqId = parseEntryId(entryId);
        return ResponseEntity.ok(faqEntryCommand.updateEntry(kbId, entrySeqId, req));
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}/similar-questions")
    public ResponseEntity<FaqEntry> addSimilarQuestions(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            @Valid @NonNullBody @RequestBody AddSimilarQuestionsRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        long entrySeqId = parseEntryId(entryId);
        return ResponseEntity.ok(
                faqEntryCommand.addSimilarQuestions(kbId, entrySeqId, req.similarQuestions()));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/fields")
    public ResponseEntity<Void> updateEntryFieldsBatch(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqEntryFieldsBatchUpdate req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        faqEntryCommand.updateEntryFieldsBatch(kbId, req);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/tags")
    public ResponseEntity<Void> updateEntryTagBatch(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqEntryTagBatchRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        faqEntryCommand.updateEntryTagBatch(kbId, req.updates());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<Void> deleteEntries(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqDeleteRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        faqEntryCommand.deleteEntries(kbId, req.ids());
        return ResponseEntity.noContent().build();
    }

    /** matchCount 先钳 [10,200]（service 再钳 50）。 */
    @PostMapping("/api/v1/knowledge-bases/{id}/faq/search")
    public ResponseEntity<List<FaqEntry>> searchFAQ(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody FaqSearchRequest raw) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);
        FaqSearchRequest req = new FaqSearchRequest(LogSanitizer.sanitize(raw.queryText()),
                raw.vectorThreshold(),
                raw.matchCount() <= 0 ? 10 : Math.min(raw.matchCount(), 200),
                raw.firstPriorityTagIds(), raw.secondPriorityTagIds(), raw.onlyRecommended());
        return ResponseEntity.ok(faqEntryQuery.searchEntries(kbId, req));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/faq/import/last-result/display")
    public ResponseEntity<Void> updateLastImportResultDisplayStatus(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateLastImportDisplayStatusRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        faqImport.updateLastImportResultDisplayStatus(kbId, req.displayStatus());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 路由守卫 ══════════════════════════

    /** 读路由：解析 KB（404）→ API-Key 白名单 → 跨租户 403。 */
    private KnowledgeBase requireKbRead(String kbId) {
        return guard.requireKbAccess(kbId);
    }

    /**
     * 写路由：所有权判定先行（非创建者且非 Admin+ → 403 纯字符串），再 KB 访问层。
     * 判定顺序 契约样例依赖（faq-create-contrib / faq-list-cross），不能重排。
     */
    private KnowledgeBase requireKbWrite(String kbId) {
        guard.requireOwnedKbInCallerSpace(kbId);
        return guard.requireKbAccess(kbId);
    }

    // ══════════════════════════ 参数解析 ══════════════════════════

    /** 导入进度面：任务租户必须与调用方一致，否则 404（不泄露他租户任务存在性）。 */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenantId = KnowledgeTaskIds.taskTenantId(taskId);
        if (taskTenantId == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        Long callerTenantId = TenantContext.currentTenantId();
        if (callerTenantId == null || callerTenantId == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (taskTenantId.longValue() != callerTenantId.longValue()) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    /** 逗号分隔标签 ID：滤掉空段与 __untagged__ 哨兵。 */
    private static List<String> parseCommaSeparatedTagIDs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String p : raw.split(",", -1)) {
            String trimmed = p.trim();
            if (trimmed.isEmpty() || "__untagged__".equals(trimmed)) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }

    /** is_enabled：缺省 null；true/false（大小写/空白宽容），其余 400。 */
    private static Boolean parseOptionalFAQEnabled(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new BizException(AppError.badRequest("is_enabled must be true or false"));
    }

    private static long parseEntryId(String entryId) {
        try {
            return Long.parseLong(entryId);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("entry_id 必须是整数"));
        }
    }

    /**
     * 分页 query 绑定：解析失败 → 400「page: 类型不正确」；&lt;1 →「必须为正整数」
     * （page=0 与缺省同义，由调用方钳 1）；page_size 上限 1000。message 统一
     * 「分页参数不合法」。
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalidPagination(field + ": 类型不正确");
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw invalidPagination(field + ": 必须为正整数");
        }
        if (withMax && value > 1000) {
            throw invalidPagination(field + ": 必须不大于 1000");
        }
        return (int) value;
    }

    private static BizException invalidPagination(String errText) {
        return new BizException(AppError.badRequest("分页参数不合法").withDetails(errText));
    }

}
