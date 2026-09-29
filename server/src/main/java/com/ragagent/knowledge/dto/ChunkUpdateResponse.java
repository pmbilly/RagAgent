package com.ragagent.knowledge.dto;


/** chunk 编辑响应：更新后的 chunk + 描述 + 摘要状态。 */
public record ChunkUpdateResponse(ChunkResponse chunk, String description, String summaryStatus) {
}
