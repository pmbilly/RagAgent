package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * omitempty 语义），故这里只是不透明的 JSON 载体，不是响应体类型。
 */
public record SpanTree(ObjectNode root, String currentStage) {
}
