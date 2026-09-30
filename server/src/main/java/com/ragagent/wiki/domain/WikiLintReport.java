package com.ragagent.wiki.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次 wiki 体检的完整报告。
 *
 * <p><b>issues 允许为 null</b>：一条问题都没有时序列化成 {@code "issues":null}
 * （不是 {@code []}）。前端只做 {@code issues?.length}，出口契约按此保持。</p>
 */
public class WikiLintReport {

    private String knowledgeBaseId = "";

    /** 见类注释：空时保持 null 而非 [] */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<WikiLintIssue> issues;

    /** 0-100；只在 {@code stats.totalPages > 0} 时扣分，否则恒为 100 */
    private int healthScore;

    private WikiStats stats = new WikiStats();

    private String summary = "";

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v == null ? "" : v; }

    public List<WikiLintIssue> getIssues() { return issues; }
    public void setIssues(List<WikiLintIssue> v) { this.issues = v; }

    public int getHealthScore() { return healthScore; }
    public void setHealthScore(int v) { this.healthScore = v; }

    public WikiStats getStats() { return stats; }
    public void setStats(WikiStats v) { this.stats = v == null ? new WikiStats() : v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v == null ? "" : v; }
}
