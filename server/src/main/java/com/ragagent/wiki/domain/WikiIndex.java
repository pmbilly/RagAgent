package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 结构化 wiki 索引响应的类型集合（对照 Go internal/types/wiki_page.go 的
 * WikiIndexGroup L780-785 / WikiIndexResponse L792-796；条目类型
 * {@code WikiIndexEntry} 因需被 MyBatis 映射，单独成文件 {@link WikiIndexEntry}）。
 */
public final class WikiIndex {

    private WikiIndex() {}

    /**
     * 把某个 page_type 的条目打成一页（对照 Go WikiIndexGroup）。
     *
     * <p>{@code total} 是该类型在知识库中的完整计数；{@code items} 是从
     * {@code NextOffset - items.size()} 开始的当前分页窗口。{@code nextCursor} 为空
     * 表示该类型的窗口已经到底。</p>
     */
    @JsonPropertyOrder({"type", "total", "items", "next_cursor"})
    public static final class Group {
        @JsonProperty("type")
        private String type = "";
        @JsonProperty("total")
        private long total;
        @JsonProperty("items")
        private List<WikiIndexEntry> items = new ArrayList<>();
        @JsonProperty("next_cursor")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String nextCursor = "";

        public String getType() { return type; }
        public void setType(String v) { this.type = v == null ? "" : v; }

        public long getTotal() { return total; }
        public void setTotal(long v) { this.total = v; }

        public List<WikiIndexEntry> getItems() { return items; }
        public void setItems(List<WikiIndexEntry> v) { this.items = v == null ? new ArrayList<>() : v; }

        public String getNextCursor() { return nextCursor; }
        public void setNextCursor(String v) { this.nextCursor = v == null ? "" : v; }
    }

    /**
     * {@code GET /wiki/index} 的返回（对照 Go WikiIndexResponse）。
     *
     * <p>过去塞在 wiki_pages.content 里的大块目录 markdown 已经移除——那里只剩
     * LLM 生成的导语。其余内容由 index 仓储的<b>瘦列投影</b>按需装配，
     * 使索引读取成本恒为 O(page_size)，与知识库规模无关。</p>
     */
    @JsonPropertyOrder({"intro", "version", "groups"})
    public static final class Response {
        @JsonProperty("intro")
        private String intro = "";
        @JsonProperty("version")
        private int version;
        @JsonProperty("groups")
        private List<Group> groups = new ArrayList<>();

        public String getIntro() { return intro; }
        public void setIntro(String v) { this.intro = v == null ? "" : v; }

        public int getVersion() { return version; }
        public void setVersion(int v) { this.version = v; }

        public List<Group> getGroups() { return groups; }
        public void setGroups(List<Group> v) { this.groups = v == null ? new ArrayList<>() : v; }
    }
}
