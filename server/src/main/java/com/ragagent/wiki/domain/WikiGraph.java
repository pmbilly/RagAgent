package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * wiki 链接图谱的类型集合：service 入参（{@link WikiGraph.Request}）与可视化响应
 * （{@link WikiGraph.Data} 及其 {@link WikiGraph.Meta}/{@link WikiGraph.Node}/{@link WikiGraph.Edge}）
 * 收敛为一个类的静态嵌套类型。
 */
public final class WikiGraph {

    private WikiGraph() {}

    /** 模式常量 */
    public static final String MODE_OVERVIEW = WikiConstants.GRAPH_MODE_OVERVIEW;
    public static final String MODE_EGO = WikiConstants.GRAPH_MODE_EGO;

    /**
     * service 层图谱查询入参。
     *
     * <p>Limit 策略：非正数表示"不设上限"，仅保留给内部调用方（如 wiki lint）取全图；
     * HTTP handler 永远会在调用 service 前把 Limit 夹到安全区间，外部流量拿不到全图。</p>
     *
     * @param knowledgeBaseId      目标知识库
     * @param mode                 "overview"（默认）| "ego"
     * @param center               ego 模式的中心 slug（Mode == ego 时必填）
     * @param depth                ego 模式 BFS 深度，&gt;= 1
     * @param types                可选 page_type 过滤；空 = 不过滤
     * @param limit                返回节点数上限；&lt;= 0 表示不设上限
     * @param familiarKnowledgeIds 该用户反复引用作答的文档 id；source_refs 与其相交的页面
     *                             被标记 Familiar，从而在既有图谱上"点亮"，无需克隆第二张图
     */
    public record Request(
            String knowledgeBaseId,
            String mode,
            String center,
            int depth,
            List<String> types,
            int limit,
            List<String> familiarKnowledgeIds) {
    }

    /** 可视化用的链接图结构 */
        public static final class Data {
        private List<Node> nodes = new ArrayList<>();
        private List<Edge> edges = new ArrayList<>();
        private Meta meta = new Meta();

        public List<Node> getNodes() { return nodes; }
        public void setNodes(List<Node> v) { this.nodes = v == null ? new ArrayList<>() : v; }

        public List<Edge> getEdges() { return edges; }
        public void setEdges(List<Edge> v) { this.edges = v == null ? new ArrayList<>() : v; }

        public Meta getMeta() { return meta; }
        public void setMeta(Meta v) { this.meta = v == null ? new Meta() : v; }
    }

    /**
     * 返回的子图与完整知识库图谱的关系。
     * 前端用 {@code truncated} 决定是否显示"显示 X / 共 Y"提示并开启 ego 展开。
     */
        public static final class Meta {
        private String mode = "";
        /** 过滤/限流之前该知识库的节点总数 */
        private int total;
        /** 实际返回的节点数 */
        private int returned;
        /** Returned < Total（过滤后）时为 true */
        private boolean truncated;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String center = "";
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int depth;
        /** 返回节点里为当前用户点亮了几个 */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int familiarCount;

        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v == null ? "" : v; }

        public int getTotal() { return total; }
        public void setTotal(int v) { this.total = v; }

        public int getReturned() { return returned; }
        public void setReturned(int v) { this.returned = v; }

        public boolean isTruncated() { return truncated; }
        public void setTruncated(boolean v) { this.truncated = v; }

        public String getCenter() { return center; }
        public void setCenter(String v) { this.center = v == null ? "" : v; }

        public int getDepth() { return depth; }
        public void setDepth(int v) { this.depth = v; }

        public int getFamiliarCount() { return familiarCount; }
        public void setFamiliarCount(int v) { this.familiarCount = v; }
    }

    /** 图谱中的一个节点 */
        public static final class Node {
        private String slug = "";
        private String title = "";
        private String pageType = "";
        /** 入链 + 出链数量 */
        private int linkCount;
        /**
         * 本页由该用户反复引用的文档构建而来时为 true。它是个人叠加层、不是页面属性：
         * 两个人看同一个 wiki 会看到不同的高亮。
         */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean familiar;

        public String getSlug() { return slug; }
        public void setSlug(String v) { this.slug = v == null ? "" : v; }

        public String getTitle() { return title; }
        public void setTitle(String v) { this.title = v == null ? "" : v; }

        public String getPageType() { return pageType; }
        public void setPageType(String v) { this.pageType = v == null ? "" : v; }

        public int getLinkCount() { return linkCount; }
        public void setLinkCount(int v) { this.linkCount = v; }

        public boolean isFamiliar() { return familiar; }
        public void setFamiliar(boolean v) { this.familiar = v; }
    }

    /** 图谱中的一条有向边 */
        public static final class Edge {
        /** 源 slug */
        private String source = "";
        /** 目标 slug */
        private String target = "";

        public Edge() {}

        public Edge(String source, String target) {
            this.source = source == null ? "" : source;
            this.target = target == null ? "" : target;
        }

        public String getSource() { return source; }
        public void setSource(String v) { this.source = v == null ? "" : v; }

        public String getTarget() { return target; }
        public void setTarget(String v) { this.target = v == null ? "" : v; }
    }
}
