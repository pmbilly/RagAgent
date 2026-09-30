package com.ragagent.session.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.SuggestionAttribution;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;
import com.ragagent.storage.support.ResourceModeException;
import com.ragagent.storage.support.StreamRewriter;
import static com.ragagent.session.service.QaSupport.KnowledgeTargets;
import com.ragagent.session.controller.KnowledgeQaController.Base64Support;

/**
 * {@code KnowledgeQaController} 的**请求解析主体**（§14.9c 刀 4b）：把 HTTP 请求（会话 id、
 * agentId、资源、模式）解析成 {@code ParsedRequest}（{@code QaRequestContext} + 具名请求体）。
 *
 * <p>参数化而非注入（见 §11.26）：{@code agentResolver} 由调用方传入（控制器里是
 * {@code @Autowired} 字段，普通协作者不能快照）；读者租户 {@code readerTenant} 同理由调用方算好传入
 * （控制器侧的 {@code currentTenant()} 依赖 {@code tenantServiceField}）。共享静态助手
 * {@code KnowledgeQaController.stringListOf} 与嵌套类型 {@code ParsedRequest} 按包内可见引用。</p>
 */
final class QaRequestParser {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaRequestParser.class);

    private final SessionService sessionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final com.ragagent.storage.support.FileService fileService;
    private final com.ragagent.storage.support.StorageBackendResolver storageBackendResolver;

    QaRequestParser(SessionService sessionService, TemporaryDocumentService temporaryDocuments, com.ragagent.storage.support.FileService fileService, com.ragagent.storage.support.StorageBackendResolver storageBackendResolver) {
        this.sessionService = sessionService;
        this.temporaryDocuments = temporaryDocuments;
        this.fileService = fileService;
        this.storageBackendResolver = storageBackendResolver;
    }

    KnowledgeQaController.ParsedRequest parseQARequest(String rawSessionId, CreateKnowledgeQARequest request,
            String resourceUrls, String logPrefix,
            AgentResolver agentResolver,
            com.ragagent.auth.domain.Tenant readerTenant) {
        QaRequestContext rc = new QaRequestContext();

        String sessionId = SessionStreamController.sanitizeForLog(rawSessionId);
        if (sessionId.isEmpty()) {
            throw BizException.badRequest("invalid session id");
        }
        rc.sessionId = sessionId;
        rc.query = QaSupport.orEmpty(request.query);
        rc.requestId = TenantContext.currentRequestId() == null ? "" : TenantContext.currentRequestId();

        if (request.query == null || request.query.isEmpty()) {
            throw BizException.badRequest("Query content cannot be empty");
        }

        // 先解析存储引用形态：SSE 起流后非法值不能再落 400
        try {
            com.ragagent.storage.support.Mode mode = com.ragagent.storage.support.Mode.resolve(resourceUrls);
            com.ragagent.storage.support.Rewriter rw = com.ragagent.storage.support.Rewriter.forRequest(
                    mode, readerTenant, fileService, storageBackendResolver);
            rc.resourceRewriter = new StreamRewriter(rw);
        } catch (com.ragagent.storage.support.PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.forbidden(e.getMessage());
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.badRequest(e.getMessage());
        }

        // 建议归因校验
        if (request.suggestionAttribution != null && !request.suggestionAttribution.isNull()) {
            try {
                rc.suggestionAttribution = new com.fasterxml.jackson.databind.ObjectMapper()
                        .treeToValue(request.suggestionAttribution, SuggestionAttribution.class);
            } catch (Exception e) {
                throw BizException.badRequest("invalid suggestion attribution");
            }
        }

        // SSRF：客户端不可携带图片 URL/Caption（qa.go L169-172）
        for (var img : request.images()) {
            img.url = "";
            img.caption = "";
        }

        // 严格 owner 范围取会话（QA 写消息，不复用 Admin 读回退）
        try {
            rc.session = sessionService.getOwnedSession(sessionId);
        } catch (com.ragagent.session.domain.SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        }

        // resolveAgent（Go L548-600：共享优先，source==0 才回落 own）
        var resolvedAgent = agentResolver.resolve(request.agentId,
                request.agentSourceTenantId);
        CustomAgentEntity customAgent = resolvedAgent.row();
        if (request.agentSourceTenantId != 0 && customAgent == null) {
            throw BizException.notFound("Shared agent not found");
        }
        rc.agentRow = customAgent;
        rc.effectiveTenantId = resolvedAgent.effectiveTenantId();
        rc.sharedAgentReadOnly = resolvedAgent.sharedAgentReadOnly();
        if (customAgent != null) {
            ObjectNode cfg = AgentResolver.parseAgentConfig(customAgent);
            AgentConfigJson.ensureDefaults(cfg);
            rc.agentConfig = cfg;
        }

        // @mention 合并
        KnowledgeTargets merged = QaSupport.mergeKnowledgeTargets(
                request.knowledgeBaseIds(), request.knowledgeIds(), request.mentionedItems());
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(
                merged.kbIds(), merged.knowledgeIds());

        // wiki fixer 的租户作用域（Go L206-218）：内建 agent id 不匹配即跳过（同款守卫）
        if (rc.agentConfig != null && "builtin-wiki-fixer".equals(customAgent.getId())) {
            // wiki fixer 的租户作用域随 wiki_config 面（Go resolveWikiFixerTenantScope
            // 的命中条件是 KB 共享关系；dev 单租户等价于不动）。
        }

        // 内联 base64 图片（对照 Go saveImageAttachments：落盘后回填 URL，消息/检索/VLM
        // 三处消费同一引用；SSRF 已在上方清空客户端 url/caption）
        if (!request.images().isEmpty()) {
            if (rc.agentConfig == null || !rc.agentConfig.path("image_upload_enabled").asBoolean(false)) {
                log.warn("[{}] Image upload is not enabled for this agent, rejecting {} images",
                        logPrefix, request.images().size());
                throw BizException.badRequest("Image upload is not enabled for this agent");
            }
            long tenantId = rc.session.getTenantId();
            for (var img : request.images()) {
                QaSupport.QaRequestsImage view = new QaSupport.QaRequestsImage();
                if (img.data != null && !img.data.isEmpty()) {
                    byte[] bytes;
                    try {
                        bytes = Base64Support.decode(img.data);
                    } catch (RuntimeException e) {
                        throw BizException.badRequest("image decode failed: " + e.getMessage());
                    }
                    if (bytes.length == 0) {
                        throw BizException.badRequest("image decode failed: empty payload");
                    }
                    if (bytes.length > KnowledgeQaController.maxFileBytes()) {
                        throw BizException.badRequest(
                                "image exceeds size limit of " + KnowledgeQaController.maxFileBytes() + " bytes");
                    }
                    try {
                        view.url = temporaryDocuments.saveInlineImageBytes(tenantId, bytes);
                    } catch (RuntimeException e) {
                        throw BizException.internal("failed to save image: " + e.getMessage());
                    }
                }
                rc.images.add(view);
            }
        }

        // 内联附件（base64 直传，对照 Go：落临时附件表后走既有的 attachment_ids 解析链）
        if (!request.attachmentUploads().isEmpty()) {
            QaRequestBinder.decodeAndValidateAttachmentUploads(request.attachmentUploads(),
                    QaSupport.MAX_ATTACHMENT_UPLOADS_PER_REQUEST,
                    KnowledgeQaController.maxFileBytes(),
                    QaSupport.MAX_ATTACHMENT_UPLOAD_TOTAL_BYTES);
            long tenantId = rc.session.getTenantId();
            int i = 1;
            for (var up : request.attachmentUploads()) {
                byte[] data;
                try {
                    data = Base64Support.decode(up.data);
                } catch (RuntimeException e) {
                    throw BizException.badRequest("attachment " + i + " decode failed: " + e.getMessage());
                }
                try {
                    TemporaryDocument doc = temporaryDocuments.create(tenantId, sessionId,
                            up.fileName == null || up.fileName.isEmpty() ? "attachment-" + i : up.fileName,
                            "", up.fileSize, data);
                    rc.attachmentIDs.add(doc.getId());
                } catch (IllegalArgumentException e) {
                    throw BizException.badRequest("attachment " + i + ": " + e.getMessage());
                }
                i++;
            }
        }

        // 预上传附件：只取元数据（内容在 SSE 起流后解析）
        if (!request.attachmentIds().isEmpty()) {
            List<String> normalizedIds = QaSupport.normalizeTemporaryAttachmentIds(
                    request.attachmentIds(), 5);
            long tenantId = rc.session.getTenantId();
            for (String id : normalizedIds) {
                TemporaryDocument doc;
                try {
                    doc = temporaryDocuments.get(tenantId, sessionId, id);
                } catch (RuntimeException e) {
                    doc = null;
                }
                if (doc == null) {
                    throw BizException.badRequest(
                            "attachment " + SessionStreamController.sanitizeForLog(id)
                                    + " was not found in this session");
                }
                if (rc.agentConfig != null) {
                    List<String> supported = KnowledgeQaController.stringListOf(rc.agentConfig.get("supported_file_types"));
                    if (!supported.isEmpty()) {
                        String ext = doc.getFileType() == null ? ""
                                : (doc.getFileType().startsWith(".")
                                        ? doc.getFileType().substring(1).toLowerCase()
                                        : doc.getFileType().toLowerCase());
                        if (!supported.contains(ext)) {
                            throw BizException.badRequest(
                                    "file type " + ext + " is not supported by this agent");
                        }
                    }
                }
                MessageAttachment meta = new MessageAttachment();
                meta.setId(doc.getId());
                meta.setUrl(doc.getResourceRef());
                meta.setFileName(doc.getFileName());
                meta.setFileType(doc.getFileType());
                meta.setFileSize(doc.getFileSize());
                rc.attachmentMetas.add(meta);
            }
            rc.attachmentIDs = normalizedIds;
        }

        // tag scopes / ids / mcp / skills（Go L359-367）
        List<QaSupport.TagScope> mentionScopes = QaSupport.tagScopesFromMentionedItems(request.mentionedItems());
        List<String> requestTagIds = QaSupport.dedupRequestStrings(request.tagIds());
        String tagError = QaSupport.validateUnscopedTagIds(
                QaSupport.orphanTagIdsForScope(requestTagIds, mentionScopes), merged.kbIds());
        if (tagError != null) {
            throw BizException.badRequest(tagError);
        }
        rc.tagScopes = QaSupport.mergeTagScopesFromRequestIds(mentionScopes, requestTagIds, merged.kbIds());
        List<String> tagIds = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.tagIds(),
                QaSupport.mentionedIdsByType(request.mentionedItems(), "tag")));
        List<String> mcpServiceIds = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.mcpServiceIds(),
                QaSupport.mentionedIdsByType(request.mentionedItems(), "mcp")));
        List<String> skillNames = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.skillNames(),
                QaSupport.mentionedIdsByType(request.mentionedItems(), "skill")));

        // assistant 消息骨架（buildMessageExecutionContext 的 agent 字段，Go L368-426）
        String agentId = customAgent == null ? "" : customAgent.getId();
        // Go：agentTenantID = effectiveTenantID，为 0 才回落 agent.TenantID（L493-495）
        long agentTenantId = resolvedAgent.effectiveTenantId() != 0 ? resolvedAgent.effectiveTenantId()
                : (customAgent != null && customAgent.getTenantId() != null ? customAgent.getTenantId() : 0);
        String modelId = request.summaryModelId;
        if (modelId.isEmpty() && rc.agentConfig != null) {
            modelId = rc.agentConfig.path("model_id").asText("");
        }
        Message assistant = new Message();
        assistant.setSessionId(sessionId);
        assistant.setRole("assistant");
        assistant.setRequestId(rc.requestId);
        assistant.setCompleted(false);
        assistant.setChannel(request.channel);
        assistant.setAgentId(agentId);
        assistant.setAgentTenantId(agentTenantId);
        assistant.setModelId(modelId);
        assistant.setExecutionContext(new com.ragagent.session.domain.MessageExecutionContext());
        rc.assistantMessage = assistant;

        rc.knowledgeBaseIds = merged.kbIds();
        rc.knowledgeIds = merged.knowledgeIds();
        rc.tagIds = tagIds;
        rc.mcpServiceIds = mcpServiceIds;
        rc.skillNames = skillNames;
        rc.summaryModelId = request.summaryModelId;
        rc.webSearchEnabled = request.webSearchEnabled;
        rc.mentionedItems = QaSupport.convertMentionedItems(request.mentionedItems());
        rc.channel = request.channel;
        rc.reqAgentEnabled = request.agentEnabled;
        rc.reqAgentID = request.agentId;
        return new KnowledgeQaController.ParsedRequest(rc, request);
    }
}
