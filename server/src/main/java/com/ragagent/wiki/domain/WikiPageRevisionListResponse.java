package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code GET /wiki/revisions/*slug} 的载荷。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>revisions 里是被取代的历史版本（列表模式下省略 content）；当前版本由
 * {@code currentVersion} + wiki_pages 行本身描述，前端已经持有。</p>
 */
@JsonPropertyOrder({"revisions", "total", "current_version"})
public class WikiPageRevisionListResponse {

    @JsonProperty("revisions")
    private List<WikiPageRevision> revisions = new ArrayList<>();

    @JsonProperty("total")
    private long total;

    @JsonProperty("current_version")
    private int currentVersion;

    public List<WikiPageRevision> getRevisions() { return revisions; }
    public void setRevisions(List<WikiPageRevision> v) { this.revisions = v == null ? new ArrayList<>() : v; }

    public long getTotal() { return total; }
    public void setTotal(long v) { this.total = v; }

    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int v) { this.currentVersion = v; }
}
