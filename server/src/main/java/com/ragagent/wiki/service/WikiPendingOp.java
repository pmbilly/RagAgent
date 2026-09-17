package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code task_pending_ops} 表里 {@code task_type="wiki:ingest"} 的一行操作
 * （对照 Go {@code WikiPendingOp}，wiki_ingest.go L319-345）。
 *
 * <p>本对象就是该行的 JSON 载荷；外围的 {@code (task_type, scope, scope_id, dedup_key)}
 * 是独立列，不在此序列化。</p>
 *
 * <p>{@link #dbId} 是载入该行的自增主键。{@code peekPendingList} 会填它；消费方
 * 带着它穿过 Map/Reduce，好让 {@code DeleteByIDs}（消费后）与 {@code IncrFailCount}
 * （失败后）能寻址到正确的行。它<b>刻意不参与 JSON</b>（对照 Go 的
 * {@code json:"-"}），避免持久化的载荷里重复一份列值。</p>
 *
 * <p><b>改成可变 POJO 而非 record</b>：{@link #dbId} 是 peek 阶段回填的，
 * record 表达不了这种"先构造、后补主键"的时序。</p>
 */
@JsonPropertyOrder({"op", "knowledge_id", "language", "doc_title", "doc_summary",
        "page_slugs", "folder_ids"})
public class WikiPendingOp {

    /** 对照 Go {@code WikiPendingOp.Op}：{@code "ingest"} 或 {@code "retract"} */
    @JsonProperty("op")
    private String op = "";

    /** 对照 Go {@code KnowledgeID} */
    @JsonProperty("knowledge_id")
    private String knowledgeId = "";

    /** 对照 Go 的 ingest 字段 {@code Language} */
    @JsonProperty("language")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String language;

    /** 对照 Go 的 retract 字段 {@code DocTitle} */
    @JsonProperty("doc_title")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String docTitle;

    /** 对照 Go 的 retract 字段 {@code DocSummary}（被删文档的一句话摘要） */
    @JsonProperty("doc_summary")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String docSummary;

    /** 对照 Go 的 retract 字段 {@code PageSlugs} */
    @JsonProperty("page_slugs")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> pageSlugs;

    /** 对照 Go 的 retract 字段 {@code FolderIDs} */
    @JsonProperty("folder_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> folderIds;

    /**
     * 对照 Go 的未导出字段 {@code dbID}：由 {@code peekPendingList} 从
     * {@code task_pending_ops.id} 填入；在队列之外构造出来的对象里为 0。
     */
    @JsonIgnore
    private long dbId;

    public WikiPendingOp() {}

    public WikiPendingOp(String op, String knowledgeId) {
        this.op = op == null ? "" : op;
        this.knowledgeId = knowledgeId == null ? "" : knowledgeId;
    }

    /** 对照 Go {@code WikiOpIngest} / {@code WikiOpRetract} 的便捷判定 */
    @JsonIgnore
    public boolean isIngest() {
        return WikiIngestConstants.OP_INGEST.equals(op);
    }

    /** 对照 Go {@code op.Op == WikiOpRetract} */
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
     * 对照 Go {@code DocTitle} 的空安全取值（日志用）。
     *
     * <p><b>必须 {@code @JsonIgnore}</b>：Jackson 会把任何 {@code getXxx()/isXxx()}
     * 形态的方法当成属性，从而把 {@code docTitleOrEmpty} 写进落库的 JSON 载荷里
     * （约定文档 §9 反复强调的"复发率最高的坑"）。</p>
     */
    @JsonIgnore
    public String docTitleOrEmpty() {
        return docTitle == null ? "" : docTitle;
    }

    /** 供 Map/Reduce 组装列表用（Go 直接 append 到 slice）。同样必须 @JsonIgnore。 */
    @JsonIgnore
    public List<String> pageSlugsOrEmpty() {
        return pageSlugs == null ? new ArrayList<>() : pageSlugs;
    }
}
