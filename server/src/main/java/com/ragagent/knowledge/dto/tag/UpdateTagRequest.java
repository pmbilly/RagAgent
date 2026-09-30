package com.ragagent.knowledge.dto.tag;


public record UpdateTagRequest(
        String name,
        String color,
        Integer sortOrder) {
}
