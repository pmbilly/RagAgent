package com.ragagent.common.graph;

/**
 * 图形模型值类型（原为 {@code chatpipeline.ChatManage} 的嵌套记录）。
 *
 * <p>被 knowledge（抽取）、retrieval（Neo4j 落库）与 chatpipeline 三域共用——纯值、零域依赖，
 * 故提到 common（先例：ResponseType、StorageAllowList）。</p>
 */
public record GraphRelation(String node1, String node2, String type) {}
