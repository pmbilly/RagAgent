package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code task_pending_ops} 表里 {@code task_type="wiki:ingest"} 的一行操作。
 *
 * <p>本对象就是该行的 JSON 载荷；外围的 {@code (task_type, scope, scope_id, dedup_key)}
 * 是独立列，不在此序列化。</p>
 *
 * <p>{@link #dbId} 是载入该行的自增主键。{@code peekPendingList} 会填它；消费方
 * 带着它穿过 Map/Reduce，好让消费后删除与失败后计数都能寻址到正确的行。
 * 它<b>刻意不参与 JSON</b>，避免持久化的载荷里重复一份列值。</p>
 *
 * <p><b>改成可变 POJO 而非 record</b>：{@link #dbId} 是 peek 阶段回填的，
 * record 表达不了这种"先构造、后补主键"的时序。</p>
 */
@JsonPropertyOrder({"op", "knowledge_id", "language", "doc_title", "doc_summary",
        "page_slugs", "folder_ids"})
public class WikiPendingOp {

    /** {@code "ingest"} 或 {@code "retract"} */
    @JsonProperty("op")
    private String op = "";

    @JsonProperty("knowledge_id")
    private String knowledgeId = "";

    /** ingest 载荷：文档语言 */
    @JsonProperty("language")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String language;

    /** retract 载荷：文档标题 */
    @JsonProperty("doc_title")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String docTitle;

    /** retract 载荷：被删文档的一句话摘要 */
    @JsonProperty("doc_summary")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String docSummary;

    /** retract 载荷：该文档写过的页面 slug */
    @JsonProperty("page_slugs")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> pageSlugs;

    /** retract 载荷：该文档曾归属的目录 id */
    @JsonProperty("folder_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> folderIds;

    /**
     * 载入行的自增主键：由批次 peek 从 {@code task_pending_ops.id} 填入；
     * 在队列之外构造出来的对象里为 0。
     */
    @JsonIgnore
    private long dbId;

    public WikiPendingOp() {}

    public WikiPendingOp(String op, String knowledgeId) {
        this.op = op == null ? "" : op;
        this.knowledgeId = knowledgeId == null ? "" : knowledgeId;
    }

    /** 是否 ingest op */
    @JsonIgnore
    public boolean isIngest() {
        return WikiIngestConstants.OP_INGEST.equals(op);
    }

    /** 是否 retract op */
    @JsonIgnore
    public boolean isRetract() {
        return WikiIngestConstants.OP_RETRACT.equals(op);
    }

    public String getOp() { return op; }
    public void setOp(String v) { op = v == null ? "" : v; }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String v) { docTitle = v; }

    public String getDocSummary() { return docSummary; }
    public void setDocSummary(String v) { docSummary = v; }

    public List<String> getPageSlugs() { return pageSlugs; }
    public void setPageSlugs(List<String> v) { pageSlugs = v; }

    public List<String> getFolderIds() { return folderIds; }
    public void setFolderIds(List<String> v) { folderIds = v; }

    @JsonIgnore
    public long getDbId() { return dbId; }
    @JsonIgnore
    public void setDbId(long v) { dbId = v; }

    /**
     * docTitle 的空安全取值（日志用）。
     *
     * <p><b>必须 {@code @JsonIgnore}</b>：Jackson 会把任何 {@code getXxx()/isXxx()}
     * 形态的方法当成属性，从而把 {@code docTitleOrEmpty} 写进落库的 JSON 载荷里
     * （复发率最高的坑）。</p>
     */
    @JsonIgnore
    public String docTitleOrEmpty() {
        return docTitle == null ? "" : docTitle;
    }

    /** 供 Map/Reduce 组装列表用。同样必须 @JsonIgnore。 */
    @JsonIgnore
    public List<String> pageSlugsOrEmpty() {
        return pageSlugs == null ? new ArrayList<>() : pageSlugs;
    }
}
