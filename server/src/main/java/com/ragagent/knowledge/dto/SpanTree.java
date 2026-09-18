package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * spans 响应的合成树载体（对照 Go handler 内 buildSpanTree 的返回三元组中的
 * root + current_stage；last_failure 在 Java 侧由 knowledgeSpansLastError 直接消费，
 * 不经过本类型）。节点由 service 组装成 ObjectNode（键序 = Go struct 声明序 +
 * omitempty 语义），故这里只是不透明的 JSON 载体，不是响应体类型。
 */
public record SpanTree(ObjectNode root, String currentStage) {
}
