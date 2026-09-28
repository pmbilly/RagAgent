package com.ragagent.session.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.session.domain.Message;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * steer 运行协调（对照 Go internal/handler/session/steer.go 的引擎侧：
 * discardSteerBacklog / kickNextRunFromSteerBacklog / claimNextSteerFollowUp，
 * 波 1 G4 已翻 HTTP 面，这里补 executeQA 收尾路径的 back half）。
 */
@Service
public class SteerRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SteerRunCoordinator.class);

    private final StreamManager streamManager;
    private final MessageService messageService;

    public SteerRunCoordinator(StreamManager streamManager, MessageService messageService) {
        this.streamManager = streamManager;
        this.messageService = messageService;
    }

    /** follow-up 启动器（executeQA 传入 runFollowUp，避免 controller 循环依赖）。 */
    public interface FollowUpLauncher {
        void launch(QaSupport.QaRequestContext followUp);
    }

    /** 对照 discardSteerBacklog（steer.go L818-841）：停止路径，全部标 consumed。 */
    public void discardSteerBacklog(String sessionId, String assistantMessageId, Set<String> injected) {
        List<StreamEvent> all;
        try {
            all = streamManager.getSteerEvents(sessionId, assistantMessageId, 0).events();
        } catch (RuntimeException e) {
            log.warn("steer backlog read failed for session {}: {}", sessionId, e.toString());
            return;
        }
        List<StreamEvent> backlog = QaSupport.selectSteerBacklog(all, injected);
        if (backlog.isEmpty()) {
            return;
        }
        markSteerEventsConsumed(sessionId, assistantMessageId, backlog);
        try {
            List<StreamEvent> lateAll = streamManager.getSteerEvents(sessionId, assistantMessageId, 0).events();
            List<StreamEvent> late = QaSupport.selectSteerBacklog(lateAll, injected);
            if (!late.isEmpty()) {
                markSteerEventsConsumed(sessionId, assistantMessageId, late);
                backlog.addAll(late);
            }
        } catch (RuntimeException e) {
            log.warn("steer discard sweep failed for session {}: {}", sessionId, e.toString());
        }
        log.info("Discarded {} queued steer message(s) after stop, session={}", backlog.size(), sessionId);
    }

    /**
     * 对照 kickNextRunFromSteerBacklog（steer.go L857-879）：drain 未注入的 steer
     * 消息，把第一条作为 query 启动 follow-up run（follow-up 在返回前已 ClaimLiveRun）。
     */
    public boolean kickNextRunFromSteerBacklog(QaSupport.QaRequestContext prevReqCtx,
            QaSupport.SseStreamContext prevStreamCtx, FollowUpLauncher launcher) {
        QaSupport.QaRequestContext followUp = claimNextSteerFollowUp(prevReqCtx, prevStreamCtx);
        if (followUp == null) {
            return false;
        }
        launcher.launch(followUp);
        return true;
    }

    /** 对照 claimNextSteerFollowUp（steer.go L884-986）。 */
    private QaSupport.QaRequestContext claimNextSteerFollowUp(QaSupport.QaRequestContext prevReqCtx,
            QaSupport.SseStreamContext prevStreamCtx) {
        String prevMessageId = prevStreamCtx.assistantMessage.getId();
        List<StreamEvent> all;
        try {
            all = streamManager.getSteerEvents(prevReqCtx.sessionId, prevMessageId, 0).events();
        } catch (RuntimeException e) {
            log.warn("steer backlog read failed for session {}: {}", prevReqCtx.sessionId, e.toString());
            return null;
        }
        Set<String> injected = prevStreamCtx.steerSink != null
                ? prevStreamCtx.steerSink.injectedIds() : new LinkedHashSet<>();
        List<StreamEvent> backlog = QaSupport.selectSteerBacklog(all, injected);
        if (backlog.isEmpty()) {
            try {
                List<StreamEvent> lateAll = streamManager
                        .getSteerEvents(prevReqCtx.sessionId, prevMessageId, 0).events();
                backlog = QaSupport.selectSteerBacklog(lateAll, injected);
            } catch (RuntimeException ignored) {
                // Go：lateErr != nil 直接放弃
            }
            if (backlog.isEmpty()) {
                return null;
            }
        }

        StreamEvent first = backlog.get(0);
        List<StreamEvent> rest = backlog.subList(1, backlog.size());

        log.info("Steer backlog detected after run completion, session={}, count={}, injected={}",
                prevReqCtx.sessionId, backlog.size(), injected.size());

        QaSupport.QaRequestContext followUp = shallowCopy(prevReqCtx);
        followUp.query = first.getContent();
        followUp.requestId = UUID.randomUUID().toString();
        followUp.channel = QaSupport.getString(first.getData(), "channel");
        if (followUp.channel.isEmpty()) {
            followUp.channel = "web";
        }
        followUp.assistantMessage = new Message();
        followUp.assistantMessage.setSessionId(prevReqCtx.sessionId);
        followUp.assistantMessage.setRole("assistant");
        followUp.assistantMessage.setCompleted(false);
        followUp.assistantMessage.setRequestId(followUp.requestId);
        followUp.assistantMessage.setCreatedAt(java.time.OffsetDateTime.now());
        followUp.suggestionAttribution = null;
        followUp.userMessageID = "";
        followUp.steerSink = null;
        followUp.images = new java.util.ArrayList<>();
        followUp.attachments = new java.util.ArrayList<>();
        followUp.attachmentIDs = new java.util.ArrayList<>();
        followUp.attachmentMetas = new java.util.ArrayList<>();
        followUp.skipSSE = true;
        followUp.steerCarryOver = new java.util.ArrayList<>(rest);

        try {
            // persistTurnMessages 的 follow-up 形态（user 行 + assistant 行）
            Message userMsg = new Message();
            userMsg.setSessionId(followUp.sessionId);
            userMsg.setRole("user");
            userMsg.setContent(followUp.query);
            userMsg.setRequestId(followUp.requestId);
            userMsg.setCreatedAt(java.time.OffsetDateTime.now());
            userMsg.setCompleted(true);
            userMsg.setChannel(followUp.channel);
            userMsg.setMentionedItems(followUp.mentionedItems);
            Message createdUser = messageService.createMessage(userMsg);
            followUp.userMessageID = createdUser.getId();
            followUp.userCreatedAt = createdUser.getCreatedAt();
            Message createdAssistant = messageService.createMessage(followUp.assistantMessage);
            followUp.assistantMessage = createdAssistant;
        } catch (RuntimeException e) {
            log.error("follow-up persist failed for session {}: {}", prevReqCtx.sessionId, e.toString());
            rollback(followUp);
            return null;
        }
        if (followUp.assistantMessage == null || followUp.assistantMessage.getId().isEmpty()) {
            rollback(followUp);
            return null;
        }
        try {
            streamManager.claimLiveRun(followUp.sessionId, followUp.assistantMessage.getId(), followUp.requestId);
        } catch (RuntimeException e) {
            log.error("follow-up ClaimLiveRun failed for session {}: {}", followUp.sessionId, e.toString());
            rollback(followUp);
            return null;
        }

        markSteerEventsConsumed(prevReqCtx.sessionId, prevMessageId, backlog);

        // A send that landed on A while we persisted still sits on A's list.
        try {
            List<StreamEvent> lateAll = streamManager
                    .getSteerEvents(prevReqCtx.sessionId, prevMessageId, 0).events();
            List<StreamEvent> late = QaSupport.selectSteerBacklog(lateAll, injected);
            if (!late.isEmpty()) {
                markSteerEventsConsumed(prevReqCtx.sessionId, prevMessageId, late);
                followUp.steerCarryOver.addAll(late);
            }
        } catch (RuntimeException e) {
            log.warn("steer late-handoff read failed for session {}: {}", prevReqCtx.sessionId, e.toString());
        }

        if (!followUp.steerCarryOver.isEmpty()) {
            try {
                streamManager.appendSteerEvents(followUp.sessionId,
                        followUp.assistantMessage.getId(), followUp.steerCarryOver);
                followUp.steerCarryOver = null;
            } catch (RuntimeException e) {
                log.warn("steer carry-over append failed for session {}: {}", followUp.sessionId, e.toString());
            }
        }
        return followUp;
    }

    private void markSteerEventsConsumed(String sessionId, String assistantId, List<StreamEvent> events) {
        for (StreamEvent evt : events) {
            try {
                streamManager.updateSteerEventData(sessionId, assistantId, evt.getId(),
                        java.util.Map.of(QaSupport.STEER_DATA_CONSUMED, true));
            } catch (RuntimeException e) {
                log.warn("steer consume flag failed for session {} steer {}: {}", sessionId, evt.getId(), e.toString());
            }
        }
    }

    private void rollback(QaSupport.QaRequestContext followUp) {
        try {
            if (!followUp.userMessageID.isEmpty()) {
                messageService.deleteMessage(followUp.sessionId, followUp.userMessageID);
            }
            if (followUp.assistantMessage != null && !followUp.assistantMessage.getId().isEmpty()) {
                messageService.deleteMessage(followUp.sessionId, followUp.assistantMessage.getId());
            }
        } catch (RuntimeException e) {
            log.warn("follow-up rollback failed: {}", e.toString());
        }
    }

    /** 对照 Go 的 followUp := *prevReqCtx（结构体值拷贝）。 */
    private static QaSupport.QaRequestContext shallowCopy(QaSupport.QaRequestContext src) {
        QaSupport.QaRequestContext copy = new QaSupport.QaRequestContext();
        copy.sessionId = src.sessionId;
        copy.requestId = src.requestId;
        copy.receivedAt = src.receivedAt;
        copy.query = src.query;
        copy.session = src.session;
        copy.agentRow = src.agentRow;
        copy.agentConfig = src.agentConfig;
        copy.assistantMessage = src.assistantMessage;
        copy.knowledgeBaseIds = new java.util.ArrayList<>(src.knowledgeBaseIds);
        copy.knowledgeIds = new java.util.ArrayList<>(src.knowledgeIds);
        copy.tagScopes = new java.util.ArrayList<>(src.tagScopes);
        copy.tagIds = new java.util.ArrayList<>(src.tagIds);
        copy.mcpServiceIds = new java.util.ArrayList<>(src.mcpServiceIds);
        copy.skillNames = new java.util.ArrayList<>(src.skillNames);
        copy.summaryModelId = src.summaryModelId;
        copy.webSearchEnabled = src.webSearchEnabled;
        copy.mentionedItems = new java.util.ArrayList<>(src.mentionedItems);
        copy.effectiveTenantId = src.effectiveTenantId;
        copy.sharedAgentReadOnly = src.sharedAgentReadOnly;
        copy.images = src.images;
        copy.userMessageID = src.userMessageID;
        copy.userCreatedAt = src.userCreatedAt;
        copy.channel = src.channel;
        copy.attachments = src.attachments;
        copy.attachmentIDs = src.attachmentIDs;
        copy.attachmentMetas = src.attachmentMetas;
        copy.suggestionAttribution = src.suggestionAttribution;
        copy.resourceRewriter = src.resourceRewriter;
        copy.steerSink = src.steerSink;
        copy.reqAgentEnabled = src.reqAgentEnabled;
        copy.reqAgentID = src.reqAgentID;
        copy.skipSSE = src.skipSSE;
        return copy;
    }
}
