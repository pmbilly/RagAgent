package com.ragagent.knowledge.dto.chunk;

import java.util.List;

/** 分块预览的可选配置（全部可空，未传走服务端默认）。 */
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
