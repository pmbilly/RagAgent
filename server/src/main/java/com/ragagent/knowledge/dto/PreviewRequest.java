package com.ragagent.knowledge.dto;

import java.util.List;

/** 分块预览请求：待预览文本 + 可选分块配置。 */
public record PreviewRequest(String text, PreviewPayload chunkingConfig) {
}
