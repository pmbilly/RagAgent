package com.ragagent.knowledge.dto;


/** chunk 编辑请求：内容、启用态 + 乐观锁 {@code expectedRevision}。 */
public record UpdateChunkRequest(
        String content,
        Boolean enabled,
        Integer expectedRevision) {
}
