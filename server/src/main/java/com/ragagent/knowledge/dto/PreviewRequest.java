package com.ragagent.knowledge.dto;

import java.util.List;

public record PreviewRequest(String text, PreviewPayload chunkingConfig) {
}
