package com.ragagent.mcp.protocol;

import java.util.List;

/**
 * resources/read 结果。
 */
public record ReadResourceResult(List<ResourceContent> contents) {

    public ReadResourceResult {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }
}
