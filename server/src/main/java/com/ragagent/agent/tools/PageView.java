package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

/** agent wiki 工具的页面 seam 视图。 */

    // ==================== seam 视图 ====================

    /** Wiki 页视图（可变，供重命名/回滚就地改写字段）。 */
    public final class PageView {
        private String id = "";
        private long tenantId;
        private String knowledgeBaseId = "";
        private String slug = "";
        private String title = "";
        private String pageType = "";
        private String status = "";
        private String content = "";
        private String summary = "";
        private List<String> aliases = new ArrayList<>();
        private String parentSlug = "";
        private String folderId = "";
        private int sortOrder;
        private List<String> sourceRefs = new ArrayList<>();
        private List<String> chunkRefs = new ArrayList<>();
        private List<String> inLinks = new ArrayList<>();
        private List<String> outLinks = new ArrayList<>();
        private String pageMetadata = "";

        public static PageView of(String kbId, String slug) {
            PageView p = new PageView();
            p.knowledgeBaseId = kbId;
            p.slug = slug;
            return p;
        }

        public String id() { return id; }
        public void setId(String v) { id = v; }
        public long tenantId() { return tenantId; }
        public void setTenantId(long v) { tenantId = v; }
        public String knowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
        public String slug() { return slug; }
        public void setSlug(String v) { slug = v; }
        public String title() { return title; }
        public void setTitle(String v) { title = v; }
        public String pageType() { return pageType; }
        public void setPageType(String v) { pageType = v; }
        public String status() { return status; }
        public void setStatus(String v) { status = v; }
        public String content() { return content; }
        public void setContent(String v) { content = v; }
        public String summary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public List<String> aliases() { return aliases; }
        public void setAliases(List<String> v) { aliases = v != null ? v : new ArrayList<>(); }
        public String parentSlug() { return parentSlug; }
        public void setParentSlug(String v) { parentSlug = v; }
        public String folderId() { return folderId; }
        public void setFolderId(String v) { folderId = v; }
        public int sortOrder() { return sortOrder; }
        public void setSortOrder(int v) { sortOrder = v; }
        public List<String> sourceRefs() { return sourceRefs; }
        public void setSourceRefs(List<String> v) { sourceRefs = v != null ? v : new ArrayList<>(); }
        public List<String> chunkRefs() { return chunkRefs; }
        public void setChunkRefs(List<String> v) { chunkRefs = v != null ? v : new ArrayList<>(); }
        public List<String> inLinks() { return inLinks; }
        public void setInLinks(List<String> v) { inLinks = v != null ? v : new ArrayList<>(); }
        public List<String> outLinks() { return outLinks; }
        public void setOutLinks(List<String> v) { outLinks = v != null ? v : new ArrayList<>(); }
        public String pageMetadata() { return pageMetadata; }
        public void setPageMetadata(String v) { pageMetadata = v; }

        /** 深拷贝（逐字段复制）。 */
        public PageView copy() {
            PageView p = new PageView();
            p.id = id;
            p.tenantId = tenantId;
            p.knowledgeBaseId = knowledgeBaseId;
            p.slug = slug;
            p.title = title;
            p.pageType = pageType;
            p.status = status;
            p.content = content;
            p.summary = summary;
            p.aliases = new ArrayList<>(aliases);
            p.parentSlug = parentSlug;
            p.folderId = folderId;
            p.sortOrder = sortOrder;
            p.sourceRefs = new ArrayList<>(sourceRefs);
            p.chunkRefs = new ArrayList<>(chunkRefs);
            p.inLinks = new ArrayList<>(inLinks);
            p.outLinks = new ArrayList<>(outLinks);
            p.pageMetadata = pageMetadata;
            return p;
        }
    }
