package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.session.domain.MentionedItem;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.dto.QaRequests.MentionedItemRequest;
import com.ragagent.storage.support.StreamRewriter;
import com.ragagent.stream.StreamEvent;
import com.ragagent.event.EventBus;
import com.ragagent.agent.SteerSink;

/**
 * QA 请求上下文与共享辅助（对照 Go internal/handler/session/qa.go 的
 * {@code qaRequestContext}/{@code sseStreamContext} 与 helpers.go 的纯函数族）。
 */
public final class QaSupport {

    private QaSupport() {}

    /** 对照 maxAttachmentUploadsPerRequest / maxAttachmentUploadTotalBytes（qa.go L32-35）。 */
    public static final int MAX_ATTACHMENT_UPLOADS_PER_REQUEST = 5;
    public static final long MAX_ATTACHMENT_UPLOAD_TOTAL_BYTES = 100L * 1024 * 1024;

    /** 对照 qaMode（qa.go L966-972）。 */
    public enum QaMode {
        /** KnowledgeQA pipeline (RAG / pure chat) */
        NORMAL,
        /** Agent engine with tool calling */
        AGENT
    }

    // ==================================================================
    // qaRequestContext（qa.go L37-93，字段逐一对照；Java 侧 ctx/c 拆开传）
    // ==================================================================
    public static final class QaRequestContext {
        public String sessionId = "";
        public String requestId = "";
        /** Wall-clock time the handler started processing the request */
        public java.time.Instant receivedAt = java.time.Instant.now();
        public String query = "";
        public Session session;
        /** 已解析的 agent（id/config 树）；null = 未指定或未解析到 */
        public com.ragagent.agentm.domain.CustomAgentEntity agentRow;
        /** agent 的 config 树（ensureDefaults 后的 ObjectNode） */
        public com.fasterxml.jackson.databind.node.ObjectNode agentConfig;
        public Message assistantMessage;
        public List<String> knowledgeBaseIds = new ArrayList<>();
        public List<String> knowledgeIds = new ArrayList<>();
        public List<TagScope> tagScopes = new ArrayList<>();
        public List<String> tagIds = new ArrayList<>();
        public List<String> mcpServiceIds = new ArrayList<>();
        public List<String> skillNames = new ArrayList<>();
        public String summaryModelId = "";
        public boolean webSearchEnabled;
        public List<MentionedItem> mentionedItems = new ArrayList<>();
        /** when using shared agent, tenant ID for model/KB/MCP resolution; 0 = use context tenant */
        public long effectiveTenantId;
        /** access was granted by a read-only agent share */
        public boolean sharedAgentReadOnly;
        public List<QaRequestsImage> images = new ArrayList<>();
        /** Created user message ID (populated after createUserMessage) */
        public String userMessageID = "";
        /** Persisted user message timestamp, echoed on agent_query */
        public java.time.OffsetDateTime userCreatedAt;
        /** Source channel: "web", "api", "im", etc. */
        public String channel = "";
        /** Processed base64 file attachments (legacy inline uploads) */
        public List<com.ragagent.session.domain.MessageAttachment> attachments = new ArrayList<>();
        /** Pre-uploaded session-scoped document IDs, resolved after SSE starts */
        public List<String> attachmentIDs = new ArrayList<>();
        /** Metadata-only view of attachmentIDs for the persisted user message */
        public List<com.ragagent.session.domain.MessageAttachment> attachmentMetas = new ArrayList<>();
        public com.ragagent.session.domain.SuggestionAttribution suggestionAttribution;
        /**
         * turns internal storage references in the outbound stream into directly
         * loadable URLs when the caller asks for {@code resource_urls=public}.
         * Disabled (a pass-through) in the default handle mode.
         */
        public StreamRewriter resourceRewriter;

        /** bridges the engine's round-boundary drain to the steer sub-list（agent runs only） */
        public SteerSinkBridge steerSink;

        /** request snapshot for the input-bar state（persistLastRequestState） */
        public boolean reqAgentEnabled;
        public String reqAgentID = "";

        /** server-started follow-up runs（steer backlog）：事件进 StreamManager 但不写 SSE */
        public boolean skipSSE;
        /** appended to this run's steer sub-list before the run is published as live */
        public List<StreamEvent> steerCarryOver;

        /** agent 快答/工具开关等派生量（Java 侧从 config 树取，对照 Go customAgent.Config.*） */
        public boolean agentModeEnabled;

        /** 对照 buildQARequest（qa.go L98-123）。 */
        public QaRequest buildQaRequest() {
            ImageExtraction ext = extractImageUrlsAndOcrText(images);
            QaRequest req = new QaRequest();
            req.session = session;
            req.query = query;
            req.assistantMessageId = assistantMessage == null ? "" : assistantMessage.getId();
            req.summaryModelId = summaryModelId;
            req.agentRow = agentRow;
            req.agentConfig = agentConfig;
            req.sharedAgentReadOnly = sharedAgentReadOnly;
            req.knowledgeBaseIds = knowledgeBaseIds;
            req.knowledgeIds = knowledgeIds;
            req.tagScopes = tagScopes;
            req.mcpServiceIds = mcpServiceIds;
            req.skillNames = skillNames;
            req.imageUrls = ext.urls;
            req.imageDescription = ext.ocrText;
            req.userMessageId = userMessageID;
            req.webSearchEnabled = webSearchEnabled;
            req.attachments = attachments;
            req.steerSink = steerSink;
            return req;
        }
    }

    /** qa 请求里一张图片的轻量视图（对照 ImageAttachment 的 handler 内消费面）。 */
    public static final class QaRequestsImage {
        public String data = "";
        public String url = "";
        public String caption = "";
    }

    /** extractImageURLsAndOCRText 的二元返回（helpers.go L34-56）。 */
    public static final class ImageExtraction {
        public List<String> urls = new ArrayList<>();
        public String ocrText = "";
    }

    /** 对照 types.TagScope。 */
    public static final class TagScope {
        public String knowledgeBaseId = "";
        public List<String> tagIds = new ArrayList<>();

        public TagScope() {}

        public TagScope(String knowledgeBaseId, List<String> tagIds) {
            this.knowledgeBaseId = knowledgeBaseId;
            this.tagIds = tagIds == null ? new ArrayList<>() : tagIds;
        }
    }

    // ==================================================================
    // sseStreamContext（qa.go L642-656）
    // ==================================================================
    public static final class SseStreamContext {
        public EventBus eventBus;
        /** 虚拟线程上显式传租户的快照（对照 asyncCtx 的 ctx 值） */
        public com.ragagent.event.TenantContextSnapshot tenantSnapshot;
        public volatile boolean cancelled;
        public Message assistantMessage;
        public SteerSinkBridge steerSink;
        /** SetLiveRun failed → executeQA must not start the engine */
        public boolean liveRunFailed;
        public String liveRunErr = "";
        /** liveRunErr 是否为 live-run-already-exists（决定 409 vs 503） */
        public boolean liveRunExists;
    }

    // ==================================================================
    // helpers.go 纯函数族
    // ==================================================================

    /** 对照 extractImageURLsAndOCRText（helpers.go L34-56）。 */
    public static ImageExtraction extractImageUrlsAndOcrText(List<QaRequestsImage> images) {
        ImageExtraction out = new ImageExtraction();
        if (images == null) {
            return out;
        }
        StringBuilder ocr = new StringBuilder();
        boolean first = true;
        for (QaRequestsImage img : images) {
            if (img.url != null && !img.url.isEmpty()) {
                out.urls.add(img.url);
            }
            if (img.caption != null && !img.caption.isEmpty()) {
                if (!first) {
                    ocr.append("\n\n");
                }
                first = false;
                ocr.append(img.caption);
            }
        }
        out.ocrText = ocr.toString();
        return out;
    }

    /** 对照 convertMentionedItems（helpers.go L58-76）。 */
    public static List<MentionedItem> convertMentionedItems(List<MentionedItemRequest> items) {
        List<MentionedItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MentionedItemRequest item : items) {
            MentionedItem m = new MentionedItem();
            m.setId(orEmpty(item.id));
            m.setName(orEmpty(item.name));
            m.setType(orEmpty(item.type));
            m.setKbType(orEmpty(item.kbType));
            m.setKbId(orEmpty(item.kbId));
            m.setKbName(orEmpty(item.kbName));
            m.setServiceId(orEmpty(item.serviceId));
            m.setSkillName(orEmpty(item.skillName));
            out.add(m);
        }
        return out;
    }

    /** 对照 tagScopesFromMentionedItems（helpers.go L78-101）。 */
    public static List<TagScope> tagScopesFromMentionedItems(List<MentionedItemRequest> items) {
        Map<String, TagScope> scopesByKb = new LinkedHashMap<>();
        if (items == null) {
            return new ArrayList<>();
        }
        for (MentionedItemRequest item : items) {
            if (!"tag".equals(item.type) || isEmpty(item.kbId) || isEmpty(item.id)) {
                continue;
            }
            TagScope scope = scopesByKb.get(item.kbId);
            if (scope == null) {
                scope = new TagScope(item.kbId, new ArrayList<>());
                scopesByKb.put(item.kbId, scope);
            }
            if (!scope.tagIds.contains(item.id)) {
                scope.tagIds.add(item.id);
            }
        }
        return new ArrayList<>(scopesByKb.values());
    }

    /** 对照 orphanTagIDsForScope（helpers.go L103-121）。 */
    public static List<String> orphanTagIdsForScope(List<String> tagIds, List<TagScope> scopes) {
        Set<String> scoped = new LinkedHashSet<>();
        if (scopes != null) {
            for (TagScope s : scopes) {
                scoped.addAll(s.tagIds);
            }
        }
        List<String> orphans = new ArrayList<>();
        if (tagIds != null) {
            for (String id : tagIds) {
                if (!isEmpty(id) && !scoped.contains(id)) {
                    orphans.add(id);
                }
            }
        }
        return orphans;
    }

    /** 对照 validateUnscopedTagIDs（helpers.go L123-134）。 */
    public static String validateUnscopedTagIds(List<String> orphan, List<String> kbIds) {
        if (orphan.isEmpty()) {
            return null;
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return "tags without a knowledge base scope must be mentioned with a knowledge base";
        }
        return null;
    }

    /** 对照 mergeTagScopesFromRequestIDs（helpers.go L136-153）。 */
    public static List<TagScope> mergeTagScopesFromRequestIds(
            List<TagScope> scopes, List<String> tagIds, List<String> kbIds) {
        List<TagScope> merged = new ArrayList<>();
        if (scopes != null) {
            for (TagScope s : scopes) {
                merged.add(new TagScope(s.knowledgeBaseId, new ArrayList<>(s.tagIds)));
            }
        }
        if (tagIds == null || tagIds.isEmpty()) {
            return merged;
        }
        // A request tag ID without a scope attaches to a single-KB scope when the
        // request names exactly one knowledge base (Go 同款判定).
        if (kbIds != null && kbIds.size() == 1 && !isEmpty(kbIds.get(0))) {
            String kbId = kbIds.get(0);
            TagScope scope = null;
            for (TagScope s : merged) {
                if (kbId.equals(s.knowledgeBaseId)) {
                    scope = s;
                    break;
                }
            }
            if (scope == null) {
                scope = new TagScope(kbId, new ArrayList<>());
                merged.add(scope);
            }
            for (String tagId : tagIds) {
                if (!isEmpty(tagId) && !scope.tagIds.contains(tagId)) {
                    scope.tagIds.add(tagId);
                }
            }
        }
        return merged;
    }

    /** 对照 mentionedIDsByType（helpers.go L155-166）。 */
    public static List<String> mentionedIdsByType(List<MentionedItemRequest> items, String itemType) {
        List<String> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MentionedItemRequest item : items) {
            if (itemType.equals(item.type) && !isEmpty(item.id)) {
                out.add(item.id);
            }
        }
        return out;
    }

    /** 对照 dedupRequestStrings（helpers.go L168-180）。 */
    public static List<String> dedupRequestStrings(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            if (!isEmpty(v) && !seen.contains(v)) {
                seen.add(v);
                out.add(v);
            }
        }
        return out;
    }

    /** 对照 mergeKnowledgeTargets（qa.go L602-640）。 */
    public static KnowledgeTargets mergeKnowledgeTargets(
            List<String> requestKbIds, List<String> requestKnowledgeIds, List<MentionedItemRequest> mentionedItems) {
        Set<String> kbIdSet = new LinkedHashSet<>();
        List<String> kbIds = new ArrayList<>();
        for (String id : requestKbIds == null ? List.<String>of() : requestKbIds) {
            if (!isEmpty(id) && !kbIdSet.contains(id)) {
                kbIds.add(id);
                kbIdSet.add(id);
            }
        }
        Set<String> knowledgeIdSet = new LinkedHashSet<>();
        List<String> knowledgeIds = new ArrayList<>();
        for (String id : requestKnowledgeIds == null ? List.<String>of() : requestKnowledgeIds) {
            if (!isEmpty(id) && !knowledgeIdSet.contains(id)) {
                knowledgeIds.add(id);
                knowledgeIdSet.add(id);
            }
        }
        for (MentionedItemRequest item : mentionedItems == null ? List.<MentionedItemRequest>of() : mentionedItems) {
            if (isEmpty(item.id)) {
                continue;
            }
            switch (item.type) {
                case "kb" -> {
                    if (!kbIdSet.contains(item.id)) {
                        kbIds.add(item.id);
                        kbIdSet.add(item.id);
                    }
                }
                case "file" -> {
                    if (!knowledgeIdSet.contains(item.id)) {
                        knowledgeIds.add(item.id);
                        knowledgeIdSet.add(item.id);
                    }
                }
                default -> {}
            }
        }
        return new KnowledgeTargets(kbIds, knowledgeIds);
    }

    /** mergeKnowledgeTargets 的二元返回。 */
    public record KnowledgeTargets(List<String> kbIds, List<String> knowledgeIds) {}

    /** 对照 normalizeTemporaryAttachmentIDs（qa.go L1561-1581）。 */
    public static List<String> normalizeTemporaryAttachmentIds(List<String> ids, int max) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            String v = id == null ? "" : id.trim();
            if (v.isEmpty() || seen.contains(v)) {
                continue;
            }
            seen.add(v);
            out.add(v);
        }
        return out;
    }

    // ==================================================================
    // steer.go 引擎侧共用的纯函数（波 1 G4 已翻 HTTP 面；这里补引擎半边）
    // ==================================================================

    public static final String STEER_DELIVERY_INJECT = "inject";
    public static final String STEER_DELIVERY_AFTER = "after";
    public static final String STEER_DATA_CONSUMED = "consumed";
    public static final String STEER_DATA_USER_MESSAGE_ID = "user_message_id";
    public static final int MAX_STEER_QUEUE_DEPTH = 10;
    public static final int MAX_STEER_QUERY_LENGTH = 10000;
    public static final int STEER_DRAIN_BATCH_LIMIT = 20;

    /** 对照 parseSteerDelivery（steer.go L371-384）。null = 非法。 */
    public static String parseSteerDelivery(String s) {
        String v = s == null ? "" : s.trim().toLowerCase();
        if (v.isEmpty() || STEER_DELIVERY_AFTER.equals(v)) {
            return STEER_DELIVERY_AFTER;
        }
        if (STEER_DELIVERY_INJECT.equals(v)) {
            return STEER_DELIVERY_INJECT;
        }
        return null;
    }

    /** 对照 steerDeliveryOfEvent（steer.go L386-391）。 */
    public static String steerDeliveryOfEvent(StreamEvent evt) {
        return evt.getData() != null && STEER_DELIVERY_AFTER.equals(evt.getData().get("delivery"))
                ? STEER_DELIVERY_AFTER
                : STEER_DELIVERY_INJECT;
    }

    /** 对照 steerEventConsumed（steer.go L393-398）。 */
    public static boolean steerEventConsumed(StreamEvent evt) {
        return evt.getData() != null && Boolean.TRUE.equals(evt.getData().get(STEER_DATA_CONSUMED));
    }

    /** 对照 selectSteerBacklog（steer.go L406-418）。 */
    public static List<StreamEvent> selectSteerBacklog(List<StreamEvent> events, Set<String> injectedIds) {
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent evt : events) {
            if (steerEventConsumed(evt)) {
                continue;
            }
            if (injectedIds != null && injectedIds.contains(evt.getId())) {
                continue;
            }
            out.add(evt);
        }
        return out;
    }

    /** 对照 steerEvent（steer.go L356-369）。 */
    public static StreamEvent steerEvent(String id, String query, List<MentionedItem> mentionedItems, String channel) {
        StreamEvent evt = new StreamEvent();
        evt.setId(id);
        evt.setType(com.ragagent.common.llm.ResponseType.STEER);
        evt.setContent(query);
        evt.setDone(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("steer_id", id);
        data.put("channel", channel);
        data.put("delivery", STEER_DELIVERY_INJECT);
        data.put("mentioned_items", mentionedItemsToRaw(mentionedItems));
        evt.setData(data);
        return evt;
    }

    /** 对照 mentionedItemsToRaw（types/message.go L75-90）。 */
    public static List<Object> mentionedItemsToRaw(List<MentionedItem> items) {
        List<Object> out = new ArrayList<>();
        if (items != null) {
            for (MentionedItem item : items) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("id", item.getId());
                raw.put("name", item.getName());
                raw.put("type", item.getType());
                raw.put("kb_type", item.getKbType());
                raw.put("kb_id", item.getKbId());
                raw.put("kb_name", item.getKbName());
                raw.put("service_id", item.getServiceId());
                raw.put("skill_name", item.getSkillName());
                out.add(raw);
            }
        }
        return out;
    }

    /** 对照 getString（helpers.go L448-453）。 */
    public static String getString(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v instanceof String s ? s : "";
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ==================================================================
    // QARequest（对照 types.QARequest，service 面入参）
    // ==================================================================
    public static final class QaRequest {
        public Session session;
        public String query = "";
        public String assistantMessageId = "";
        public String summaryModelId = "";
        public com.ragagent.agentm.domain.CustomAgentEntity agentRow;
        public com.fasterxml.jackson.databind.node.ObjectNode agentConfig;
        public boolean sharedAgentReadOnly;
        public List<String> knowledgeBaseIds = new ArrayList<>();
        public List<String> knowledgeIds = new ArrayList<>();
        public List<TagScope> tagScopes = new ArrayList<>();
        public List<String> mcpServiceIds = new ArrayList<>();
        public List<String> skillNames = new ArrayList<>();
        public List<String> imageUrls;
        public String imageDescription = "";
        public String userMessageId = "";
        public boolean webSearchEnabled;
        public String quotedContext = "";
        public List<com.ragagent.session.domain.MessageAttachment> attachments = new ArrayList<>();
        public SteerSink steerSink;
        /**
         * 用户停止（stop）的取消探针（对照 Go 的 ctx 取消贯穿）：null=存活；
         * 非 null 时返回 null=未取消、非 null=取消错误原文——与引擎
         * {@code setCancellationSource(Supplier)} 的契约一致。
         */
        public java.util.function.Supplier<String> cancellationProbe;
    }
}
