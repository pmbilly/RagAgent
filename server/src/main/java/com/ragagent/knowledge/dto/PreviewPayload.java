package com.ragagent.knowledge.dto;

import java.util.List;

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
