package com.ragagent.session.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * QA 请求面 DTO（三个入口共用：{@code /knowledge-chat}、{@code /agent-chat}、
 * {@code /knowledge-search}；embed 经 {@code patchEmbedChatPayload} 改写后走同一对端点）。
 *
 * <p><b>键名＝Java 字段名（camelCase）</b>：这些类不带 {@code @JsonProperty} /
 * {@code @JsonInclude} 注解——它们**只做入参**（解析后转
 * {@code QaSupport.QaRequest} 内部模型），注解对入参没有语义，留着只会让下一个读者
 * 以为响应面也受影响。</p>
 *
 * <p>绑定错误文案保持既有措辞：{@code QaRequestBinder} 按
 * {@code Key: 'CreateKnowledgeQARequest.Query' Error:Field validation …} 抛错。</p>
 *
 * <p>未登记的键一律被忽略（{@code @JsonIgnoreProperties}）——**旧 snake 键因此不会报错，只会静默失效**，
 * 客户端须同批改造（前端、embed 访客页、集成文档页已同批）。</p>
 */
public final class QaRequests {

    private QaRequests() {}

    /** 提及项元素：与 `session.domain.MentionedItem` 同字段名（S3 收口）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MentionedItemRequest {
        public String id = "";
        public String name = "";
        /** "kb", "file", "tag", "mcp", "skill" */
        public String type = "";
        /** "document" or "faq" (only for kb type) */
        public String kbType = "";
        public String kbId = "";
        public String kbName = "";
        public String serviceId = "";
        public String skillName = "";
    }

    /** 对照 ImageAttachment。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageAttachment {
        /** base64 data URI from frontend (data:image/png;base64,...) */
        public String data = "";
        public String url = "";
        public String caption = "";
    }

    /** 对照 AttachmentUpload。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentUpload {
        public String data = "";
        public String fileName = "";
        public long fileSize;
    }

    /** 对照 CreateKnowledgeQARequest。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateKnowledgeQARequest {
        public String query = "";
        public List<String> knowledgeBaseIds;
        public List<String> knowledgeIds;
        public boolean agentEnabled;
        public String agentId = "";
        public long agentSourceTenantId;
        public boolean webSearchEnabled;
        public String summaryModelId = "";
        public List<String> mcpServiceIds;
        public List<String> skillNames;
        public List<String> tagIds;
        public List<MentionedItemRequest> mentionedItems;
        public boolean disableTitle;
        public List<ImageAttachment> images;
        public List<AttachmentUpload> attachmentUploads;
        public List<String> attachmentIds;
        public String channel = "";
        public JsonNode suggestionAttribution;

        public List<String> knowledgeBaseIds() {
            return knowledgeBaseIds == null ? new ArrayList<>() : knowledgeBaseIds;
        }

        public List<String> knowledgeIds() {
            return knowledgeIds == null ? new ArrayList<>() : knowledgeIds;
        }

        public List<MentionedItemRequest> mentionedItems() {
            return mentionedItems == null ? new ArrayList<>() : mentionedItems;
        }

        public List<String> tagIds() {
            return tagIds == null ? new ArrayList<>() : tagIds;
        }

        public List<String> mcpServiceIds() {
            return mcpServiceIds == null ? new ArrayList<>() : mcpServiceIds;
        }

        public List<String> skillNames() {
            return skillNames == null ? new ArrayList<>() : skillNames;
        }

        public List<ImageAttachment> images() {
            return images == null ? new ArrayList<>() : images;
        }

        public List<AttachmentUpload> attachmentUploads() {
            return attachmentUploads == null ? new ArrayList<>() : attachmentUploads;
        }

        public List<String> attachmentIds() {
            return attachmentIds == null ? new ArrayList<>() : attachmentIds;
        }
    }

    /** 对照 SearchKnowledgeRequest。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchKnowledgeRequest {
        public String query = "";
        public String knowledgeBaseId = "";
        public List<String> knowledgeBaseIds;
        public List<String> knowledgeIds;
        public List<String> tagIds;
        public List<MentionedItemRequest> mentionedItems;

        public List<String> knowledgeBaseIds() {
            return knowledgeBaseIds == null ? new ArrayList<>() : knowledgeBaseIds;
        }

        public List<String> knowledgeIds() {
            return knowledgeIds == null ? new ArrayList<>() : knowledgeIds;
        }

        public List<String> tagIds() {
            return tagIds == null ? new ArrayList<>() : tagIds;
        }

        public List<MentionedItemRequest> mentionedItems() {
            return mentionedItems == null ? new ArrayList<>() : mentionedItems;
        }
    }
}
