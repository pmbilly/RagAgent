package com.ragagent.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * chunker 预览请求（chunking_config 各字段可缺省，缺省值由 Chunker.normalizeSplitterConfig
 * 兜底）。响应形状由 ChunkerDebugController 内的响应装配类定义（标准 Jackson 序列化）。
 */
public final class ChunkerDtos {

    private ChunkerDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PreviewRequest(String text, PreviewPayload chunkingConfig) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PreviewPayload(
            Integer chunkSize,
            Integer chunkOverlap,
            List<String> separators,
            Boolean enableParentChild,
            Integer parentChunkSize,
            Integer childChunkSize,
            String strategy,
            Integer tokenLimit,
            List<String> languages) {
    }
}
