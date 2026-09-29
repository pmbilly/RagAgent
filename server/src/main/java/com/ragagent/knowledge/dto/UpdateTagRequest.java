package com.ragagent.knowledge.dto;


public record UpdateTagRequest(
        String name,
        String color,
        Integer sortOrder) {
}
