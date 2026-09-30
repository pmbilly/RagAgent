package com.ragagent.common.graph;

import java.util.List;

/**
 * 图形模型值类型（原为 {@code chatpipeline.ChatManage} 的嵌套记录）。
 *
 * <p>被 knowledge（抽取）、retrieval（Neo4j 落库）与 chatpipeline 三域共用——纯值、零域依赖，
 * 故提到 common（先例：ResponseType、StorageAllowList）。</p>
 */
public final class GraphNode {
    private String name = "";
    private List<String> chunks;
    private List<String> attributes;

    public GraphNode() {}

    public GraphNode(String name, List<String> chunks, List<String> attributes) {
        this.name = name == null ? "" : name;
        this.chunks = chunks;
        this.attributes = attributes;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public List<String> getChunks() { return chunks; }
    public void setChunks(List<String> v) { chunks = v; }
    public List<String> getAttributes() { return attributes; }
    public void setAttributes(List<String> v) { attributes = v; }
}
