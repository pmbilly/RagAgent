package com.ragagent.session.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * QA 请求面 DTO（对照 Go internal/handler/session/types.go 的请求结构段，
 * 字段名逐字段对照 json tag）。
 */
public final class QaRequests {

    private QaRequests() {}

    /** 对照 MentionedItemRequest。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MentionedItemRequest {
        public String id = "";
        public String name = "";
        /** "kb", "file", "tag", "mcp", "skill" */
        public String type = "";
        /** "document" or "faq" (only for kb type) */
        @com.fasterxml.jackson.annotation.JsonProperty("kb_type")
        public String kbType = "";
        @com.fasterxml.jackson.annotation.JsonProperty("kb_id")
        public String kbId = "";
        @com.fasterxml.jackson.annotation.JsonProperty("kb_name")
        public String kbName = "";
        @com.fasterxml.jackson.annotation.JsonProperty("service_id")
        public String serviceId = "";
        @com.fasterxml.jackson.annotation.JsonProperty("skill_name")
        public String skillName = "";
    }

    /** 对照 ImageAttachment。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageAttachment {
        /** base64 data URI from frontend (data:image/png;base64,...) */
        @JsonInclude(Include.NON_DEFAULT)
        public String data = "";
        @JsonInclude(Include.NON_DEFAULT)
        public String url = "";
        @JsonInclude(Include.NON_DEFAULT)
        public String caption = "";
    }

    /** 对照 AttachmentUpload。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentUpload {
        public String data = "";
        @com.fasterxml.jackson.annotation.JsonProperty("file_name")
        public String fileName = "";
        @com.fasterxml.jackson.annotation.JsonProperty("file_size")
        public long fileSize;
    }

    /** 对照 CreateKnowledgeQARequest。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateKnowledgeQARequest {
        public String query = "";
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_ids")
        public List<String> knowledgeBaseIds;
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids")
        public List<String> knowledgeIds;
        @com.fasterxml.jackson.annotation.JsonProperty("agent_enabled")
        public boolean agentEnabled;
        @com.fasterxml.jackson.annotation.JsonProperty("agent_id")
        public String agentId = "";
        @com.fasterxml.jackson.annotation.JsonProperty("agent_source_tenant_id")
        @JsonInclude(Include.NON_DEFAULT)
        public long agentSourceTenantId;
        @com.fasterxml.jackson.annotation.JsonProperty("web_search_enabled")
        public boolean webSearchEnabled;
        @com.fasterxml.jackson.annotation.JsonProperty("summary_model_id")
        public String summaryModelId = "";
        @com.fasterxml.jackson.annotation.JsonProperty("mcp_service_ids")
        public List<String> mcpServiceIds;
        @com.fasterxml.jackson.annotation.JsonProperty("skill_names")
        public List<String> skillNames;
        @com.fasterxml.jackson.annotation.JsonProperty("tag_ids")
        public List<String> tagIds;
        @com.fasterxml.jackson.annotation.JsonProperty("mentioned_items")
        public List<MentionedItemRequest> mentionedItems;
        @com.fasterxml.jackson.annotation.JsonProperty("disable_title")
        public boolean disableTitle;
        public List<ImageAttachment> images;
        @com.fasterxml.jackson.annotation.JsonProperty("attachment_uploads")
        @JsonInclude(Include.NON_DEFAULT)
        public List<AttachmentUpload> attachmentUploads;
        @com.fasterxml.jackson.annotation.JsonProperty("attachment_ids")
        @JsonInclude(Include.NON_DEFAULT)
        public List<String> attachmentIds;
        public String channel = "";
        @com.fasterxml.jackson.annotation.JsonProperty("suggestion_attribution")
        @JsonInclude(Include.NON_DEFAULT)
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
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id")
        public String knowledgeBaseId = "";
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_ids")
        public List<String> knowledgeBaseIds;
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids")
        public List<String> knowledgeIds;
        @com.fasterxml.jackson.annotation.JsonProperty("tag_ids")
        public List<String> tagIds;
        @com.fasterxml.jackson.annotation.JsonProperty("mentioned_items")
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
