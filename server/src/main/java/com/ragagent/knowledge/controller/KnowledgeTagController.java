package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.KnowledgeTagResponse;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.TagPageResult;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
import com.ragagent.knowledge.service.ChunkAccessGuard;
import com.ragagent.knowledge.service.KnowledgeTagService;
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
 * KB 标签 CRUD 面（W5a，对照 Go internal/handler/tag.go，292 行 4 端点）：
 *
 * <ul>
 *   <li>GET    /knowledge-bases/{id}/tags          —— ListTags（tag.go L87-111）</li>
 *   <li>POST   /knowledge-bases/{id}/tags          —— CreateTag（L132-157）</li>
 *   <li>PUT    /knowledge-bases/{id}/tags/{tag_id} —— UpdateTag（L179-208）</li>
 *   <li>DELETE /knowledge-bases/{id}/tags/{tag_id} —— DeleteTag（L226-289）</li>
 * </ul>
 *
 * <h2>路由守卫（routes_knowledge.go RegisterKnowledgeTagRoutes L264-283）</h2>
 * 读 = g.Viewer() + KBAccessRead → 拦截器 VIEWER 下限 + {@link ChunkAccessGuard#requireKbAccess}；
 * 写 = g.OwnedKBOrAdmin + KBAccessWrite（无角色门）→ 拦截器 VIEWER 下限 +
 * {@code requireOwnedKbInCallerSpace → requireKbAccess}（FAQ 控制器同款链）。
 *
 * <h2>错误形态（逐 handler 对照）</h2>
 * <ul>
 *   <li>binding 失败 → 400 信封："分页参数不合法"（query）/ "请求参数不合法"
 *       （JSON）/ "删除选项不合法"（DELETE body 非 EOF 错）。</li>
 *   <li>tag_id 既非整数也当 UUID 用（resolveTagID 只在整数路径查库）。</li>
 *   <li>service 的普通 error（GetByID "record not found" 等）→ 500 code=1007
 *       "Internal server error" 无 details 键（FAQ 同款 controller-local handler）。</li>
 * </ul>
 */
@RestController
public class KnowledgeTagController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTagController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeTagService tagService;
    private final ChunkAccessGuard guard;
    private final ChunkRepository chunkRepo;
    private final KnowledgeTagRepository tagRepo;

    public KnowledgeTagController(KnowledgeTagService tagService,
                                  ChunkAccessGuard guard,
                                  ChunkRepository chunkRepo,
                                  KnowledgeTagRepository tagRepo) {
        this.tagService = tagService;
        this.guard = guard;
        this.chunkRepo = chunkRepo;
        this.tagRepo = tagRepo;
    }

    // ── GET /tags（对照 ListTags，tag.go L87-111） ──────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<Map<String, Object>> listTags(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "page_size", required = false) String pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireKbAccess(kbId);

        Integer pageValue = page == null ? null : bindPaginationInt(page, "Page");
        Integer pageSizeValue = pageSize == null ? null : bindPaginationInt(pageSize, "PageSize");

        TagPageResult result = tagService.listTags(kbId, pageValue, pageSizeValue,
                LogSanitizer.sanitize(keyword));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 Pagination（form 绑定）：非整数 → strconv 原文；其余交给 validator/service。 */
    private static int bindPaginationInt(String raw, String field) {
        try {
            return Long.parseLong(raw.trim()) > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw paramError("分页参数不合法",
                    "strconv.ParseInt: parsing \"" + raw + "\": invalid syntax");
        }
    }

    // ── POST /tags（对照 CreateTag，tag.go L132-157） ───────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<Map<String, Object>> createTag(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        // 写路由：OwnedKBOrAdmin → KBAccessWrite（判定顺序 golden 依赖）
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        com.fasterxml.jackson.databind.JsonNode root = parseJsonBody(rawBody, "请求参数不合法");
        // 对照 createTagRequest{name string required; color string; sort_order int}：
        // json.Unmarshal 先于 validator——类型错给 Go UnmarshalTypeError 原文
        String typeError = goStringField(root, "name", "createTagRequest");
        if (typeError == null) {
            typeError = goStringField(root, "color", "createTagRequest");
        }
        if (typeError == null) {
            typeError = goIntField(root, "sort_order", "createTagRequest");
        }
        if (typeError != null) {
            throw paramError("请求参数不合法", typeError);
        }
        CreateTagRequest req = new CreateTagRequest();
        req.name = textOrNull(root, "name");
        req.color = textOrNull(root, "color");
        req.sortOrder = intOrNull(root, "sort_order");
        // createTagRequest 是具名 struct → validator 键带前缀（golden w5a-tag-create-missing-name）
        String nameError = requiredError(req.name, "createTagRequest", "Name");
        if (nameError != null) {
            throw paramError("请求参数不合法", nameError);
        }

        KnowledgeTag tag = tagService.createTag(kbId,
                LogSanitizer.sanitize(req.name), LogSanitizer.sanitize(req.color),
                req.sortOrder == null ? 0 : req.sortOrder);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", KnowledgeTagResponse.from(tag));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    static final class CreateTagRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("name")
        String name;
        @com.fasterxml.jackson.annotation.JsonProperty("color")
        String color;
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order")
        Integer sortOrder;
    }

    // ── PUT /tags/{tag_id}（对照 UpdateTag，tag.go L179-208） ───────────────

    @PutMapping("/api/v1/knowledge-bases/{id}/tags/{tag_id}")
    public ResponseEntity<Map<String, Object>> updateTag(
            @PathVariable("id") String id,
            @PathVariable("tag_id") String tagIdParam,
            @RequestBody(required = false) String rawBody) {
        String kbId = LogSanitizer.sanitize(id);
        guard.requireOwnedKbInCallerSpace(kbId);
        guard.requireKbAccess(kbId);

        String tagId = resolveTagId(LogSanitizer.sanitize(tagIdParam));

        com.fasterxml.jackson.databind.JsonNode root = parseJsonBody(rawBody, "请求参数不合法");
        // 对照 updateTagRequest{name *string; color *string; sort_order *int}（全指针，无 binding）
        String typeError = goStringField(root, "name", "updateTagRequest");
        if (typeError == null) {
            typeError = goStringField(root, "color", "updateTagRequest");
        }
        if (typeError == null) {
            typeError = goIntField(root, "sort_order", "updateTagRequest");
        }
        if (typeError != null) {
            throw paramError("请求参数不合法", typeError);
        }
        UpdateTagRequest req = new UpdateTagRequest();
        req.name = textOrNull(root, "name");
        req.color = textOrNull(root, "color");
        req.sortOrder = intOrNull(root, "sort_order");
        KnowledgeTag tag = tagService.updateTag(tagId, req.name, req.color, req.sortOrder);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", KnowledgeTagResponse.from(tag));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 updateTagRequest（tag.go L159-163）：全指针字段，无 binding。 */
    static final class UpdateTagRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("name")
        String name;
        @com.fasterxml.jackson.annotation.JsonProperty("color")
        String color;
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order")
        Integer sortOrder;
    }

    // ── DELETE /tags/{tag_id}（对照 DeleteTag，tag.go L226-289） ────────────

    @DeleteMapping("/api/v1/knowledge-bases/{id}/tags/{tag_id}")
    public ResponseEntity<Map<String, Object>> deleteTag(
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

        // body 可省（EOF 容忍）；非 EOF 的绑定错误 → 400 "删除选项不合法"（无 details）
        DeleteTagRequest req = new DeleteTagRequest();
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                DeleteTagRequest parsed = MAPPER.readValue(rawBody, DeleteTagRequest.class);
                if (parsed != null && parsed.excludeIds != null) {
                    req.excludeIds = parsed.excludeIds;
                }
            } catch (Exception e) {
                throw new com.ragagent.common.error.BizException(
                        com.ragagent.common.error.AppError.badRequest("删除选项不合法"));
            }
        }

        // exclude_ids → chunk UUID 校验链（tag.go L245-276）
        List<String> excludeUUIDs = new ArrayList<>();
        if (req.excludeIds != null && !req.excludeIds.isEmpty()) {
            long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
            java.util.Map<Long, Boolean> wanted = new java.util.HashMap<>();
            for (Long seqId : req.excludeIds) {
                if (seqId == null || seqId <= 0) {
                    throw new com.ragagent.common.error.BizException(
                            com.ragagent.common.error.AppError.badRequest("排除条目 ID 必须为正整数"));
                }
                wanted.put(seqId, Boolean.TRUE);
            }
            List<Chunk> chunks = chunkRepo.listChunksBySeqId(tenantId, req.excludeIds);
            for (Chunk chunk : chunks) {
                if (chunk == null || chunk.getSeqId() == null || !wanted.containsKey(chunk.getSeqId())) {
                    continue;
                }
                if (chunk.getTenantId() == null || chunk.getTenantId() != tenantId
                        || kbId.equals(chunk.getKnowledgeBaseId()) == false
                        || !"faq".equals(chunk.getChunkType())) {
                    throw new com.ragagent.common.error.BizException(
                            com.ragagent.common.error.AppError.forbidden("排除条目不属于当前知识库"));
                }
                excludeUUIDs.add(chunk.getId());
                wanted.remove(chunk.getSeqId());
            }
            if (!wanted.isEmpty()) {
                throw new com.ragagent.common.error.BizException(
                        com.ragagent.common.error.AppError.notFound("排除条目不存在"));
            }
        }

        tagService.deleteTag(tagId, forceFlag, contentOnlyFlag, excludeUUIDs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 DeleteTagRequest（tag.go L32-34）。 */
    static final class DeleteTagRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("exclude_ids")
        java.util.List<Long> excludeIds;
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    /**
     * 对照 resolveTagID（tag.go L48-65）：tag_id 是**整数** → 按 seq_id 查
     * （查不到 → 404 "标签不存在"）；否则当 UUID 原样透传（缺失由 service 层
     * GetByID 的普通 error 出 plain-500——Go 既有行为，照抄）。
     */
    private String resolveTagId(String raw) {
        try {
            long seqId = Long.parseLong(raw);
            long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
            KnowledgeTag tag = chunkTagLookup(tenantId, seqId);
            if (tag == null) {
                throw new com.ragagent.common.error.BizException(
                        com.ragagent.common.error.AppError.notFound("标签不存在"));
            }
            return tag.getId();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private KnowledgeTag chunkTagLookup(long tenantId, long seqId) {
        return tagRepo.getBySeqId(tenantId, seqId);
    }

    /** 对照 binding:"required"（string 非零值）。 */
    private static String requiredError(String value, String structName, String field) {
        if (value == null || value.isEmpty()) {
            return "Key: '" + structName + "." + field + "' Error:Field validation for '"
                    + field + "' failed on the 'required' tag";
        }
        return null;
    }

    /**
     * 对照 c.ShouldBindJSON 的**语法层**：空 body → details "EOF"；顶层语法错 →
     * Go encoding/json 文案。字段类型层由 {@link #goStringField}/{@link #goIntField}
     * 逐字段复刻（具名 struct 的 UnmarshalTypeError 路径形如
     * "createTagRequest.name"）。
     */
    private static com.fasterxml.jackson.databind.JsonNode parseJsonBody(String rawBody, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw paramError(message, "EOF");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(rawBody);
            if (node == null || !node.isObject()) {
                // 顶层 null/非对象：null → Go 零值绑定（空对象语义）；非对象 → Go 顶层文案
                if (node != null && !node.isNull()) {
                    throw paramError(message, GoJsonBindError.message(rawBody, "top-level "
                            + node.getNodeType()));
                }
                return MAPPER.createObjectNode();
            }
            return node;
        } catch (com.ragagent.common.error.BizException e) {
            throw e;
        } catch (Exception e) {
            throw paramError(message, GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /** Go json.Decoder 的值种别（UnmarshalTypeError 文案用）。 */
    private static String goJsonKind(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "bool";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        return "number";
    }

    /** string 字段的类型检查；违规返回 Go UnmarshalTypeError 原文，否则 null。 */
    private static String goStringField(com.fasterxml.jackson.databind.JsonNode root,
                                        String field, String structName) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isTextual()) {
            return null;
        }
        return "json: cannot unmarshal " + goJsonKind(node)
                + " into Go struct field " + structName + "." + field + " of type string";
    }

    /** int 字段（含指针语义：null → nil）的类型检查；违规返回 Go 原文。 */
    private static String goIntField(com.fasterxml.jackson.databind.JsonNode root,
                                     String field, String structName) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            // Go int 拒绝小数/超界，文案带字面值：cannot unmarshal number 1.5 into ...
            if (!node.canConvertToExactIntegral() || !(node.isInt() || node.isLong())) {
                return "json: cannot unmarshal number " + node.asText()
                        + " into Go struct field " + structName + "." + field + " of type int";
            }
            return null;
        }
        return "json: cannot unmarshal " + goJsonKind(node)
                + " into Go struct field " + structName + "." + field + " of type int";
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.intValue();
    }

    /** 对照 NewBadRequestError(message).WithDetails(err.Error())。 */
    private static com.ragagent.common.error.BizException paramError(String message, String details) {
        return new com.ragagent.common.error.BizException(
                com.ragagent.common.error.AppError.badRequest(message).withDetails(details));
    }

    /**
     * service 的普通 error（fmt.Errorf 族）→ Go 全局 ErrorHandler 的 plain 分支：
     * 500 + {"error":{"code":1007,"message":"Internal server error"},"success":false}
     * （无 details 键——FAQ 同款，golden 实录）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleTagPlainInternal(IllegalStateException ex) {
        log.error("Tag operation failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
