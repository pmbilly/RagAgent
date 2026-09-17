package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 单条 wiki 体检发现（对照 Go internal/application/service/wiki_lint.go
 * L13-45 的 {@code WikiLintIssueType} / {@code WikiLintIssueSeverity} 常量块与
 * {@code WikiLintIssue} 结构体）。
 *
 * <p>Go 里类型/严重度是 {@code string} 的自定义类型，Java 收敛为本类的
 * {@code public static final String} 常量，<b>字面值逐字相同</b>。</p>
 *
 * <p><b>@JsonInclude 说明</b>：Go 的 {@code TargetSlug} 带 {@code omitempty}，
 * 空串时整个键不出现；其余字段无 tag 选项，恒输出。</p>
 */
@JsonPropertyOrder({"type", "severity", "page_slug", "target_slug", "description", "auto_fixable"})
public class WikiLintIssue {

    // ── 问题类型（对照 Go L16-23 WikiLintIssueType 常量块） ──

    /** 没有任何入链（index 页除外） */
    public static final String ORPHAN_PAGE = "orphan_page";
    /** 出链指向不存在的 slug */
    public static final String BROKEN_LINK = "broken_link";
    /** source_refs 指向已软删的文档 */
    public static final String STALE_REF = "stale_ref";
    /** 正文提到实体/概念标题但没有对应链接 */
    public static final String MISSING_CROSS_REF = "missing_cross_ref";
    /** 正文过短 */
    public static final String EMPTY_CONTENT = "empty_content";
    /**
     * 重复 slug。对照 Go L22 声明——但 {@code RunLint} 目前<b>从不产出</b>这一类
     * （slug 的唯一性由 (kb, slug) 部分唯一索引 + service 写入路径保证）。
     * 常量照抄以保持与 Go 的枚举面一致，Java 侧同样不产出。
     */
    public static final String DUPLICATE_SLUG = "duplicate_slug";

    // ── 严重度（对照 Go L28-32 WikiLintIssueSeverity 常量块） ──

    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_ERROR = "error";

    @JsonProperty("type")
    private String type = "";

    @JsonProperty("severity")
    private String severity = "";

    @JsonProperty("page_slug")
    private String pageSlug = "";

    /**
     * 与本问题相关的另一个页面 slug：死链的目标、缺失交叉引用对应的实体 slug，
     * 或陈旧引用对应的 knowledge id。AutoFix 用这个<b>结构化字段</b>而不是解析
     * Description（对照 Go L39-42 注释）。
     */
    @JsonProperty("target_slug")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String targetSlug = "";

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("auto_fixable")
    private boolean autoFixable;

    public WikiLintIssue() {}

    /** 便利构造器：对照 Go 结构体字面量的逐字段赋值顺序 */
    public WikiLintIssue(String type, String severity, String pageSlug, String targetSlug,
                         String description, boolean autoFixable) {
        setType(type);
        setSeverity(severity);
        setPageSlug(pageSlug);
        setTargetSlug(targetSlug);
        setDescription(description);
        setAutoFixable(autoFixable);
    }

    public String getType() { return type; }
    public void setType(String v) { this.type = v == null ? "" : v; }

    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v == null ? "" : v; }

    public String getPageSlug() { return pageSlug; }
    public void setPageSlug(String v) { this.pageSlug = v == null ? "" : v; }

    public String getTargetSlug() { return targetSlug; }
    public void setTargetSlug(String v) { this.targetSlug = v == null ? "" : v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v == null ? "" : v; }

    public boolean isAutoFixable() { return autoFixable; }
    public void setAutoFixable(boolean v) { this.autoFixable = v; }
}
