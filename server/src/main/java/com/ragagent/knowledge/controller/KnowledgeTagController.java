package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.ApiResponse;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.CreateTagRequest;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.DeleteTagRequest;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.TagPageResult;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.UpdateTagRequest;
import com.ragagent.knowledge.mapper.FaqChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
import com.ragagent.knowledge.service.ChunkAccessGuard;
import com.ragagent.knowledge.service.KnowledgeTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
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
 * KB 标签 CRUD 面：列表（分页/关键字）、创建、更新、删除（含排除条目）。
 * 读路由 = 拦截器 VIEWER 下限 + {@link ChunkAccessGuard#requireKbAccess}；
 * 写路由 = 所有权判定先行（非创建者且非 Admin+ → 403 纯字符串）再 KB 访问层，
 * 判定顺序为既有契约（golden 依赖）。
 */
@RestController
public class KnowledgeTagController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTagController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeTagService tagService;
    private final ChunkAccessGuard guard;
    private final ChunkRepository chunkRepo;
    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeTagRepository tagRepo;

    public KnowledgeTagController(KnowledgeTagService tagService,
                                  ChunkAccessGuard guard,
                                  ChunkRepository chunkRepo,
                            FaqChunkRepository faqChunkRepository,
                                  KnowledgeTagRepository tagRepo) {
        this.tagService = tagService;
        this.guard = guard;
        this.chunkRepo = chunkRepo;
        this.faqChunkRepository = faqChunkRepository;
        this.tagRepo = tagRepo;
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<ApiResponse<TagPageResult>> listTags(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireKbAccess(kbId);

        Integer pageValue = page == null ? null : bindPaginationInt(page);
        Integer pageSizeValue = pageSize == null ? null : bindPaginationInt(pageSize);

        TagPageResult result = tagService.listTags(kbId, pageValue, pageSizeValue,
                LogSanitizer.sanitize(keyword));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 分页 query：缺省/null 交给 service；非整数 → 400「page: 类型不正确」。 */
    private static int bindPaginationInt(String raw) {
        try {
            return Long.parseLong(raw.trim()) > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw paramError("分页参数不合法", "page: 类型不正确");
        }
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<ApiResponse<Object>> createTag(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody CreateTagRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        KnowledgeTag tag = tagService.createTag(kbId,
                LogSanitizer.sanitize(req.name()), LogSanitizer.sanitize(req.color()),
                req.sortOrder() == null ? 0 : req.sortOrder());
        return ResponseEntity.ok(ApiResponse.ok(
                com.ragagent.knowledge.dto.KnowledgeTagDtos.KnowledgeTagResponse.from(tag)));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/tags/{tag_id}")
    public ResponseEntity<ApiResponse<Object>> updateTag(
            @PathVariable("id") String id,
            @PathVariable("tag_id") String tagIdParam,
            @Valid @NonNullBody @RequestBody UpdateTagRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        String tagId = resolveTagId(LogSanitizer.sanitize(tagIdParam));
        KnowledgeTag tag = tagService.updateTag(tagId, req.name(), req.color(), req.sortOrder());
        return ResponseEntity.ok(ApiResponse.ok(
                com.ragagent.knowledge.dto.KnowledgeTagDtos.KnowledgeTagResponse.from(tag)));
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/tags/{tag_id}")
    public ResponseEntity<ApiResponse<Void>> deleteTag(
            @PathVariable("id") String id,
            @PathVariable("tag_id") String tagIdParam,
            @RequestParam(value = "force", required = false) String force,
            @RequestParam(value = "content_only", required = false) String contentOnly,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        String tagId = resolveTagId(LogSanitizer.sanitize(tagIdParam));
        boolean forceFlag = "true".equals(force);
        boolean contentOnlyFlag = "true".equals(contentOnly);

        // body 可省（EOF 容忍）；非 EOF 的绑定错误 → 400「删除选项不合法」（无 details）
        List<Long> excludeIds = parseExcludeIds(rawBody);

        // exclude_ids → chunk UUID 解析与作用域校验
        List<String> excludeUUIDs = resolveExcludeUUIDs(kbId, excludeIds);

        tagService.deleteTag(tagId, forceFlag, contentOnlyFlag, excludeUUIDs);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /** body 可省；null 体按空对象；解析失败 → 400「删除选项不合法」。 */
    private List<Long> parseExcludeIds(String rawBody) {
        if (rawBody == null || rawBody.isBlank() || "null".equals(rawBody.trim())) {
            return null;
        }
        try {
            DeleteTagRequest parsed = MAPPER.readValue(rawBody, DeleteTagRequest.class);
            return parsed == null ? null : parsed.excludeIds();
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("删除选项不合法"));
        }
    }

    /** 校验排除条目：非法 ID 400、他库/他租户 403、缺失 404；返回 chunk UUID 列表。 */
    private List<String> resolveExcludeUUIDs(String kbId, List<Long> excludeIds) {
        List<String> excludeUUIDs = new ArrayList<>();
        if (excludeIds == null || excludeIds.isEmpty()) {
            return excludeUUIDs;
        }
        long tenantId = TenantContext.currentTenantId();
        java.util.Map<Long, Boolean> wanted = new java.util.HashMap<>();
        for (Long seqId : excludeIds) {
            if (seqId == null || seqId <= 0) {
                throw new BizException(AppError.badRequest("排除条目 ID 必须为正整数"));
            }
            wanted.put(seqId, Boolean.TRUE);
        }
        List<Chunk> chunks = faqChunkRepository.listChunksBySeqId(tenantId, excludeIds);
        for (Chunk chunk : chunks) {
            if (chunk == null || chunk.getSeqId() == null || !wanted.containsKey(chunk.getSeqId())) {
                continue;
            }
            if (chunk.getTenantId() == null || chunk.getTenantId() != tenantId
                    || kbId.equals(chunk.getKnowledgeBaseId()) == false
                    || !"faq".equals(chunk.getChunkType())) {
                throw new BizException(AppError.forbidden("排除条目不属于当前知识库"));
            }
            excludeUUIDs.add(chunk.getId());
            wanted.remove(chunk.getSeqId());
        }
        if (!wanted.isEmpty()) {
            throw new BizException(AppError.notFound("排除条目不存在"));
        }
        return excludeUUIDs;
    }

    /**
     * tag_id 是整数 → 按 seq_id 解析（查不到 → 404「标签不存在」）；否则当 UUID
     * 原样透传（缺失由 service 层出 plain-500——既有行为）。
     */
    private String resolveTagId(String raw) {
        try {
            long seqId = Long.parseLong(raw);
            long tenantId = TenantContext.currentTenantId();
            KnowledgeTag tag = tagRepo.getBySeqId(tenantId, seqId);
            if (tag == null) {
                throw new BizException(AppError.notFound("标签不存在"));
            }
            return tag.getId();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** 400 信封（message 类别 + details 说明）。 */
    private static BizException paramError(String message, String details) {
        return new BizException(AppError.badRequest(message).withDetails(details));
    }

    /**
     * service 的普通 error（fmt.Errorf 族）→ 500 code=1007 固定文案
     * 「Internal server error」无 details 键（与 AppError 信封刻意不同，golden 锁定）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleTagPlainInternal(IllegalStateException ex) {
        log.error("Tag operation failed", ex);
        java.util.Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
