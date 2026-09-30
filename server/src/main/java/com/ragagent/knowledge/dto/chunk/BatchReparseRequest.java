package com.ragagent.knowledge.dto.chunk;

import java.util.List;

public record BatchReparseRequest(String kbId, List<String> ids) {
}
