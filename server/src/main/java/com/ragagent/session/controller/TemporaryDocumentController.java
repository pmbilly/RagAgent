package com.ragagent.session.controller;

import java.util.List;
import java.util.Map;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会话附件（临时文档）HTTP 层（对照 Go handler/session/temporary_document.go，
 * 路由对照 routes_chat.go L61-65 的 5 条）。
 *
 * 响应形态（§2.1：裸对象，无 {"data","success"} 信封）：上传 202 裸 doc
 * （status=uploaded，解析异步）；列表/详情 200 裸对象/裸数组（详情查不到
 * 404 "Attachment not found"）；预览是文件字节流（filetransport 语义）；
 * 删除 204（幂等）。表单字段名＝Java 参数名（camelCase）。
 *
 * owner 范围（与 Go 注释一致）：上传/删除改会话内容 → 严格 owner 范围；
 * 列表/详情/预览是读 → 读可见性。
 */
@RestController
public class TemporaryDocumentController {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentController.class);

    private final SessionService sessionService;
    private final TemporaryDocumentService temporaryDocuments;
    /** 对照 Go handler 的 resolveAgent（qa.go；共享优先、source==0 才回落 own）。 */
    private final com.ragagent.session.service.AgentResolver agentResolver;

    public TemporaryDocumentController(SessionService sessionService,
                                       TemporaryDocumentService temporaryDocuments,
                                       com.ragagent.session.service.AgentResolver agentResolver) {
        this.sessionService = sessionService;
        this.temporaryDocuments = temporaryDocuments;
        this.agentResolver = agentResolver;
    }

    /** 对照 Go UploadTemporaryDocument（L19-93）。agent 表单字段随波 5（见 service 注释）。 */
    @PostMapping("/api/v1/sessions/{session_id}/attachments")
    public ResponseEntity<TemporaryDocument> upload(
            @PathVariable("session_id") String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "parserEngine", required = false) String parserEngine,
            jakarta.servlet.http.HttpServletRequest request) {
        String sid = LogSanitizer.sanitize(sessionId);
        // Go 的 FormFile 先查请求是否 multipart：非 multipart 是固定原文（golden 实测）
        String contentType = request.getContentType() == null ? "" : request.getContentType();
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
            throw new BizException(AppError.badRequest(
                    "invalid attachment upload: request Content-Type isn't multipart/form-data"));
        }
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        if (file == null) {
            throw new BizException(AppError.badRequest(
                    "invalid attachment upload: http: no such file"));
        }
        // 空 size 不在此拒——Go 的 FormFile 收 0 字节文件，由 service 的
        // "file size must be between 1 byte and 50MB" 兜（golden 实测）
        // agent_source_tenant_id / 共享 agent 分支随空间分享裁撤：只解析自有 agent
        var resolved = agentResolver.resolve(agentId, 0);
        var agent = resolved.row();
        TemporaryDocumentService.CreateOptions options =
                agentOptions(agent, extNoDot(file.getOriginalFilename()), parserEngine);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("failed to open attachment"));
        }
        TemporaryDocument document;
        try {
            document = temporaryDocuments.create(currentTenantId(), sid,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), data,
                    options);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(document);
    }

    /** 超过 multipart 上限：仿 Go MaxBytesReader 的 "http: request body too large"。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> bodyTooLarge() {
        throw new BizException(AppError.badRequest(
                "invalid attachment upload: http: request body too large"));
    }

    /** 对照 Go ListTemporaryDocuments（L95-108）。 */
    @GetMapping({"/api/v1/sessions/{id}/attachments", "/api/v1/sessions/{session_id}/attachments"})
    public ResponseEntity<List<TemporaryDocument>> list(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        List<TemporaryDocument> documents;
        try {
            documents = temporaryDocuments.list(currentTenantId(), sid);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }
        return ResponseEntity.ok(documents);
    }

    /** 对照 Go GetTemporaryDocument（L110-127）。 */
    @GetMapping({"/api/v1/sessions/{id}/attachments/{attachment_id}",
            "/api/v1/sessions/{session_id}/attachments/{attachment_id}"})
    public ResponseEntity<TemporaryDocument> get(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback,
            @PathVariable("attachment_id") String attachmentId) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        TemporaryDocument document;
        try {
            document = temporaryDocuments.get(currentTenantId(), sid, attachmentId);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }
        if (document == null) {
            throw BizException.notFound("Attachment not found");
        }
        return ResponseEntity.ok(document);
    }

    /**
     * 对照 Go PreviewTemporaryDocument（L129-158）+ filetransport.Serve：
     * 错误分支逐字对照；成功路径的响应头按 filetransport 语义拼装。
     */
    @GetMapping({"/api/v1/sessions/{id}/attachments/{attachment_id}/preview",
            "/api/v1/sessions/{session_id}/attachments/{attachment_id}/preview"})
    public void preview(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback,
            @PathVariable("attachment_id") String attachmentId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String attachment = LogSanitizer.sanitize(attachmentId);
        if (attachment.isEmpty()) {
            throw new BizException(AppError.badRequest("Attachment ID cannot be empty"));
        }
        TemporaryDocumentService.OpenedFile opened;
        try {
            opened = temporaryDocuments.openFile(currentTenantId(), sid, attachment);
        } catch (TemporaryDocumentService.AttachmentNotFoundException e) {
            throw BizException.notFound("Attachment not found");
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (message.contains("not found")) {
                throw BizException.notFound("Attachment not found");
            }
            log.error("Failed to retrieve attachment: {}", e.toString());
            throw BizException.internal("Failed to retrieve attachment");
        }
        // filetransport.Serve 语义：头用 servlet setHeader 原样写——
        // Spring/Tomcat 的 Content-Type 处理会规范化 "charset=" 前的空格（golden 实测差异）
        String fileName = opened.fileName();
        com.ragagent.common.web.ContentTypeByFilename.Record safe =
                com.ragagent.common.web.ContentTypeByFilename.safe(fileName);
        response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", safe.contentType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", disposition(fileName, safe.inline()));
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLength(opened.data().length);
        response.getOutputStream().write(opened.data());
    }

    /** 对照 Go DeleteTemporaryDocument（L160-174）：204，幂等。 */
    @DeleteMapping({"/api/v1/sessions/{id}/attachments/{attachment_id}",
            "/api/v1/sessions/{session_id}/attachments/{attachment_id}"})
    public ResponseEntity<Void> delete(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback,
            @PathVariable("attachment_id") String attachmentId) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        try {
            temporaryDocuments.delete(currentTenantId(), sid, attachmentId);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // ── 辅助 ──────────────────────────────

    /**
     * 对照 Go UploadTemporaryDocument 的 agent 门控与 options 组装（L52-83）：
     * supported_file_types 拒收 / 音频需 ASR / agent 级 parser engine 回落（显式 engine
     * 为空或 auto 时）/ VLM 图片理解选项。租户级 parser 规则由 parse worker 兜底
     * （照 Go 注释"Tenant-level rules remain the final fallback"）。
     */
    private static TemporaryDocumentService.CreateOptions agentOptions(
            com.ragagent.agentm.domain.CustomAgentEntity agent,
            String ext, String parserEngine) {
        TemporaryDocumentService.CreateOptions options = TemporaryDocumentService.CreateOptions
                .empty().withParserEngine(parserEngine == null ? "" : parserEngine.strip());
        if (agent == null) {
            return options;
        }
        com.fasterxml.jackson.databind.node.ObjectNode cfg =
                com.ragagent.session.service.AgentResolver.parseAgentConfig(agent);
        List<String> supported = stringListOf(cfg.get("supported_file_types"));
        if (!supported.isEmpty() && !containsFileType(supported, ext)) {
            throw new BizException(AppError.badRequest("file type is not supported by this agent"));
        }
        if (isAudioExtension(ext)) {
            if (!cfg.path("audio_upload_enabled").asBoolean(false)
                    || cfg.path("asr_model_id").asText("").isEmpty()) {
                throw new BizException(AppError.badRequest(
                        "audio upload is not enabled or no ASR model is configured"));
            }
            options = options.withAsrModelId(cfg.path("asr_model_id").asText(""));
        }
        if (options.parserEngine().isEmpty() || "auto".equals(options.parserEngine())) {
            String engine = com.ragagent.knowledge.support.ParserEngineRules.resolve(
                    cfg.get("chat_parser_engine_rules"), ext);
            if (!engine.isEmpty()) {
                options = options.withParserEngine(engine);
            }
        }
        if (cfg.path("image_upload_enabled").asBoolean(false)
                && !cfg.path("vlm_model_id").asText("").isEmpty()) {
            options = options.withVlm(cfg.path("vlm_model_id").asText(""),
                    cfg.path("attachment_image_understanding").asBoolean(false),
                    cfg.path("attachment_ocr_max_pages").asInt(0));
        }
        return options;
    }

    /** 对照 Go filepath.Ext + TrimPrefix(strings.ToLower(...), ".")（handler L54）。 */
    private static String extNoDot(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 对照 Go isAudioExtension（L192-200）：含 aac，与 parse 的 audioFormats 不是同一张表。 */
    private static boolean isAudioExtension(String ext) {
        return switch (ext) {
            case "mp3", "wav", "m4a", "flac", "ogg", "aac" -> true;
            default -> false;
        };
    }

    /** 对照 Go containsFileType（L183-191）：逐项去点小写后与 ext 比较。 */
    private static boolean containsFileType(List<String> supported, String ext) {
        for (String item : supported) {
            String normalized = item == null ? "" : item.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            if (normalized.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> stringListOf(com.fasterxml.jackson.databind.JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    private static String sessionParam(String id, String sessionIdFallback) {
        String value = id == null || id.isEmpty() ? sessionIdFallback : id;
        return LogSanitizer.sanitize(value == null ? "" : value);
    }

    private static long currentTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }

    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        return BizException.internal(e.getMessage());
    }

    /** 对照 Go mime.FormatMediaType：ASCII 安全字符集外的名字加引号。 */
    private static String disposition(String fileName, boolean inline) {
        String base = fileName == null ? "" : fileName;
        String type = inline ? "inline" : "attachment";
        boolean simple = !base.isEmpty() && base.chars().allMatch(c ->
                c >= 0x21 && c <= 0x7e && c != '"' && c != '\\' && c != '(' && c != ')'
                        && c != '<' && c != '>' && c != '@' && c != ',' && c != ';'
                        && c != ':' && c != '/' && c != '[' && c != ']' && c != '?'
                        && c != '=' && c != '{' && c != '}');
        return simple ? type + "; filename=" + base : type + "; filename=\"" + base + "\"";
    }
}
