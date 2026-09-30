package com.ragagent.knowledge.controller;

import java.util.List;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.CreateTagRequest;
import com.ragagent.knowledge.dto.DeleteTagRequest;
import com.ragagent.knowledge.dto.KnowledgeTagResponse;
import com.ragagent.knowledge.dto.TagPageResult;
import com.ragagent.knowledge.dto.UpdateTagRequest;
import com.ragagent.knowledge.security.ChunkAccessGuard;
import com.ragagent.knowledge.service.KnowledgeTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * KB 标签 CRUD 面：列表（分页/关键字）、创建、更新、删除（含排除条目）。
 * 读路由 = 拦截器 VIEWER 下限 + {@link ChunkAccessGuard#requireKbAccess}；
 * 写路由 = 所有权判定先行（非创建者且非 Admin+ → 403 纯字符串）再 KB 访问层，
 * 判定顺序为既有契约（契约样例依赖）。
 */
@RestController
public class KnowledgeTagController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeTagService tagService;
    private final ChunkAccessGuard guard;

    public KnowledgeTagController(KnowledgeTagService tagService,
                                  ChunkAccessGuard guard) {
        this.tagService = tagService;
        this.guard = guard;
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<TagPageResult> listTags(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "pageSize", required = false) String pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireKbAccess(kbId);

        Integer pageValue = page == null ? null : bindPaginationInt(page);
        Integer pageSizeValue = pageSize == null ? null : bindPaginationInt(pageSize);

        return ResponseEntity.ok(tagService.listTags(kbId, pageValue, pageSizeValue,
                LogSanitizer.sanitize(keyword)));
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
    public ResponseEntity<KnowledgeTagResponse> createTag(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody CreateTagRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        KnowledgeTag tag = tagService.createTag(kbId,
                LogSanitizer.sanitize(req.name()), LogSanitizer.sanitize(req.color()),
                req.sortOrder() == null ? 0 : req.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeTagResponse.from(tag));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/tags/{tagId}")
    public ResponseEntity<KnowledgeTagResponse> updateTag(
            @PathVariable("id") String id,
            @PathVariable("tagId") String tagIdParam,
            @Valid @NonNullBody @RequestBody UpdateTagRequest req) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        String tagId = tagService.resolveTagId(LogSanitizer.sanitize(tagIdParam));
        KnowledgeTag tag = tagService.updateTag(tagId, req.name(), req.color(), req.sortOrder());
        return ResponseEntity.ok(KnowledgeTagResponse.from(tag));
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/tags/{tagId}")
    public ResponseEntity<Void> deleteTag(
            @PathVariable("id") String id,
            @PathVariable("tagId") String tagIdParam,
            @RequestParam(value = "force", required = false) String force,
            @RequestParam(value = "contentOnly", required = false) String contentOnly,
            @RequestBody(required = false) DeleteTagRequest request) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        String tagId = tagService.resolveTagId(LogSanitizer.sanitize(tagIdParam));
        boolean forceFlag = "true".equals(force);
        boolean contentOnlyFlag = "true".equals(contentOnly);

        // body 可整体省略；excludeIds 缺省 = 不排除任何条目
        List<Long> excludeIds = request == null ? null : request.excludeIds();

        // exclude_ids → chunk UUID 解析与作用域校验
        List<String> excludeUUIDs = tagService.resolveExcludeUUIDs(kbId, excludeIds);

        tagService.deleteTag(tagId, forceFlag, contentOnlyFlag, excludeUUIDs);
        return ResponseEntity.noContent().build();
    }



    /** 400 信封（message 类别 + details 说明）。 */
    private static BizException paramError(String message, String details) {
        return new BizException(AppError.badRequest(message).withDetails(details));
    }

}
