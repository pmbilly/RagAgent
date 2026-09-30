package com.ragagent.knowledge.dto.tag;

import java.util.List;

public record DeleteTagRequest(List<Long> excludeIds) {
}
