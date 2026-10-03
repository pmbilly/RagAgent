package com.ragagent.agent.tools.wiki;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.web.ToolJson;

/** agent wiki 工具的 issue seam 视图。 */

    /** issue 视图（时间字段以 RFC3339 文本透传保证逐字节输出）。 */
    public final class IssueView {
        private String id = "";
        private long tenantId;
        private String knowledgeBaseId = "";
        private String slug = "";
        private String issueType = "";
        private String description = "";
        private List<String> suspectedKnowledgeIds = new ArrayList<>();
        private String status = "";
        private String reportedBy = "";
        private String createdAt = "";
        private String updatedAt = "";
        private boolean deletedAtValid;
        private String deletedAt = "";

        public String id() { return id; }
        public void setId(String v) { id = v; }
        public long tenantId() { return tenantId; }
        public void setTenantId(long v) { tenantId = v; }
        public String knowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
        public String slug() { return slug; }
        public void setSlug(String v) { slug = v; }
        public String issueType() { return issueType; }
        public void setIssueType(String v) { issueType = v; }
        public String description() { return description; }
        public void setDescription(String v) { description = v; }
        public List<String> suspectedKnowledgeIds() { return suspectedKnowledgeIds; }
        public void setSuspectedKnowledgeIds(List<String> v) { suspectedKnowledgeIds = v != null ? v : new ArrayList<>(); }
        public String status() { return status; }
        public void setStatus(String v) { status = v; }
        public String reportedBy() { return reportedBy; }
        public void setReportedBy(String v) { reportedBy = v; }
        public String createdAt() { return createdAt; }
        public void setCreatedAt(String v) { createdAt = v; }
        public String updatedAt() { return updatedAt; }
        public void setUpdatedAt(String v) { updatedAt = v; }
        public boolean deletedAtValid() { return deletedAtValid; }
        public void setDeletedAtValid(boolean v) { deletedAtValid = v; }
        public String deletedAt() { return deletedAt; }
        public void setDeletedAt(String v) { deletedAt = v; }

        /**
         * 两空格缩进的 JSON 字节形态。
         * deletedAt 时间戳无效时序列化为 null。
         */
        public String toGoJsonIndent() {
            // 嵌套数组非空时逐元素换行缩进
            StringBuilder b = new StringBuilder();
            b.append("{\n");
            b.append("  \"id\": ").append(goJsonString(id)).append(",\n");
            b.append("  \"tenant_id\": ").append(tenantId).append(",\n");
            b.append("  \"knowledge_base_id\": ").append(goJsonString(knowledgeBaseId)).append(",\n");
            b.append("  \"slug\": ").append(goJsonString(slug)).append(",\n");
            b.append("  \"issue_type\": ").append(goJsonString(issueType)).append(",\n");
            b.append("  \"description\": ").append(goJsonString(description)).append(",\n");
            b.append("  \"suspected_knowledge_ids\": ");
            if (suspectedKnowledgeIds == null || suspectedKnowledgeIds.isEmpty()) {
                b.append("[]");
            } else {
                b.append("[\n");
                for (int i = 0; i < suspectedKnowledgeIds.size(); i++) {
                    if (i > 0) {
                        b.append(",\n");
                    }
                    b.append("    ").append(goJsonString(suspectedKnowledgeIds.get(i)));
                }
                b.append("\n  ]");
            }
            b.append(",\n");
            b.append("  \"status\": ").append(goJsonString(status)).append(",\n");
            b.append("  \"reported_by\": ").append(goJsonString(reportedBy)).append(",\n");
            b.append("  \"created_at\": ").append(goJsonString(createdAt)).append(",\n");
            b.append("  \"updated_at\": ").append(goJsonString(updatedAt)).append(",\n");
            b.append("  \"deleted_at\": ").append(deletedAtValid ? goJsonString(deletedAt) : "null").append("\n");
            b.append('}');
            return b.toString();
        }

        private static String goJsonString(String s) {
            StringBuilder sb = new StringBuilder();
            ToolJson.writeString(s == null ? "" : s, sb);
            return sb.toString();
        }
    }
