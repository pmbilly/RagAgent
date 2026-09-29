package com.ragagent.knowledge.dto;

import java.util.List;

public record BatchReparseRequest(String kbId, List<String> ids) {
}
