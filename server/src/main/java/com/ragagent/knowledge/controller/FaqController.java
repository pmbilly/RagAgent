package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.dto.FaqDtos.AddSimilarQuestionsRequest;
import com.ragagent.knowledge.dto.FaqDtos.FaqDeleteRequest;
import com.ragagent.knowledge.dto.FaqDtos.FaqEntryPayload;
import com.ragagent.knowledge.dto.FaqDtos.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.FaqDtos.FaqSearchRequest;
import com.ragagent.knowledge.dto.FaqDtos.FaqBatchUpsertPayload;
import com.ragagent.knowledge.dto.FaqDtos.FaqEntryTagBatchRequest;
import com.ragagent.knowledge.dto.FaqDtos.UpdateLastImportDisplayStatusRequest;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.FaqEntryCommandService;
import com.ragagent.knowledge.service.FaqEntryQueryService;
import com.ragagent.knowledge.service.FaqImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * FAQ 模块 HTTP 面（波 2 第四批，对照 Go internal/handler/faq.go，607 行）。
 *
 * <h2>错误形态（与 ChunkController 刻意不同，逐 handler 对照）</h2>
 * <ul>
 *   <li><b>binding/校验失败</b> → 400 信封 {@code message="请求参数不合法" +
 *     details=err.Error()}（gin 的 ShouldBindJSON 原文——EOF / validator / strconv）。
 *     部分文案不同：list 是 "分页参数不合法"、tag_id/entry_id 是专用句子（details=null）。</li>
 *   <li><b>service 的 AppError</b> → 信封（BizException 直通）。</li>
 *   <li><b>service 的普通 error</b> → <b>500 code=1007 固定 "Internal server error"
 *     无 details 键</b>（Go 全局 ErrorHandler 的非 AppError 分支，golden 实测）——
 *     用 {@link #handleFaqInternal} 输出，与 ChunkController 的 message=原文形态<b>不同</b>。</li>
 * </ul>
 *
 * <p>路由守卫（Viewer+ 读 / OwnedKBOrAdmin+KBAccessWrite 写）由 RbacInterceptor +
 * controller 内守卫调用承担——FAQ 的 :id 是 KB id，Ownership 判定用
 * {@code OwnedKBOrAdmin}（route 层语义已在 RbacInterceptor 角色下限 + 守卫链实现）。</p>
 */
@RestController
public class FaqController {

    private static final Logger log = LoggerFactory.getLogger(FaqController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FaqEntryQueryService faqEntryQuery;
    private final FaqEntryCommandService faqEntryCommand;
    private final FaqImportService faqImport;
    private final com.ragagent.knowledge.service.ChunkAccessGuard guard;

    public FaqController(FaqEntryQueryService faqEntryQuery,
                         FaqEntryCommandService faqEntryCommand,
                         FaqImportService faqImport,
                         com.ragagent.knowledge.service.ChunkAccessGuard guard) {
        this.faqEntryQuery = faqEntryQuery;
        this.faqEntryCommand = faqEntryCommand;
        this.faqImport = faqImport;
        this.guard = guard;
    }

    // ══════════════════════════ 读 ══════════════════════════

    /** 对照 ListEntries（faq.go L81-123）。 */
    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<Map<String, Object>> listEntries(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String pageSize,
            @RequestParam(value = "tag_ids", required = false) String tagIds,
            @RequestParam(value = "tag_id", required = false) String tagId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "search_field", required = false) String searchField,
            @RequestParam(value = "sort_order", required = false) String sortOrder,
            @RequestParam(value = "is_enabled", required = false) String isEnabled) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);

        int pageValue = bindPagination(page, "Page", false);
        int sizeValue = bindPagination(pageSize, "PageSize", true);
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
                throw new BizException(AppError.badRequest("tag_id 必须是整数"));
            }
        }
        Boolean isEnabledFilter = parseOptionalFAQEnabled(isEnabled);

        Object result = faqEntryQuery.listEntries(kbId, pageValue, sizeValue, tagUuids, legacyTagSeqId,
                LogSanitizer.sanitize(keyword), LogSanitizer.sanitize(searchField),
                LogSanitizer.sanitize(sortOrder), isEnabledFilter);
        return ResponseEntity.ok(successData(result));
    }

    /** 对照 ExportEntries（faq.go L425-456）：CSV（默认，含 BOM）或 JSON（?format=json）。 */
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

    /** 对照 GetEntry（faq.go L472-493）。 */
    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public ResponseEntity<Map<String, Object>> getEntry(
            @PathVariable("id") String id, @PathVariable("entryId") String entryId) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);
        long entrySeqId = parseEntryId(entryId);
        return ResponseEntity.ok(successData(faqEntryQuery.getEntry(kbId, entrySeqId)));
    }

    /** 对照 GetImportProgress（faq.go L507-526）：requireTaskProgressTenant 先于 service 查询。 */
    @GetMapping("/api/v1/faq/import/progress/{taskId}")
    public ResponseEntity<Map<String, Object>> getImportProgress(
            @PathVariable("taskId") String taskId) {
        String task = LogSanitizer.sanitize(taskId);
        requireTaskProgressTenant(task);
        return ResponseEntity.ok(successData(faqImport.getImportProgress(task)));
    }

    // ══════════════════════════ 写 ══════════════════════════

    /** 对照 UpsertEntries（faq.go L159-184）：异步导入，返回 task_id。 */
    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<Map<String, Object>> upsertEntries(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqBatchUpsertPayload req = bindUpsertPayload(rawBody);
        String taskId = faqImport.upsertEntries(kbId, req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        return ResponseEntity.ok(successData(data));
    }

    /** 对照 CreateEntry（faq.go L199-221）：同步创建。 */
    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entry")
    public ResponseEntity<Map<String, Object>> createEntry(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqEntryPayload req = bindEntryPayload(rawBody);
        return ResponseEntity.ok(successData(faqEntryCommand.createEntry(kbId, req)));
    }

    /** 对照 UpdateEntry（faq.go L237-265）：先 binding 后 entry_id 解析（Go 的顺序）。 */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public ResponseEntity<Map<String, Object>> updateEntry(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqEntryPayload req = bindEntryPayload(rawBody);
        long entrySeqId = parseEntryId(entryId);
        return ResponseEntity.ok(successData(faqEntryCommand.updateEntry(kbId, entrySeqId, req)));
    }

    /** 对照 AddSimilarQuestions（faq.go L579-607）：entry_id 解析在 binding 之前。 */
    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}/similar-questions")
    public ResponseEntity<Map<String, Object>> addSimilarQuestions(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        long entrySeqId = parseEntryId(entryId);
        AddSimilarQuestionsRequest req = bindBody(rawBody, AddSimilarQuestionsRequest.class);
        if (req == null) {
            req = new AddSimilarQuestionsRequest(null);
        }
        validator(req.similarQuestions() == null, "Key: 'addSimilarQuestionsRequest.SimilarQuestions' "
                + "Error:Field validation for 'SimilarQuestions' failed on the 'required' tag");
        validator(req.similarQuestions().isEmpty(), "Key: 'addSimilarQuestionsRequest.SimilarQuestions' "
                + "Error:Field validation for 'SimilarQuestions' failed on the 'min' tag");
        return ResponseEntity.ok(successData(
                faqEntryCommand.addSimilarQuestions(kbId, entrySeqId, req.similarQuestions())));
    }

    /** 对照 UpdateEntryFieldsBatch（faq.go L300-331）。 */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/fields")
    public ResponseEntity<Map<String, Object>> updateEntryFieldsBatch(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqEntryFieldsBatchUpdate req = bindBody(rawBody, FaqEntryFieldsBatchUpdate.class);
        faqEntryCommand.updateEntryFieldsBatch(kbId, req);
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 UpdateEntryTagBatch（faq.go L280-298）。 */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/tags")
    public ResponseEntity<Map<String, Object>> updateEntryTagBatch(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqEntryTagBatchRequest req = bindBody(rawBody, FaqEntryTagBatchRequest.class);
        if (req == null || req.updates() == null) {
            throw invalidRequest("Key: 'faqEntryTagBatchRequest.Updates' Error:Field validation "
                    + "for 'Updates' failed on the 'required' tag");
        }
        if (req.updates().isEmpty()) {
            throw invalidRequest("Key: 'faqEntryTagBatchRequest.Updates' Error:Field validation "
                    + "for 'Updates' failed on the 'min' tag");
        }
        faqEntryCommand.updateEntryTagBatch(kbId, req.updates());
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 DeleteEntries（faq.go L346-366）。 */
    @DeleteMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ResponseEntity<Map<String, Object>> deleteEntries(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        FaqDeleteRequest req = bindBody(rawBody, FaqDeleteRequest.class);
        if (req == null || req.ids() == null) {
            throw invalidRequest("Key: 'faqDeleteRequest.IDs' Error:Field validation for 'IDs' "
                    + "failed on the 'required' tag");
        }
        if (req.ids().isEmpty()) {
            throw invalidRequest("Key: 'faqDeleteRequest.IDs' Error:Field validation for 'IDs' "
                    + "failed on the 'min' tag");
        }
        faqEntryCommand.deleteEntries(kbId, req.ids());
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 SearchFAQ（faq.go L381-409）：matchCount 先钳 [10,200]（service 再钳 50）。 */
    @PostMapping("/api/v1/knowledge-bases/{id}/faq/search")
    public ResponseEntity<Map<String, Object>> searchFAQ(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbRead(kbId);
        FaqSearchRequest req = bindBody(rawBody, FaqSearchRequest.class);
        if (req == null) {
            req = new FaqSearchRequest(null, 0, 0, null, null, false);
        }
        validator(req.queryText() == null || req.queryText().isEmpty(),
                "Key: 'FAQSearchRequest.QueryText' Error:Field validation for 'QueryText' "
                        + "failed on the 'required' tag");
        req = new FaqSearchRequest(LogSanitizer.sanitize(req.queryText()), req.vectorThreshold(),
                req.matchCount() <= 0 ? 10 : Math.min(req.matchCount(), 200),
                req.firstPriorityTagIds(), req.secondPriorityTagIds(), req.onlyRecommended());
        return ResponseEntity.ok(successData(faqEntryQuery.searchEntries(kbId, req)));
    }

    /** 对照 UpdateLastImportResultDisplayStatus（faq.go L542-562）。 */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/import/last-result/display")
    public ResponseEntity<Map<String, Object>> updateLastImportResultDisplayStatus(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        requireKbWrite(kbId);
        UpdateLastImportDisplayStatusRequest req =
                bindBody(rawBody, UpdateLastImportDisplayStatusRequest.class);
        if (req == null || req.displayStatus() == null || req.displayStatus().isEmpty()) {
            throw invalidRequest("Key: 'updateLastFAQImportResultDisplayStatusRequest.DisplayStatus' "
                    + "Error:Field validation for 'DisplayStatus' failed on the 'required' tag");
        }
        validator(!"open".equals(req.displayStatus()) && !"close".equals(req.displayStatus()),
                "Key: 'updateLastFAQImportResultDisplayStatusRequest.DisplayStatus' "
                        + "Error:Field validation for 'DisplayStatus' failed on the 'oneof' tag");
        faqImport.updateLastImportResultDisplayStatus(kbId, req.displayStatus());
        return ResponseEntity.ok(successOnly());
    }

    // ══════════════════════════ 路由守卫（对照 Go 中间件链） ══════════════════════════

    /**
     * 读路由（KBAccessRead）：解析 KB（404 小写 "knowledge base not found"）→
     * API-Key 白名单 → 跨租户 403 "Permission denied to access this knowledge base"。
     */
    private KnowledgeBase requireKbRead(String kbId) {
        return guard.requireKbAccess(kbId);
    }

    /**
     * 写路由（OwnedKBOrAdmin → KBAccessWrite）：所有权在调用者空间查不到 → 放行
     * （交给 KBAccess 层出 404/403）；存在但非创建者且非 Admin+ → 403 纯字符串。
     * 判定顺序 golden 依赖（faq-create-contrib / faq-list-cross），不能重排。
     */
    private KnowledgeBase requireKbWrite(String kbId) {
        guard.requireOwnedKbInCallerSpace(kbId);
        return guard.requireKbAccess(kbId);
    }

    // ══════════════════════════ 绑定/校验辅助 ══════════════════════════

    /**
     * FAQEntryPayload 的绑定（faq.go L203-207）：Go 的 ShouldBindJSON 一并做解析与
     * validator——standard_question required（string 非零值）。
     */
    private FaqEntryPayload bindEntryPayload(String rawBody) {
        FaqEntryPayload req = bindBody(rawBody, FaqEntryPayload.class);
        if (req == null) {
            // null 字面量：Go 零值绑定不报错，落到 validator
            req = new FaqEntryPayload(null, null, null, null, null, null, 0, null, null, null);
        }
        if (req.standardQuestion() == null || req.standardQuestion().isEmpty()) {
            throw invalidRequest("Key: 'FAQEntryPayload.StandardQuestion' Error:Field validation "
                    + "for 'StandardQuestion' failed on the 'required' tag");
        }
        return req;
    }

    /** FAQBatchUpsertPayload 的绑定（entries required / mode oneof=append replace，struct 序）。 */
    private FaqBatchUpsertPayload bindUpsertPayload(String rawBody) {
        FaqBatchUpsertPayload req = bindBody(rawBody, FaqBatchUpsertPayload.class);
        if (req == null) {
            req = new FaqBatchUpsertPayload(null, "", null, "", false);
        }
        List<String> failures = new ArrayList<>();
        if (req.entries() == null) {
            failures.add("Key: 'FAQBatchUpsertPayload.Entries' Error:Field validation for 'Entries' "
                    + "failed on the 'required' tag");
        }
        if (!"append".equals(req.mode()) && !"replace".equals(req.mode())) {
            failures.add("Key: 'FAQBatchUpsertPayload.Mode' Error:Field validation for 'Mode' "
                    + "failed on the 'oneof' tag");
        }
        if (!failures.isEmpty()) {
            throw invalidRequest(String.join("\n", failures));
        }
        return req;
    }

    /**
     * FAQSearchRequest 的绑定：query_text required（string 非零值——空串/缺失都失败）。
     */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenantId = com.ragagent.knowledge.service.KnowledgeTaskIds.taskTenantId(taskId);
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

    /** 对照 parseCommaSeparatedTagIDs（knowledge.go L2546-2560）：滤掉空与 __untagged__。 */
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

    /** 对照 parseOptionalFAQEnabled（faq.go L127-143）：缺省=null；true/false（大小写/空白宽容）。 */
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

    /** 对照 entry_id 的 strconv.ParseInt（400 专用文案，details=null）。 */
    private static long parseEntryId(String entryId) {
        try {
            return Long.parseLong(entryId);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("entry_id 必须是整数"));
        }
    }

    /**
     * 对照 types.Pagination 的 query 绑定（分两步：strconv 原文 → validator min/max）。
     * 与 ChunkController.bindPagination 的差别：错误包在 "分页参数不合法" + details 里。
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalidPagination("strconv.ParseInt: parsing \"" + raw + "\": invalid syntax");
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw invalidPagination("Key: 'Pagination." + field + "' Error:Field validation for '"
                    + field + "' failed on the 'min' tag");
        }
        if (withMax && value > 1000) {
            throw invalidPagination("Key: 'Pagination." + field + "' Error:Field validation for '"
                    + field + "' failed on the 'max' tag");
        }
        return (int) value;
    }

    /** 400 信封：message="请求参数不合法"、details=err.Error()（FAQ handler 的统一形态）。 */
    private static BizException invalidRequest(String errText) {
        return new BizException(AppError.badRequest("请求参数不合法").withDetails(errText));
    }

    /** list 端点的分页 binding 专用：message 是 "分页参数不合法"。 */
    private static BizException invalidPagination(String errText) {
        return new BizException(AppError.badRequest("分页参数不合法").withDetails(errText));
    }

    private static void validator(boolean failed, String errText) {
        if (failed) {
            throw invalidRequest(errText);
        }
    }

    /** raw → DTO；空 body → Go 的 EOF；null 字面量 → 返回 null（调用方按零值处理）。 */
    private <T> T bindBody(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidRequest("EOF");
        }
        try {
            if ("null".equals(rawBody.trim())) {
                return null;
            }
            return MAPPER.readValue(rawBody, type);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalidRequest(GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /** gin.H{"success": true, "data": ...}：map 键字母序 data < success。 */
    private static Map<String, Object> successData(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    /** gin.H{"success": true}。 */
    private static Map<String, Object> successOnly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    // ══════════════════════════ 错误形态分支 ══════════════════════════

    /**
     * 非 AppError（fmt.Errorf 族）→ Go 全局 ErrorHandler 的 plain 分支：
     * 500 + {@code {"error":{"code":1007,"message":"Internal server error"},"success":false}}
     * （<b>无 details 键</b>——与 AppError 信封的 "details":null 刻意不同，golden 实录）。
     * 本 handler 只处理本控制器的异常，不污染全局（common 文件零改动）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleFaqPlainInternal(IllegalStateException ex) {
        log.error("FAQ operation failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
