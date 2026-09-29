package com.ragagent.knowledge.dto;

import java.util.List;

public record DeleteTagRequest(List<Long> excludeIds) {
}
