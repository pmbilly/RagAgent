package com.ragagent.knowledge.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.LocalStorageService;
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

/**
 * 对照 Go internal/handler/knowledge.go（阶段 3 子集）。
 * 知识实体直接序列化（struct 声明序，@JsonPropertyOrder 已锁定）；
 * 重复文档 409 为特殊信封（code/data/message/success），不走 error_handler。
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;
    private final SsrfGuard ssrfGuard;

    public KnowledgeController(KnowledgeService knowledgeService, SsrfGuard ssrfGuard) {
        this.knowledgeService = knowledgeService;
        this.ssrfGuard = ssrfGuard;
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

    /** 对照 GetKnowledge */
    @GetMapping("/knowledge/{id}")
    public ResponseEntity<?> getKnowledge(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge, ID: {}", id);
        return ResponseEntity.ok(envelope(knowledgeService.getKnowledge(id)));
    }

    /** 对照 UpdateKnowledge — 部分更新，响应 success/message/data（字母序） */
    @PutMapping("/knowledge/{id}")
    public ResponseEntity<?> updateKnowledge(@PathVariable("id") String id,
                                             @RequestBody(required = false) String rawBody) {
        log.info("Start updating knowledge, ID: {}", id);
        JsonNode body = rawBody == null || rawBody.isBlank() ? null : parseBody(rawBody, false);
        Knowledge k = knowledgeService.updateKnowledge(id, body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", k);
        resp.put("message", "Knowledge updated successfully");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 DeleteKnowledge：{data:{task_id}, message:"Delete task submitted", success} */
    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<?> deleteKnowledge(@PathVariable("id") String id) {
        log.info("Start deleting knowledge, ID: {}", id);
        String taskId = knowledgeService.deleteKnowledge(id);
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
