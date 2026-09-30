package com.ragagent.common.wiki;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 针对某个 slug 的一次更新操作。
 *
 * <p>Map 阶段每条文档产出若干 SlugUpdate，Reduce 阶段按 slug 聚合后逐页处理。</p>
 *
 * <p><b>Type 的取值</b>：{@code "entity"} /
 * {@code "concept"} / {@code "summary"} / {@code "retract"} / {@code "retractStale"}。
 * 提成常量，让拼写错误从"运行时静默走错分支"变成编译期错误。</p>
 */
public class SlugUpdate {

    public static final String TYPE_ENTITY = "entity";
    public static final String TYPE_CONCEPT = "concept";
    public static final String TYPE_SUMMARY = "summary";
    public static final String TYPE_RETRACT = "retract";
    public static final String TYPE_RETRACT_STALE = "retractStale";

    private String slug = "";

    /** type：见本类常量 */
    private String type = "";

    /** entity/concept 抽取项 */
    private ExtractedItem item;

    private String docTitle = "";

    private String knowledgeId = "";

    private String sourceRef = "";

    /**
     * Map 阶段<b>已经解析好</b>的人类可读语言名
     * （如 {@code "Chinese (Simplified)"}），Reduce 阶段直接插进编辑 prompt，
     * <b>不是</b> locale 码。每条文档解析一次，该文档派生的所有页面共享同一语言。
     */
    private String language = "";

    /** summary 类型用 */
    private String summaryBody = "";

    /** summary 类型用 */
    private String summaryLine = "";

    /** retract / retractStale 用 */
    private String retractDocContent = "";

    /**
     * KnowledgeID 内<b>实质性支撑</b>本次更新的
     * chunk ID。与 {@link #item} 的 SourceChunks 镜像——Reduce 阶段从这里读，
     * 省一次字段跳转。
     */
    private List<String> sourceChunks;

    /**
     * 文档级摘要正文
     * （SUMMARY 行之后的全部内容；解析不出 SUMMARY 行时就是原始输出）。带在这是为了
     * 让 Reduce 阶段能用一段丰富的 {@code <source_context>} 把被引用的 chunk 框起来，
     * 告诉编辑模型这份文档<b>讲什么</b>以及是<b>哪一类</b>文档（简历 / 公告 / 产品页）。
     * 只有一句话的 SUMMARY 行对较长、多主题的源文档来说信息量太薄。
     */
    private String docSummary = "";

    public SlugUpdate() {}

    public SlugUpdate(String slug, String type) {
        this.slug = slug == null ? "" : slug;
        this.type = type == null ? "" : type;
    }

    /** retract 更新的便利工厂 */
    public static SlugUpdate retract(String slug, String knowledgeId, String docTitle,
                                     String retractDocContent, String language) {
        SlugUpdate u = new SlugUpdate(slug, TYPE_RETRACT);
        u.knowledgeId = knowledgeId == null ? "" : knowledgeId;
        u.docTitle = docTitle == null ? "" : docTitle;
        u.retractDocContent = retractDocContent == null ? "" : retractDocContent;
        u.language = language == null ? "" : language;
        return u;
    }

    /** type 是否为 retract / retractStale */
    @JsonIgnore
    public boolean isRetractType() {
        return TYPE_RETRACT.equals(type) || TYPE_RETRACT_STALE.equals(type);
    }

    public String getSlug() { return slug; }
    public void setSlug(String v) { slug = v == null ? "" : v; }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public ExtractedItem getItem() { return item; }
    public void setItem(ExtractedItem v) { item = v; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String v) { docTitle = v == null ? "" : v; }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { sourceRef = v == null ? "" : v; }

    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v == null ? "" : v; }

    public String getSummaryBody() { return summaryBody; }
    public void setSummaryBody(String v) { summaryBody = v == null ? "" : v; }

    public String getSummaryLine() { return summaryLine; }
    public void setSummaryLine(String v) { summaryLine = v == null ? "" : v; }

    public String getRetractDocContent() { return retractDocContent; }
    public void setRetractDocContent(String v) { retractDocContent = v == null ? "" : v; }

    public List<String> getSourceChunks() { return sourceChunks; }
    public void setSourceChunks(List<String> v) { sourceChunks = v; }

    public String getDocSummary() { return docSummary; }
    public void setDocSummary(String v) { docSummary = v == null ? "" : v; }

    /** null 安全的 sourceChunks 访问 */
    @JsonIgnore
    public List<String> sourceChunksOrEmpty() {
        return sourceChunks == null ? List.of() : sourceChunks;
    }
}
