package com.ragagent.mcp.protocol;

import java.util.List;

/**
 * resources/read 结果（对照 Go internal/mcp/types.go 的 {@code ReadResourceResult}）。
 */
public record ReadResourceResult(List<ResourceContent> contents) {

    public ReadResourceResult {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }
}
