package com.ragagent.session.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ragagent.event.Event;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.AgentToolCallData;
import com.ragagent.event.payload.AgentToolResultData;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.TemporaryDocumentService;

/**
 * {@code KnowledgeQaController} 的**附件解析簇**（§14.9c 刀 6a）：临时文档/内联图片的解析与
 * 等待、解析结果的持久化；SSE 侧只负责经它拿结果。
 *
 * <p>为什么先落它（§11.26 判据）：执行簇（刀 6）会回调 {@code resolveTemporaryAttachments}
 * （实例方法、110 行）——先把它搬进本协作者，执行协作者持有本类即可，无需回调宿主。</p>
 */
final class QaAttachmentResolver {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaAttachmentResolver.class);

    private final MessageService messageService;
    private final TemporaryDocumentService temporaryDocuments;
    private final QaTurnFinalizer turnFinalizer;

    QaAttachmentResolver(MessageService messageService, TemporaryDocumentService temporaryDocuments, QaTurnFinalizer turnFinalizer) {
        this.messageService = messageService;
        this.temporaryDocuments = temporaryDocuments;
        this.turnFinalizer = turnFinalizer;
    }

    /** resolveTemporaryAttachments（qa.go L1417-1512）：等待 → ResolveForPrompt → 注入。 */
    void resolveTemporaryAttachments(SseStreamContext streamCtx, QaRequestContext reqCtx) {
        if (reqCtx.attachmentIDs.isEmpty()) {
            return;
        }
        long tenantId = reqCtx.session.getTenantId();
        String sessionId = reqCtx.sessionId;
        long start = System.currentTimeMillis();
        String toolCallId = "";
        if (turnFinalizer.hasPendingAttachments(tenantId, sessionId, reqCtx.attachmentIDs)) {
            toolCallId = UUID.randomUUID().toString();
            Event evt = new Event();
            evt.setType(EventType.EVENT_AGENT_TOOL_CALL);
            evt.setSessionId(sessionId);
                        AgentToolCallData callData = new AgentToolCallData();
            callData.setToolCallId(toolCallId);
            callData.setToolName("attachment_parsing");
            callData.setArguments(new LinkedHashMap<>());
            callData.setIteration(0);
            evt.setData(callData);
            streamCtx.eventBus.emit(evt);
            waitForAttachments(tenantId, sessionId, reqCtx.attachmentIDs, 60_000);
        }
        List<String> readyIds = new ArrayList<>();
        int skipped = 0;
        for (String id : reqCtx.attachmentIDs) {
            TemporaryDocument doc;
            try {
                doc = temporaryDocuments.get(tenantId, sessionId, id);
            } catch (RuntimeException e) {
                doc = null;
            }
            if (doc != null && "ready".equals(doc.getStatus())) {
                readyIds.add(id);
            } else {
                skipped++;
            }
        }
        TemporaryDocumentService.PromptResult resolved = null;
        RuntimeException resolveErr = null;
        if (!readyIds.isEmpty()) {
            try {
                resolved = temporaryDocuments.resolveForPrompt(
                        tenantId, sessionId, readyIds, reqCtx.query);
            } catch (RuntimeException e) {
                resolveErr = e;
            }
        }

        if (!toolCallId.isEmpty()) {
            String output = "已解析 " + readyIds.size() + " 个附件";
            if (skipped > 0) {
                output += "，" + skipped + " 个未完成已跳过";
            }
            boolean success = resolveErr == null;
            if (resolveErr != null) {
                output = "附件解析失败: " + resolveErr.getMessage();
            }
            Event evt = new Event();
            evt.setType(EventType.EVENT_AGENT_TOOL_RESULT);
            evt.setSessionId(sessionId);
            AgentToolResultData data = new AgentToolResultData();
            data.setToolCallId(toolCallId);
            data.setToolName("attachment_parsing");
            data.setOutput(output);
            data.setSuccess(success);
            data.setDurationMs(System.currentTimeMillis() - start);
            data.setIteration(0);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("display_type", "attachment_parsing");
            d.put("parsed_count", readyIds.size());
            d.put("skipped_count", skipped);
            data.setData(d);
            evt.setData(data);
            streamCtx.eventBus.emit(evt);
        }
        if (resolveErr != null || resolved == null) {
            if (resolveErr != null) {
                log.warn("temporary attachment resolution failed for session {}: {}",
                        sessionId, resolveErr.getMessage());
            }
            return;
        }
        List<MessageAttachment> attachments = resolved.attachments();
        // 对照 Go：handler 侧再按 supported_file_types 过滤一层（不支持的附件不进提示词）
        if (reqCtx.agentConfig != null && !attachments.isEmpty()) {
            List<String> supported = KnowledgeQaController.stringListOf(reqCtx.agentConfig.get("supportedFileTypes"));
            if (!supported.isEmpty()) {
                attachments.removeIf(att -> {
                    String ext = att.getFileType() == null
                            ? "" : att.getFileType().toLowerCase();
                    if (ext.startsWith(".")) {
                        ext = ext.substring(1);
                    }
                    return !supported.contains(ext);
                });
            }
        }
        reqCtx.attachments.addAll(attachments);
        persistResolvedAttachmentContent(reqCtx, attachments);
        // 图片进 vision：ImageURLs 挂到本回合的 images（与内联 base64 图片同一条下游，
        // 经 extractImageURLsAndOCRText 读 url）。Go 同样以 ImageUploadEnabled 为闸。
        if (reqCtx.agentConfig != null
                && reqCtx.agentConfig.path("imageUploadEnabled").asBoolean(false)) {
            for (String imageUrl : resolved.imageUrls()) {
                QaSupport.QaRequestsImage image = new QaSupport.QaRequestsImage();
                image.url = imageUrl;
                reqCtx.images.add(image);
            }
        }
    }
    /**
     * 对照 Go {@code persistResolvedAttachmentContent}（qa.go L1516-1560）：把解析出的
     * 附件内容回写到已存的 user 消息（attachments 列）——消息创建时只带元数据，
     * 内容在 SSE 起流后才选出；不回写的话多轮历史重建时附件是空的。
     *
     * <p>失败只 WARN：丢这次写入只降级后续上下文，不能让本回合失败。</p>
     */
    private void persistResolvedAttachmentContent(QaRequestContext reqCtx,
            List<MessageAttachment> resolved) {
        if (reqCtx.userMessageID.isEmpty() || resolved.isEmpty()) {
            return;
        }
        Message msg;
        try {
            msg = messageService.getMessage(reqCtx.sessionId, reqCtx.userMessageID);
        } catch (RuntimeException e) {
            log.warn("persist attachment content: load user message {} failed: {}",
                    reqCtx.userMessageID, e.getMessage());
            return;
        }
        if (msg == null) {
            log.warn("persist attachment content: load user message {} failed: not found",
                    reqCtx.userMessageID);
            return;
        }
        Map<String, MessageAttachment> byId = new LinkedHashMap<>();
        for (MessageAttachment att : resolved) {
            if (att.getId() != null && !att.getId().isEmpty()) {
                byId.put(att.getId(), att);
            }
        }
        boolean changed = false;
        List<MessageAttachment> stored = msg.getAttachments();
        if (stored != null) {
            for (int i = 0; i < stored.size(); i++) {
                MessageAttachment existing = stored.get(i);
                if (existing == null || existing.getId() == null || existing.getId().isEmpty()) {
                    continue;
                }
                MessageAttachment enriched = byId.get(existing.getId());
                if (enriched != null) {
                    stored.set(i, enriched);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        try {
            messageService.updateMessage(msg);
        } catch (RuntimeException e) {
            log.warn("persist attachment content: update user message {} failed: {}",
                    reqCtx.userMessageID, e.getMessage());
        }
    }
    void waitForAttachments(long tenantId, String sessionId, List<String> ids, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (turnFinalizer.hasPendingAttachments(tenantId, sessionId, ids) && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
