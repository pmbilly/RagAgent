package com.ragagent.session.controller;

import java.time.OffsetDateTime;
import java.util.List;
import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.MessageSuggestionService;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;

/**
 * {@code KnowledgeQaController} 的**收尾簇**：轮次状态落库、待处理附件判定、
 * assistant 消息收尾（落库/标题/建议），以及租户作用域包装 {@code runWithTenant}。
 *
 * <p>{@code runWithTenant} 与 {@code completeAssistantMessage} 集中在本类，
 * SSE 编排协作者持有本类即可转发这两件事，不必回调控制器。</p>
 */
final class QaTurnFinalizer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaTurnFinalizer.class);

    private final SessionService sessionService;
    private final MessageService messageService;
    private final MessageSuggestionService suggestionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final com.ragagent.memory.service.MemoryExtractionService memoryExtraction;

    QaTurnFinalizer(SessionService sessionService, MessageService messageService, MessageSuggestionService suggestionService, TemporaryDocumentService temporaryDocuments, com.ragagent.memory.service.MemoryExtractionService memoryExtraction) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.suggestionService = suggestionService;
        this.temporaryDocuments = temporaryDocuments;
        this.memoryExtraction = memoryExtraction;
    }

    /**
     * 借用执行租户运行：只换执行租户，身份（principal/role/userId）原样保留。
     *
     * <p>纪律 #1：借用必须**保存-恢复**而非 clear。旧实现在 finally 里先
     * {@code clear()} 再读 {@code currentPrincipal()/currentRole()/currentUserId()}
     * 去还原——这些读取在 clear 之后恒为 null，等于把调用线程的身份永久抹掉、
     * 只还原了 tenantId。而本方法的调用点包含**同步 EventBus 的内联 handler**
     * （723 的 AGENT_FINAL_ANSWER、1006 的 STOP），handler 跑在引擎/流水线/HTTP
     * 线程上，身份被抹后同线程后续的 owner/权限判定全部失真。</p>
     */
    void runWithTenant(Long tenantId, Runnable body) {
        com.ragagent.event.TenantContextSnapshot prev =
                com.ragagent.event.TenantContextSnapshot.capture();
        try {
            if (tenantId != null) {
                prev.withTenantId(tenantId).replay();
            }
            body.run();
        } finally {
            // 调用方无上下文时 prev 全空，恢复等价于 clear（新线程场景行为不变）
            prev.replay();
        }
    }
    boolean hasPendingAttachments(long tenantId, String sessionId, List<String> ids) {
        for (String id : ids) {
            TemporaryDocument doc;
            try {
                doc = temporaryDocuments.get(tenantId, sessionId, id);
            } catch (RuntimeException e) {
                continue;
            }
            if (doc != null && ("uploaded".equals(doc.getStatus()) || "processing".equals(doc.getStatus()))) {
                return true;
            }
        }
        return false;
    }
    void persistLastRequestState(QaRequestContext reqCtx, QaMode mode) {
        boolean agentEnabled = reqCtx.reqAgentEnabled;
        if (mode == QaMode.AGENT && reqCtx.agentConfig != null) {
            agentEnabled = SessionKnowledgeQaService.isAgentMode(reqCtx.agentConfig);
        }
        try {
            com.ragagent.session.domain.SessionLastRequestState state =
                    new com.ragagent.session.domain.SessionLastRequestState();
            state.setAgentId(QaSupport.orEmpty(reqCtx.reqAgentID));
            state.setAgentEnabled(agentEnabled);
            state.setModelId(reqCtx.summaryModelId);
            state.setKnowledgeBaseIds(reqCtx.knowledgeBaseIds);
            state.setKnowledgeIds(reqCtx.knowledgeIds);
            state.setTagIds(reqCtx.tagIds);
            state.setMcpServiceIds(reqCtx.mcpServiceIds);
            state.setSkillNames(reqCtx.skillNames);
            state.setMentionedItems(reqCtx.mentionedItems);
            state.setWebSearchEnabled(reqCtx.webSearchEnabled);
            sessionService.updateSessionLastRequestState(reqCtx.sessionId, state);
        } catch (RuntimeException e) {
            log.warn("persist last_request_state failed for session {}: {}", reqCtx.sessionId, e.toString());
        }
    }
    /** 收尾 assistant 消息：置完成态落库，并异步排 KB 索引 / 追问建议 / 记忆蒸馏。 */
    void completeAssistantMessage(Message assistantMessage, String userQuery,
            String userMessageId, Long tenantId) {
        assistantMessage.setUpdatedAt(OffsetDateTime.now());
        assistantMessage.setCompleted(true);
        try {
            messageService.updateMessage(assistantMessage);
        } catch (RuntimeException e) {
            log.warn("complete assistant message update failed: {}", e.toString());
        }
        final String content = assistantMessage.getContent();
        final String amId = assistantMessage.getId();
        final String sessionId = assistantMessage.getSessionId();
        // 纪律 #1：新虚拟线程没有 ThreadLocal——必须捕获**完整身份**（不止 tenantId）
        // 再重放。历史上这里只带 tenantId，principal/userId 丢失后
        // sessionUserIDForLookup() 推导出 owner=""，embed（owner=embed_session:…）与
        // 平台（owner=<userId>）会话的索引与 follow-up 全部 SessionNotFoundException。
        final com.ragagent.event.TenantContextSnapshot asyncTenant =
                com.ragagent.event.TenantContextSnapshot.capture();
        Thread.ofVirtual().start(() -> {
            asyncTenant.replay();
            com.ragagent.session.service.SessionLookupScope.mark();
            try {
                messageService.indexMessageToKb(userQuery, content, amId, sessionId);
            } catch (RuntimeException e) {
                log.warn("index message to KB failed for message {}: {}", amId, e.toString());
            } finally {
                TenantContext.clear();
                com.ragagent.session.service.SessionLookupScope.clear();
            }
        });
        if (userQuery != null && !userQuery.isEmpty() && suggestionService != null) {
            Thread.ofVirtual().start(() -> {
                asyncTenant.replay();
                com.ragagent.session.service.SessionLookupScope.mark();
                try {
                    suggestionService.ensureFollowUps(sessionId, amId, false);
                } catch (RuntimeException e) {
                    log.warn("follow-up suggestion generation failed for message {}: {}", amId, e.toString());
                } finally {
                    TenantContext.clear();
                    com.ragagent.session.service.SessionLookupScope.clear();
                }
            });
        }
        // 记忆自动蒸馏调度：enabledScope 读当前
        // 租户上下文（调用方已 runWithTenant），model_id 沿 assistant 行取。
        if (memoryExtraction != null) {
            try {
                memoryExtraction.scheduleExtraction(sessionId, amId,
                        assistantMessage.getModelId() == null ? "" : assistantMessage.getModelId());
            } catch (RuntimeException e) {
                log.warn("memory extraction scheduling failed for session {}: {}", sessionId, e.toString());
            }
        }
    }
}
